package com.hippo.ehviewer.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** M1 startup probe only; gallery, reader and download features are not yet implemented. */
@Composable
fun StartupContent(onReady: () -> Unit) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text("EhViewer · Kotlin/Native startup")
            }
        }
    }
    SideEffect(onReady)
}
