package com.ehviewer.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ehviewer.core.model.GalleryTagGroup
import com.ehviewer.core.model.PowerStatus
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_search

/** v1.15.10 GalleryTags' namespace and wrapping pills, shared across all product surfaces. */
@Composable
internal fun GalleryDetailTagGroup(group: GalleryTagGroup, onSearch: (String) -> Unit, modifier: Modifier = Modifier) {
    val search = galleryString(Res.string.gallery_search)
    Row(modifier.fillMaxWidth()) {
        GalleryTagPill(group.namespace.value, isGroup = true)
        FlowRow(Modifier.weight(1f)) {
            group.tags.forEach { tag ->
                val name = "${group.namespace.value}:${tag.text}"
                GalleryTagPill(tag.text, weak = tag.power == PowerStatus.Weak, modifier = Modifier.clickable(role = Role.Button, onClickLabel = search) { onSearch(name) }.semantics { contentDescription = name })
            }
        }
    }
}

@Composable
private fun GalleryTagPill(text: String, modifier: Modifier = Modifier, weak: Boolean = false, isGroup: Boolean = false) {
    Surface(Modifier.padding(4.dp), color = if (isGroup) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(64.dp)) {
        Text(text, modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(IntrinsicSize.Max), color = LocalContentColor.current.let { if (weak) it.copy(alpha = 0.5f) else it }, style = MaterialTheme.typography.labelLarge)
    }
}
