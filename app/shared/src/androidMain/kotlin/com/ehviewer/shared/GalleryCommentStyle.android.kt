package com.ehviewer.shared

import androidx.compose.ui.text.PlatformTextStyle

// Match the original AppCompat comment widget, including the first/last-line font padding.
internal actual fun platformCommentTextStyle(): PlatformTextStyle? = PlatformTextStyle(includeFontPadding = true)
