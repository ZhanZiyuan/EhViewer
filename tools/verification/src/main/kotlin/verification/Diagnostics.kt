package verification

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val magic = "EHVDIAG1".toByteArray()
private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

private fun pem(label: String, bytes: ByteArray): String = "-----BEGIN $label-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes)}\n-----END $label-----\n"

private fun readPem(path: Path, label: String): ByteArray {
    val text = path.readText().trim()
    require(text.startsWith("-----BEGIN $label-----") && text.endsWith("-----END $label-----")) { "Expected $label PEM" }
    return Base64.getDecoder().decode(text.removePrefix("-----BEGIN $label-----").removeSuffix("-----END $label-----").filterNot(Char::isWhitespace))
}

fun diagnosticsKey(directory: Path): Map<String, Any?> {
    privateDirectory(directory)
    require(directory.listDirectoryEntries().isEmpty()) { "Refusing to replace an existing diagnostics key" }
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
    listOf("private.pem" to pem("PRIVATE KEY", pair.private.encoded), "public.pem" to pem("PUBLIC KEY", pair.public.encoded)).forEach { (name, contents) ->
        Files.createFile(directory.resolve(name), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(permissions600)).writeText(contents)
    }
    return mapOf("public_key" to directory.resolve("public.pem").toString(), "private_key" to directory.resolve("private.pem").toString(), "algorithm" to "RSA-3072")
}

fun encryptDiagnostics(directory: Path, publicKey: Path, output: Path): Map<String, Any?> = temporary { temp ->
    privateDirectory(directory)
    val names = setOf("mapping.txt", "native-debug-symbols.zip", "manifest.json")
    require(directory.listDirectoryEntries().map { it.name }.toSet() == names) { "Expected precisely mapping, symbols and retained manifest" }
    val bundle = temp.resolve("diagnostics.zip")
    ZipOutputStream(bundle.outputStream()).use { zip ->
        names.sorted().forEach { name ->
            val file = directory.resolve(name)
            require(file.isRegularFile(NOFOLLOW_LINKS) && file.fileSize() > 0) { "Invalid diagnostic input" }
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
    encryptFile(bundle, publicKey, output)
    mapOf("file" to output.toString(), "sha256" to sha256(output), "bytes" to output.fileSize(), "algorithm" to "RSA-OAEP-SHA256 + AES-256-GCM", "retention_days" to 90)
}

fun encryptFile(input: Path, publicKey: Path, output: Path) {
    require(!output.exists(NOFOLLOW_LINKS)) { "Refusing to overwrite encrypted diagnostics" }
    val public = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(readPem(publicKey, "PUBLIC KEY")))
    require((public as RSAKey).modulus.bitLength() >= 3072) { "RSA key must be at least 3072 bits" }
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val wrapped = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.ENCRYPT_MODE, public, oaep) }.doFinal(key.encoded)
    val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
    val header = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use {
            it.write(magic)
            it.writeInt(wrapped.size)
            it.write(wrapped)
            it.write(nonce)
        }
    }.toByteArray()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        updateAAD(header)
    }
    Files.createFile(output, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(permissions600))
    try {
        output.outputStream().use { stream ->
            stream.write(header)
            CipherOutputStream(stream, cipher).use { encrypted -> input.inputStream().use { it.copyTo(encrypted) } }
        }
    } catch (failure: Exception) {
        Files.deleteIfExists(output)
        throw failure
    }
}

fun decryptDiagnostics(input: Path, privateKey: Path, output: Path): Map<String, Any?> = temporary { temp ->
    require(!output.exists(NOFOLLOW_LINKS)) { "Refusing to overwrite decrypted diagnostics" }
    privateDirectory(output.parent)
    val private = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(readPem(privateKey, "PRIVATE KEY")))
    val decrypted = temp.resolve("diagnostics.zip")
    DataInputStream(input.inputStream()).use { stream ->
        require(stream.readNBytes(magic.size).contentEquals(magic)) { "Invalid diagnostics header" }
        val size = stream.readInt()
        require(size in 384..1024 && size == ((private as RSAKey).modulus.bitLength() + 7) / 8) { "Invalid encrypted key size" }
        val wrapped = stream.readNBytes(size)
        val nonce = stream.readNBytes(12)
        require(wrapped.size == size && nonce.size == 12) { "Truncated diagnostics header" }
        val key = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.DECRYPT_MODE, private, oaep) }.doFinal(wrapped)
        val header = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.write(magic)
                it.writeInt(size)
                it.write(wrapped)
                it.write(nonce)
            }
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header)
        }
        // Authentication must finish before any plaintext is retained at the destination.
        CipherInputStream(stream, cipher).use { clear -> decrypted.outputStream().use { clear.copyTo(it) } }
    }
    retainFile(decrypted, output)
    mapOf("file" to output.toString(), "sha256" to sha256(output))
}
