package com.ehviewer.shared

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberGalleryLinkSharer(): ((String) -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        { url ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
            context.startActivity(Intent.createChooser(send, null))
        }
    }
}
