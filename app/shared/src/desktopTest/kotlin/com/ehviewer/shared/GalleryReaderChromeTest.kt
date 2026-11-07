package com.ehviewer.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_reader_show_controls
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

class GalleryReaderChromeTest {
    @Test
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun visibleTicksUseTheActualReaderWidthWhenHostWindowMetadataIsUnready() {
        var pages by mutableIntStateOf(11)
        val chrome = GalleryReaderChromeState().apply { visible = true }
        val scene = ImageComposeScene(1206, 2622, Density(3f)) {
            val host = LocalWindowInfo.current
            CompositionLocalProvider(
                LocalWindowInfo provides object : WindowInfo by host {
                    override val containerSize: IntSize get() = IntSize.Zero
                },
            ) {
                GalleryTheme(false, null) {
                    GalleryReaderChrome("Gallery", 0, pages, chrome, {}, {}, {}, actions = {}) {}
                }
            }
        }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun steps() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.single { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.config[SemanticsProperties.ProgressBarRangeInfo].steps
        try {
            repeat(30) { scene.render(it * 16_666_667L).close() }
            assertEquals(9, steps(), "Eleven-page ticks must fit the actual phone viewport even before host metadata is ready")
            pages = Int.MAX_VALUE
            repeat(3) { scene.render((31 + it) * 16_666_667L).close() }
            assertEquals(0, steps(), "Dense galleries must not allocate billions of tick fractions")
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun navigatorEndLabelsUseOriginalBodyTypographyAndMirrorWithReadingDirection() {
        for (width in listOf(360, 1280)) {
            for (dark in listOf(false, true)) {
                for (font in listOf(1f, 1.8f)) {
                    for (rtl in listOf(false, true)) {
                        for (uiRtl in listOf(false, true)) {
                            var enabled by mutableStateOf(true)
                            var jumps = 0
                            val chrome = GalleryReaderChromeState().apply { visible = true }
                            val scene = ImageComposeScene(width, 720, Density(1f, font)) {
                                GalleryTheme(dark, null) {
                                    CompositionLocalProvider(LocalLayoutDirection provides if (uiRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                        GalleryReaderChrome("Gallery", 0, 61, chrome, {}, { jumps++ }, {}, rtl = rtl, seekEnabled = enabled, actions = {}) {}
                                    }
                                }
                            }
                            var frame = 0L
                            fun render(times: Int = 3) = repeat(times) { scene.render(frame++ * 16_666_667L).close() }
                            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                            fun text(value: String) = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.single {
                                it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { text -> text.text } == value
                            }
                            fun click(point: Offset) {
                                scene.sendPointerEvent(PointerEventType.Press, point)
                                render(1)
                                scene.sendPointerEvent(PointerEventType.Release, point)
                                render()
                            }
                            try {
                                render(30)
                                val current = text("1")
                                val total = text("61")
                                val progress = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.single { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.config[SemanticsProperties.ProgressBarRangeInfo]
                                assertEquals(if (width == 360) 0 else 59, progress.steps, "Original4dp tick density intentionally suppresses61-page ticks on phones")
                                assertEquals("1 / 61", current.config[SemanticsProperties.StateDescription], "The separate visual labels must retain an announced current/total position")
                                val keyline = if (width == 360) 32f else 40f
                                assertEquals(keyline, if (rtl) width - current.boundsInRoot.right else current.boundsInRoot.left)
                                assertEquals(keyline, if (rtl) total.boundsInRoot.left else width - total.boundsInRoot.right)
                                assertEquals(current.boundsInRoot.center.y, total.boundsInRoot.center.y)
                                for (label in listOf(current, total)) {
                                    val layouts = mutableListOf<TextLayoutResult>()
                                    assertTrue(requireNotNull(label.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts))
                                    assertEquals(16.sp, layouts.single().layoutInput.style.fontSize, "Original default bodyLarge page labels")
                                    val layout = layouts.single()
                                    assertEquals(1, layout.lineCount)
                                    assertFalse(layout.isLineEllipsized(0))
                                    assertFalse(layout.didOverflowHeight, "Page labels must retain their full line height at font scale$font")
                                    // Skiko reports width overflow when fractional intrinsic text width
                                    // exceeds its rounded integer size (even '1' at 10x24). Check the
                                    // actual available width, retaining exact keylines and typography.
                                    assertTrue(layout.multiParagraph.width <= layout.layoutInput.constraints.maxWidth, "Page labels must fit the available row at font scale$font")
                                }
                                click(current.boundsInRoot.center)
                                assertEquals(1, jumps, "The actual current-page label must retain page jumping")
                                enabled = false
                                render()
                                click(text("1").boundsInRoot.center)
                                assertEquals(1, jumps, "Pending page resolution must disable the jump target")
                            } finally {
                                scene.close()
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun hiddenIndicatorAndSeekbarKeepTheFullCanvasAndAccessibleRevealAndJump() {
        val state = GalleryReaderChromeState()
        var jumps = 0
        val scene = ImageComposeScene(411, 720, Density(1f)) {
            GalleryTheme(false, null) {
                GalleryReaderChrome("Gallery", 0, 61, state, {}, { jumps++ }, {}, showPageNumber = false, showSeekBar = false, background = Color.White, actions = {}) {}
            }
        }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        try {
            repeat(30) { scene.render(it * 16_666_667L).close() }
            Bitmap().use { pixels ->
                pixels.allocN32Pixels(411, 720)
                scene.render(31 * 16_666_667L).use { it.readPixels(pixels) }
                assertEquals(0xffffffff.toInt(), pixels.getColor(205, 710), "Hiding the indicator preserves the selected real canvas background")
            }
            val actions = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
            assertEquals(2, actions.size)
            val canvas = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.single { it.config.getOrNull(SemanticsActions.CustomActions)?.size == 2 }
            assertTrue(canvas.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().isNotEmpty(), "UIKit must expose a named canvas rather than omit an unnamed container's actions")
            assertTrue(requireNotNull(canvas.config[SemanticsActions.OnClick].action).invoke())
            assertTrue(state.visible, "Hiding page numbers must retain an accessible way to reveal controls")
            assertTrue(actions[1].action())
            assertEquals(1, jumps)
            repeat(30) { scene.render((32 + it) * 16_666_667L).close() }
            assertFalse(scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }, "A disabled seekbar must actually leave the rendered controls")
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun hiddenPageIndicatorUsesOutlinedTextAtTheImageEdgeAndStillRevealsControls() {
        val state = GalleryReaderChromeState()
        var reveal = ""
        val scene = ImageComposeScene(411, 720, Density(1f)) {
            GalleryTheme(false, null) {
                reveal = galleryString(Res.string.gallery_reader_show_controls)
                GalleryReaderChrome("Gallery", 0, 61, state, {}, {}, {}, actions = {}) {
                    Box(Modifier.fillMaxSize().background(Color.White))
                }
            }
        }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        try {
            repeat(30) { scene.render(it * 16_666_667L).close() }
            val indicator = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(reveal) == true }
            assertEquals(720f, indicator.boundsInRoot.bottom, "Original indicator sits against the bottom inset, without button padding")
            assertTrue(indicator.boundsInRoot.height < 32, "The original text indicator must not reserve a button-height blank strip")
            Bitmap().use { pixels ->
                pixels.allocN32Pixels(411, 720)
                scene.render(31 * 16_666_667L).use { it.readPixels(pixels) }
                assertEquals(0xffffffff.toInt(), pixels.getColor(205, 688), "The image above the text remains unobscured")
                var outlined = 0
                for (y in 696 until 720) {
                    for (x in 160 until 251) {
                        val color = pixels.getColor(x, y)
                        val red = color shr 16 and 255
                        val green = color shr 8 and 255
                        val blue = color and 255
                        if (red in 45..60 && red == green && green == blue) outlined++
                    }
                }
                assertTrue(outlined > 0, "Actual glyph pixels must include the original dark outline on a bright image")
            }
            scene.sendPointerEvent(PointerEventType.Press, indicator.boundsInRoot.center)
            scene.render(32 * 16_666_667L).close()
            scene.sendPointerEvent(PointerEventType.Release, indicator.boundsInRoot.center)
            scene.render(33 * 16_666_667L).close()
            assertTrue(state.visible, "The real pointer action still reveals the existing reader controls")
            repeat(30) { scene.render((34 + it) * 16_666_667L).close() }
            val hide = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }.single()
            assertTrue(hide.action(), "The original title bar retains an accessible hide action without an extra visual icon")
            assertFalse(state.visible)
        } finally {
            scene.close()
        }
    }

    @Test
    fun seekPillUsesOriginalRoundedKeylinesAndSeparatesTheBottomBarFromTheCanvas() {
        for (width in listOf(411, 1280)) {
            for (dark in listOf(false, true)) {
                val state = GalleryReaderChromeState().apply { visible = true }
                var action = Rect.Zero
                var actionBar = Rect.Zero
                var expectedContainer = 0
                val scene = ImageComposeScene(width, 720, Density(1f)) {
                    GalleryTheme(dark, null) {
                        expectedContainer = BottomAppBarDefaults.containerColor.copy(alpha = if (dark) 0.9f else 0.95f).compositeOver(Color.Black).toArgb()
                        GalleryReaderChrome("Gallery", 0, 61, state, {}, {}, {}, actions = {
                            Box(
                                Modifier.onGloballyPositioned {
                                    action = it.boundsInRoot()
                                    actionBar = requireNotNull(it.parentLayoutCoordinates).boundsInRoot()
                                },
                            ) { GalleryReaderAction("Action", GalleryIcons.LibraryBooks) {} }
                        }) {}
                    }
                }
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                try {
                    repeat(30) { scene.render(it * 16_666_667L).close() }
                    val slider = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.single { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.boundsInRoot
                    val keyline = if (width == 411) 16 else 24
                    assertEquals(64f, actionBar.height, "Original FlexibleBottomAppBar content height; system insets are outside it")
                    Bitmap().use { pixels ->
                        pixels.allocN32Pixels(width, 720)
                        scene.render(31 * 16_666_667L).use { it.readPixels(pixels) }
                        assertEquals(0xff000000.toInt(), pixels.getColor(1, slider.center.y.toInt()), "Image remains visible outside the pill at $width/$dark")
                        assertEquals(0xff000000.toInt(), pixels.getColor(keyline + 1, slider.top.toInt() + 1), "Original rounded corner must reveal the image")
                        val actualContainer = pixels.getColor(keyline + 6, slider.center.y.toInt())
                        for (shift in listOf(0, 8, 16)) {
                            // Allow only one 8-bit compositing rounding unit; geometry remains exact.
                            assertTrue(abs((actualContainer shr shift and 255) - (expectedContainer shr shift and 255)) <= 1, "Overlay opacity must follow the selected app theme at $width/$dark")
                        }
                        assertEquals(0xff000000.toInt(), pixels.getColor(width / 2, actionBar.top.toInt() - 4), "The original 8dp gap reveals the unchanged image canvas")
                        assertTrue(pixels.getColor(1, action.center.y.toInt()) != 0xff000000.toInt(), "Only the separate bottom bar spans the window")
                    }
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun aRealPointerDragSeeksTheLastAndFirstIntegerPages() {
        for ((density, pointer) in listOf(1f to PointerType.Mouse, 1f to PointerType.Touch, 2.625f to PointerType.Mouse, 2.625f to PointerType.Touch)) {
            var page by mutableIntStateOf(1)
            var enabled by mutableStateOf(true)
            var sought = -1
            val requests = mutableListOf<Int>()
            val chrome = GalleryReaderChromeState().apply { visible = true }
            val scene = ImageComposeScene((411 * density).toInt(), (720 * density).toInt(), Density(density)) {
                MaterialTheme {
                    GalleryReaderChrome("Gallery", page, 3, chrome, {}, {}, {
                        sought = it
                        requests += it
                        enabled = false // Real token/page resolution completes after the gesture.
                    }, seekEnabled = enabled, actions = {}) {}
                }
            }
            var frame = 0L
            fun render() {
                scene.render(frame).close()
                frame += 16_666_667
            }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun bounds() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.single { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.boundsInRoot
            fun drag(from: Offset, to: Offset) {
                scene.sendPointerEvent(PointerEventType.Press, from, timeMillis = frame / 1_000_000, type = pointer)
                render()
                for (step in 1..20) {
                    scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (step / 20f), timeMillis = frame / 1_000_000, type = pointer)
                    render()
                }
                scene.sendPointerEvent(PointerEventType.Release, to, timeMillis = frame / 1_000_000, type = pointer)
                repeat(30) { render() }
            }
            try {
                repeat(30) { render() }
                val slider = bounds()
                val inset = (16 * density).coerceAtMost(slider.width / 4)
                val right = Offset(slider.right - inset, slider.center.y)
                val left = Offset(slider.left + inset, slider.center.y)
                drag(slider.center, right)
                assertEquals(2, sought, "Actual pointer drag must reach page3 at density$density; slider=$slider")
                assertEquals(listOf(2), requests, "Pending resolution must not commit the old page again")
                enabled = true // A failed resolution retains the actual page and resets the thumb.
                repeat(3) { render() }
                val progress = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.single { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.config[SemanticsProperties.ProgressBarRangeInfo]
                assertEquals(0f..2f, progress.range, "Three actual pages must retain the explicit page range")
                assertEquals(1f, progress.current, "A failed seek must restore the decoded page rather than leave a requested position")
                drag(slider.center, right)
                assertEquals(listOf(2, 2), requests, "The same real page must remain retryable after failure")
                page = sought
                enabled = true
                repeat(3) { render() }
                drag(right, left)
                assertEquals(0, sought, "Actual pointer drag must return to page1 at density$density")
                assertEquals(listOf(2, 2, 0), requests)
            } finally {
                scene.close()
            }
        }
    }

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun focusedReaderButtonsAndSliderOwnTheirKeysBeforeCanvasShortcuts() {
        for (pages in listOf(11, 12_345, 1_000_000, Int.MAX_VALUE)) {
            for (rtl in listOf(false, true)) {
                val canvasFocus = FocusRequester()
                val buttonFocus = FocusRequester()
                var buttonClicks = 0
                var canvasKeys = 0
                val initial = if (pages > 100_000) pages - 3 else 5
                var page by mutableIntStateOf(initial)
                var sought = -1
                val chrome = GalleryReaderChromeState().apply { visible = true }
                val scene = ImageComposeScene(800, 600, Density(1f)) {
                    MaterialTheme {
                        GalleryReaderChrome("Gallery", page, pages, chrome, {}, {}, {
                            sought = it
                            page = it
                        }, modifier = Modifier.focusRequester(canvasFocus), rtl = rtl, onKey = { event ->
                            if (event.type == KeyEventType.KeyDown && event.key in setOf(Key.Spacebar, Key.DirectionRight, Key.DirectionLeft)) {
                                canvasKeys++
                                true
                            } else {
                                false
                            }
                        }, actions = {
                            IconButton(onClick = { buttonClicks++ }, modifier = Modifier.focusRequester(buttonFocus)) {}
                        }) {}
                    }
                }
                var frame = 0L
                fun render() = repeat(3) {
                    scene.render(frame).close()
                    frame += 16_666_667
                }
                fun press(key: Key, shift: Boolean = false) {
                    scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
                    scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
                    render()
                }
                try {
                    repeat(10) { render() }
                    buttonFocus.requestFocus()
                    press(Key.Spacebar)
                    assertEquals(1, buttonClicks, "Space must activate the focused real Material button")
                    assertEquals(0, canvasKeys, "The canvas must not steal a focused control's key")
                    press(Key.Tab, shift = true) // Previous focus target is the actual page slider.
                    press(Key.DirectionRight)
                    assertEquals(initial + if (rtl) -1 else 1, sought, "A single slider arrow must seek exactly one page at $pages/RTL=$rtl")
                    assertEquals(0, canvasKeys)
                    press(Key.DirectionLeft)
                    assertEquals(initial, sought)
                    assertEquals(0, canvasKeys)
                    canvasFocus.requestFocus()
                    press(Key.DirectionRight)
                    assertEquals(1, canvasKeys, "The unfocused canvas retains its reader shortcuts")
                    assertEquals(initial, sought)
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun overlayControlsLeaveTheCanvasUnchangedAndReachableAtLargeFontSizes() {
        for (width in listOf(360, 411, 1280)) {
            for (font in listOf(1f, 1.8f)) {
                val state = GalleryReaderChromeState()
                var canvas = Rect.Zero
                val buttons = mutableMapOf<Int, Rect>()
                val scene = ImageComposeScene(width, 720, Density(1f, font)) {
                    MaterialTheme {
                        GalleryReaderChrome("A long gallery title ".repeat(12), 12344, 12345, state, {}, {}, {}, actions = {
                            repeat(4) { index ->
                                Box(Modifier.onGloballyPositioned { buttons[index] = it.boundsInRoot() }) { GalleryReaderAction("Action $index", GalleryIcons.LibraryBooks) {} }
                            }
                        }) { Box(Modifier.fillMaxSize().onGloballyPositioned { canvas = it.boundsInRoot() }) }
                    }
                }
                try {
                    repeat(3) { scene.render(it * 16_000_000L).close() }
                    assertEquals(Rect(0f, 0f, width.toFloat(), 720f), canvas, "Hidden controls at $width/$font")
                    val image = scene.render(500_000_000)
                    try {
                        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(image.width, image.height, ColorAlphaType.PREMUL)) }
                        try {
                            image.readPixels(bitmap)
                            assertEquals(0xff000000.toInt(), bitmap.getColor(width / 2, 360), "Default reading background")
                        } finally {
                            bitmap.close()
                        }
                    } finally {
                        image.close()
                    }
                    state.visible = true
                    // Advance real animation frames through the original 200ms slide transition.
                    repeat(30) { scene.render(1_000_000_000 + it * 16_666_667L).close() }
                    assertEquals(Rect(0f, 0f, width.toFloat(), 720f), canvas, "Showing controls must not resize images at $width/$font")
                    assertEquals(4, buttons.size)
                    for ((_, bounds) in buttons) assertTrue(bounds.width >= 48 && bounds.height >= 48 && bounds.left >= 0 && bounds.right <= width && bounds.bottom <= 720, "Clipped action at $width/$font: $bounds")
                    for (index in 0..2) assertFalse(buttons.getValue(index).overlaps(buttons.getValue(index + 1)), "Overlapping actions at $width/$font")
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun enteringTheReaderDropsTheRealLibraryContentWidthCapAndRestoresItOnExit() {
        var reading by mutableStateOf(false)
        var width = 0
        val chrome = GalleryReaderChromeState()
        val scene = ImageComposeScene(1280, 720, Density(1f)) {
            MaterialTheme {
                GalleryNavigationShell(GalleryDestination.entries, GalleryDestination.GALLERIES, {}) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Column(galleryPageModifier(searchBarPage = false, galleryIsImmersive()).onGloballyPositioned { width = it.size.width }) {
                            if (reading) {
                                GalleryImmersive()
                                GalleryReaderChrome("Gallery", 0, 1, chrome, {}, {}, {}, actions = {}) {}
                            }
                        }
                    }
                }
            }
        }
        try {
            repeat(3) { scene.render(it * 16_000_000L).close() }
            val original = width
            assertTrue(original in 1..1040)
            reading = true
            repeat(4) { scene.render(100_000_000 + it * 16_000_000L).close() }
            assertEquals(1280, width)
            reading = false
            repeat(4) { scene.render(200_000_000 + it * 16_000_000L).close() }
            assertEquals(original, width)
        } finally {
            scene.close()
        }
    }
}
