package com.ehviewer.shared

import coil3.Extras
import kotlinx.coroutines.CancellationException

/** Request-scoped ownership port for the application's existing native WebP decoder. */
class GalleryAnimationLease(private val onFailure: (GalleryAnimationLease) -> Unit = {}) : AutoCloseable {
    private val resources = mutableListOf<AutoCloseable>()
    private var closed = false
    val isClosed: Boolean get() = synchronized(this) { closed }

    fun notifyFailure() {
        if (!isClosed) onFailure(this)
    }

    fun own(resource: AutoCloseable) {
        val accepted = synchronized(this) {
            if (closed) {
                false
            } else {
                resources += resource
                true
            }
        }
        if (!accepted) {
            resource.close()
            throw CancellationException("Reader animation request was disposed")
        }
    }

    override fun close() {
        val owned = synchronized(this) {
            if (closed) return
            closed = true
            resources.toList().also { resources.clear() }
        }
        owned.forEach(AutoCloseable::close)
    }
}

val galleryAnimationLeaseKey = Extras.Key<GalleryAnimationLease?>(default = null)
