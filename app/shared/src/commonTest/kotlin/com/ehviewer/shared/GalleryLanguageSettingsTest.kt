@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ehviewer.shared

import com.ehviewer.core.domain.AppLanguageException
import com.ehviewer.core.domain.AppLanguageStore
import com.ehviewer.shared.settings.GalleryLanguageSettings
import com.ehviewer.shared.settings.galleryResourceLanguageTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private class LanguageTestStore : AppLanguageStore {
    var saved: String? = null
    var writes = 0
    var readFailure: Exception? = null
    var writeFailure: Exception? = null
    var cancelAfterWrite = false
    override suspend fun tag(): String? {
        readFailure?.let { throw it }
        return saved
    }
    override suspend fun setTag(tag: String?) {
        writeFailure?.let { throw it }
        saved = tag
        writes++
        if (cancelAfterWrite) throw CancellationException()
    }
}

class GalleryLanguageSettingsTest {
    private fun onMain(test: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            test()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun openingSettingsAppliesTheSavedChoiceWithoutWritingOrReplacingUnsupportedTags() = onMain {
        val store = LanguageTestStore()
        val applied = mutableListOf<String?>()
        val settings = GalleryLanguageSettings(store) { applied += it }
        assertNull(settings.tag())
        store.saved = "fr-CA"
        assertEquals("fr-CA", settings.tag())
        assertEquals(listOf(null, "fr-CA"), applied)
        assertEquals(0, store.writes)
    }

    @Test fun anApplyFailureKeepsTheCommittedChoiceAndReloadAppliesItWithoutAnotherWrite() = onMain {
        val store = LanguageTestStore()
        var fail = true
        var visible: String? = null
        val settings = GalleryLanguageSettings(store) {
            assertEquals(it, store.saved)
            if (fail) error("owned-private-locale-backend")
            visible = it
        }
        val failure = assertFailsWith<AppLanguageException> { settings.setTag("ja") }
        assertEquals("Language preferences could not be loaded or saved", failure.message)
        assertNull(failure.cause)
        assertEquals("ja", store.saved)
        assertNull(visible)
        fail = false
        assertEquals("ja", settings.tag())
        assertEquals("ja", visible)
        assertEquals(1, store.writes)
    }

    @Test fun unreadableOrInvalidSavedChoicesCannotApplyOrOverwriteThePreference() = onMain {
        val store = LanguageTestStore()
        var applied = false
        val settings = GalleryLanguageSettings(store) { applied = true }
        store.saved = "ja"
        store.readFailure = IllegalStateException("owned-private-preference-path")
        assertFailsWith<AppLanguageException> { settings.tag() }
        store.readFailure = null
        store.saved = "../invalid"
        assertFailsWith<AppLanguageException> { settings.tag() }
        assertFailsWith<IllegalArgumentException> { settings.setTag("../invalid") }
        assertEquals(false, applied)
        assertEquals(0, store.writes)
        assertEquals("../invalid", store.saved)
    }

    @Test fun failureAndCancellationDoNotApplyAnUnconfirmedWriteAndReloadReconcilesAnAdmittedWrite() = onMain {
        val store = LanguageTestStore().also { it.saved = "en" }
        val applied = mutableListOf<String?>()
        val settings = GalleryLanguageSettings(store) { applied += it }
        store.writeFailure = IllegalStateException("owned-private-preference-path")
        assertFailsWith<AppLanguageException> { settings.setTag("ja") }
        assertEquals("en", store.saved)
        store.writeFailure = CancellationException()
        assertFailsWith<CancellationException> { settings.setTag("ja") }
        store.writeFailure = null
        store.readFailure = CancellationException()
        assertFailsWith<CancellationException> { settings.tag() }
        store.readFailure = null
        store.cancelAfterWrite = true
        assertFailsWith<CancellationException> { settings.setTag("zh-TW") }
        assertEquals(emptyList(), applied)
        assertEquals("zh-TW", store.saved)
        store.cancelAfterWrite = false
        assertEquals("zh-TW", settings.tag())
        assertEquals(listOf<String?>("zh-TW"), applied)
        settings.setTag(null)
        assertNull(store.saved)
        assertEquals(listOf("zh-TW", null), applied)
    }

    @Test fun chineseScriptTakesPriorityOverRegionWithoutReinterpretingPrivateOrUnicodeExtensions() {
        for ((tag, resource) in listOf(
            "zh-Hant-SG" to "zh-TW", "zh-Hans-TW" to "zh-CN", "zh-Hant" to "zh-TW",
            "zh-HK" to "zh-TW", "zh-MO" to "zh-TW", "zh-TW" to "zh-TW", "zh-CN" to "zh-CN",
            "zh-x-tw" to "zh-CN", "zh-u-ca-hant" to "zh-CN", "ja" to "ja", "fr-CA" to "fr-CA",
        )) {
            assertEquals(resource, galleryResourceLanguageTag(tag), tag)
        }
    }
}
