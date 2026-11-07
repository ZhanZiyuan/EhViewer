@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hippo.ehviewer.ios

import com.ehviewer.shared.settings.GalleryLanguageSettings
import com.ehviewer.shared.settings.galleryResourceLanguageTag
import com.ehviewer.shared.verifyGalleryLanguageProduct
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

private const val LANGUAGE_OVERRIDE_KEY = "EhViewerResourceLanguageOverrideV1"

/** Public Foundation locale override; preserve any pre-existing per-app Apple language choice. */
internal class AppleGalleryLanguage {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val languages = "AppleLanguages"
    private val bundle = checkNotNull(NSBundle.mainBundle.bundleIdentifier)

    private fun restoreOwnedOverride() {
        val record = defaults.dictionaryForKey(LANGUAGE_OVERRIDE_KEY) ?: return
        check(record.keys.all { it == "applied" || it == "previous" })
        val applied = record["applied"] as? String ?: error("Invalid language override record")
        val previous = record["previous"]
        check(!record.containsKey("previous") || previous is List<*> && previous.all { it is String })
        // Never replace an external Settings change with our older backup.
        if (defaults.arrayForKey(languages) == listOf(applied)) {
            if (previous == null) defaults.removeObjectForKey(languages) else defaults.setObject(previous, forKey = languages)
        }
        defaults.removeObjectForKey(LANGUAGE_OVERRIDE_KEY)
    }

    fun apply(tag: String?) {
        restoreOwnedOverride()
        val system = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"
        val requested = tag ?: system
        val effective = galleryResourceLanguageTag(requested)
        if (tag == null && effective == system) return
        // Snapshot only the app domain; copying the global fallback would pin System to an old OS language.
        val previous = defaults.persistentDomainForName(bundle)?.get(languages)
        check(previous == null || previous is List<*> && previous.all { it is String })
        val record = mutableMapOf<String, Any>("applied" to effective)
        if (previous != null) record["previous"] = previous
        // Save ownership first, so an interrupted launch can safely restore the original choice.
        defaults.setObject(record, forKey = LANGUAGE_OVERRIDE_KEY)
        defaults.setObject(listOf(effective), forKey = languages)
    }
}

/** Read real assets and verify that a System reset never leaves a copied global language in app defaults. */
internal suspend fun verifyAppleLanguageProduct(settings: GalleryLanguageSettings): Int {
    settings.tag()
    val defaults = NSUserDefaults.standardUserDefaults
    val bundle = checkNotNull(NSBundle.mainBundle.bundleIdentifier)
    val before = defaults.persistentDomainForName(bundle)?.get("AppleLanguages")
    val ownership = defaults.dictionaryForKey(LANGUAGE_OVERRIDE_KEY)
    val checks = verifyGalleryLanguageProduct(settings)
    check(before == defaults.persistentDomainForName(bundle)?.get("AppleLanguages"))
    check(ownership == defaults.dictionaryForKey(LANGUAGE_OVERRIDE_KEY))
    return checks
}
