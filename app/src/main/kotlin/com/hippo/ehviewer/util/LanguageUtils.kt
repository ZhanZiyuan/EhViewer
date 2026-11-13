package com.hippo.ehviewer.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.ehviewer.core.i18n.R

private val selectableAppLanguages = listOf("en", "ja", "zh-CN", "zh-HK", "zh-TW")

context(ctx: Context)
fun getLanguages(): Map<String, String> = buildMap {
    put("system", ctx.getString(R.string.app_language_system))
    selectableAppLanguages.forEach { put(it, getLanguageDisplayName(it)) }
}

fun getLanguageDisplayName(language: String): String = LocaleListCompat.forLanguageTags(language)[0]?.let { locale ->
    locale.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) }
}.orEmpty()

fun getAppLanguage(): String {
    val locale = AppCompatDelegate.getApplicationLocales()[0]
    return when (val language = locale?.language) {
        null -> "system"
        "zh", "nb" -> locale.country.takeIf(String::isNotEmpty)?.let { "$language-$it" } ?: language
        else -> language
    }
}

fun setAppLanguage(language: String) {
    val locales = if (language == "system") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(language)
    }
    AppCompatDelegate.setApplicationLocales(locales)
}
