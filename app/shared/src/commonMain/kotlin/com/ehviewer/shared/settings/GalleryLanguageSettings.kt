package com.ehviewer.shared.settings

import com.ehviewer.core.domain.AppLanguageException
import com.ehviewer.core.domain.AppLanguageStore
import com.ehviewer.core.domain.validateAppLanguageTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The store owns persistence; the platform callback applies the public resource locale API. */
class GalleryLanguageSettings(private val store: AppLanguageStore, private val applyResourceLocale: (String?) -> Unit) {
    private suspend fun <T> stored(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw AppLanguageException()
    }

    suspend fun tag(): String? = stored {
        val tag = store.tag()
        validateAppLanguageTag(tag)
        withContext(Dispatchers.Main) { applyResourceLocale(tag) }
        tag
    }

    suspend fun setTag(tag: String?) {
        validateAppLanguageTag(tag)
        stored {
            store.setTag(tag)
            withContext(Dispatchers.Main) { applyResourceLocale(tag) }
        }
    }
}

/** CMP 1.11's public environment matches language/region; honor Chinese script before region. */
fun galleryResourceLanguageTag(tag: String): String {
    val parts = tag.split('-')
    if (!parts.first().equals("zh", ignoreCase = true)) return tag
    val script = parts.getOrNull(1)?.takeIf { it.length == 4 && it.all(Char::isLetter) }
    val region = parts.getOrNull(if (script == null) 1 else 2)
    val traditional = when {
        script.equals("Hant", ignoreCase = true) -> true
        script.equals("Hans", ignoreCase = true) -> false
        else -> region?.uppercase() in setOf("TW", "HK", "MO")
    }
    return if (traditional) "zh-TW" else "zh-CN"
}
