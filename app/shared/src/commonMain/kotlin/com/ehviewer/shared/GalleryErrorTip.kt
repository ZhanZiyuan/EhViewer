package com.ehviewer.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** GalleryList's ErrorTip: a centered 120dp sad Android in the tertiary color above the message. */
@Composable
internal fun GalleryErrorTip(text: String, modifier: Modifier = Modifier) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(GalleryIcons.SadAndroid, contentDescription = null, modifier = Modifier.padding(16.dp).size(120.dp), tint = MaterialTheme.colorScheme.tertiary)
            Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
    }
}
