package com.ehviewer.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.ehviewer.core.domain.AppLanguageStore
import com.ehviewer.core.domain.GalleryListDensity
import com.ehviewer.core.domain.GalleryListPreferences
import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.shared.settings.GalleryAppearanceStore
import com.ehviewer.shared.settings.GalleryLanguageSettings
import com.ehviewer.shared.settings.GalleryThemeMode
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_back
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GallerySettingsNavigationTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun originalCategoriesOpenActualPagesAndBackReturnsToRootBeforeLeavingSettings() {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            for (width in listOf(320, 840, 1280)) {
                for (font in listOf(1f, 1.8f)) {
                    var leaves = 0
                    var accounts = 0
                    var histories = 0
                    var backLabel = ""
                    val urls = mutableListOf<String>()
                    val appearance = object : GalleryAppearanceStore {
                        override suspend fun mode() = GalleryThemeMode.LIGHT
                        override suspend fun setMode(mode: GalleryThemeMode) = error("Navigation must not save appearance")
                    }
                    val reader = object : ReaderStateStore {
                        override suspend fun preferences() = ReaderPreferences()
                        override suspend fun position(gid: Long) = null
                        override suspend fun setPreferences(preferences: ReaderPreferences) = error("Navigation must not save reading preferences")
                        override suspend fun savePosition(gid: Long, index: Int, pageToken: String?) = error("Navigation must not save reading progress")
                    }
                    val language = GalleryLanguageSettings(object : AppLanguageStore {
                        override suspend fun tag(): String? = null
                        override suspend fun setTag(tag: String?) = error("Navigation must not change language")
                    }) { error("Navigation must not change locale") }
                    val listPreferences = object : GalleryListPreferences {
                        override suspend fun density() = GalleryListDensity.COMPACT
                        override suspend fun setDensity(density: GalleryListDensity) = error("Selecting the saved width must not write preferences")
                        override suspend fun showComments() = true
                        override suspend fun setShowComments(show: Boolean) = error("Navigation must not change comment visibility")
                    }
                    val scene = ImageComposeScene(width, 1100, Density(1f, font)) {
                        CompositionLocalProvider(
                            LocalUriHandler provides object : UriHandler {
                                override fun openUri(uri: String) {
                                    urls += uri
                                }
                            },
                        ) {
                            MaterialTheme {
                                backLabel = galleryString(Res.string.gallery_back)
                                GallerySettingsScreen(null, appearance, reader, GalleryThemeMode.LIGHT, false, language, null, true, false, {}, {}, {}, {}, { leaves++ }, listPreferences = listPreferences, listDensity = GalleryListDensity.COMPACT, showComments = true, onAccount = { accounts++ }, onHistory = { histories++ })
                            }
                        }
                    }
                    var frame = 0L
                    fun render() = repeat(5) {
                        scene.render(frame).close()
                        frame += 16_666_667
                    }
                    fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                    fun text(label: String) = requireNotNull(all().firstOrNull { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } }) { "Missing $label in actual rendered labels: ${all().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }}" }
                    fun click(node: SemanticsNode) {
                        val point = node.touchBoundsInRoot.center
                        scene.sendPointerEvent(PointerEventType.Press, point)
                        render()
                        scene.sendPointerEvent(PointerEventType.Release, point)
                        render()
                    }
                    fun back() = click(all().first { backLabel in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
                    fun choose(label: String) {
                        // The persisted summary has the same text behind the popup. Activate
                        // the real selected/menu-item node rather than that obscured summary.
                        val item = all().last { node ->
                            node.config.getOrNull(SemanticsProperties.Selected) != null && nodes(node).any {
                                it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
                            }
                        }
                        click(item)
                        // Finish Material's actual popup exit animation before checking removal.
                        repeat(3) { render() }
                    }
                    try {
                        render()
                        val labels = listOf("EH", "Download", "Privacy", "Advanced", "About")
                        val positions = labels.map { text(it).boundsInRoot }
                        assertTrue(positions.zipWithNext().all { (a, b) -> a.bottom <= b.top })
                        assertTrue(positions.all { it.left == 56f && it.right <= width && it.bottom < 1100 }, "Actual root text bounds $width/$font: $positions")
                        labels.forEach { label ->
                            click(text(label))
                            assertTrue(all().none { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == if (label == "About") "Advanced" else "About" } }, "Root categories must leave the content tree")
                            when (label) {
                                "EH" -> {
                                    text("Reading")
                                    click(text("Dark theme"))
                                    listOf("Follow system", "Always off", "Always on").forEach { text(it) }
                                    // Selecting the persisted choice closes the actual menu without a write.
                                    choose("Always off")
                                    assertTrue(all().none { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == "Always on" } })
                                    click(text("Detail width"))
                                    text("Long")
                                    text("Short")
                                    choose("Short")
                                    assertTrue(all().none { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text in listOf("Long", "Comfortable columns", "Compact columns") } })
                                    text("Show gallery comments")
                                }
                                "Download" -> text("Download storage is unavailable.")
                                "Privacy" -> {
                                    click(text("Account"))
                                    click(text("History"))
                                }
                                "Advanced" -> {
                                    assertTrue(all().none { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == "Dark theme" } })
                                    click(text("App language"))
                                    text("English")
                                    text("日本語")
                                    choose("System Language (Default)")
                                    assertTrue(all().none { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == "日本語" } })
                                }
                                "About" -> {
                                    click(text("Source code"))
                                    click(text("License"))
                                }
                            }
                            back()
                            labels.forEach { text(it) }
                            assertEquals(0, leaves)
                        }
                        assertEquals(1, accounts)
                        assertEquals(1, histories)
                        assertEquals(listOf("https://github.com/ZhanZiyuan/EhViewer", "https://github.com/ZhanZiyuan/EhViewer/blob/main/LICENSE"), urls)
                        back()
                        assertEquals(1, leaves)
                    } finally {
                        scene.close()
                    }
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
