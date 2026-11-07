package com.ehviewer.core.network.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Desktop JNI adapter. The application owns loading its bundled original Rust JNI library. */
class NativeSessionVault(private val directory: String) : SessionVault {
    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) { readSnapshot(directory) }
    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { writeSnapshot(directory, bytes) }
    override suspend fun clear() = withContext(Dispatchers.IO) { clearSnapshot(directory) }

    private external fun readSnapshot(directory: String): ByteArray?
    private external fun writeSnapshot(directory: String, bytes: ByteArray)
    private external fun clearSnapshot(directory: String)
}
