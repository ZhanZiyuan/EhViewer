package verification

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxImageInventoryTest {
    @Test fun internalRelativeLinksRetainTheirContentAndExternalLinksAreRejected() {
        temporary { temporary ->
            val image = Files.createDirectory(temporary.resolve("image"))
            val file = Files.writeString(image.resolve("page"), "owned inventory input")
            Files.createSymbolicLink(image.resolve("internal"), Path.of("page"))
            val inventory = linuxImageInventory(image)
            assertEquals(setOf("page", "internal"), inventory.keys)
            assertEquals(sha256(file), inventory.getValue("internal")["sha256"])
            assertEquals("symlink", inventory.getValue("internal")["kind"])
            val outside = Files.writeString(temporary.resolve("outside"), "owned outside input")
            Files.createSymbolicLink(image.resolve("external"), Path.of("../outside"))
            assertTrue(runCatching { linuxImageInventory(image) }.isFailure)
            Files.delete(image.resolve("external"))
            Files.createSymbolicLink(image.resolve("absolute"), file)
            assertTrue(runCatching { linuxImageInventory(image) }.isFailure)
            assertEquals("owned outside input", Files.readString(outside))
        }
    }
}
