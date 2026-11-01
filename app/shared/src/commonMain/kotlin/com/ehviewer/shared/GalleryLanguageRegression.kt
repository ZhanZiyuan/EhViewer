package com.ehviewer.shared

import com.ehviewer.shared.settings.GalleryLanguageSettings
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_language_current
import ehviewer.app.shared.generated.resources.gallery_local_library
import ehviewer.app.shared.generated.resources.gallery_page_count
import ehviewer.app.shared.generated.resources.gallery_reading_continuous
import ehviewer.app.shared.generated.resources.gallery_search_galleries
import ehviewer.app.shared.generated.resources.gallery_settings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/** Explicit runtime regression: read the actual packaged CMP resources through the real platform. */
suspend fun verifyGalleryLanguageProduct(settings: GalleryLanguageSettings): Int {
    val original = settings.tag()
    var completed = 0
    try {
        for (expected in listOf(
            listOf("en", "Search galleries", "Local library", "Continuous reading", "Settings", "7 pages"),
            listOf("zh-CN", "搜索图库", "本地书库", "连续阅读", "设置", "7 页"),
            listOf("zh-TW", "搜尋圖庫", "本機書庫", "連續閱讀", "設定", "7 頁"),
            listOf("ja", "ギャラリーを検索", "ローカルライブラリ", "連続表示", "設定", "7 ページ"),
        )) {
            settings.setTag(expected[0])
            check(settings.tag() == expected[0])
            val actual = listOf(
                getString(Res.string.gallery_search_galleries),
                getString(Res.string.gallery_local_library),
                getString(Res.string.gallery_reading_continuous),
                getString(Res.string.gallery_settings),
                getString(Res.string.gallery_page_count, 7),
            )
            check(actual == expected.drop(1)) { "Packaged language resources did not match ${expected[0]}" }
            check(expected[0] in getString(Res.string.gallery_language_current, expected[0]))
            completed++
        }
    } finally {
        withContext(NonCancellable) { settings.setTag(original) }
    }
    return completed
}
