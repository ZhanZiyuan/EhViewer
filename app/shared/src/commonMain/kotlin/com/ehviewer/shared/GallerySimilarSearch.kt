package com.ehviewer.shared

import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.TagNamespace

internal data class GallerySimilarSearch(val query: String, val tag: String? = null, val uploader: String? = null)

// Original EhUtils title extraction: remove release/artist decorations and English suffixes.
private val similarTitlePrefix = Regex("""^(?:\([^)]*\)|\[[^]]*]|\{[^}]*\}|~[^~]*~|\s+)*""")
private val similarTitleSuffix = Regex("""(?:\s+ch.[\s\d-]+)?(?:\([^)]*\)|\[[^]]*]|\{[^}]*\}|~[^~]*~|\s+)*$""", RegexOption.IGNORE_CASE)

/** Preserve Android's title → artist/cosplayer tag → uploader fallback and typed routes. */
internal fun similarGallerySearch(detail: GalleryDetail): GallerySimilarSearch? {
    val title = detail.title?.let { similarTitleSuffix.replaceFirst(similarTitlePrefix.replaceFirst(it, ""), "").substringBeforeLast('|').trim() }.orEmpty()
    if (title.isNotEmpty() && title.length <= 4094) return GallerySimilarSearch("\"$title\"")
    val artist = detail.tagGroups.firstOrNull { it.namespace == TagNamespace.Artist || it.namespace == TagNamespace.Cosplayer }
    val tag = artist?.tags?.firstOrNull()?.text?.takeIf(String::isNotBlank)?.let { "${artist.namespace.value}:$it" }
    if (tag != null) return GallerySimilarSearch(tag, tag = tag)
    val uploader = detail.uploader?.takeIf { it.isNotBlank() && it != "(Disowned)" && !detail.disowned }
    return uploader?.let { GallerySimilarSearch("uploader:$it", uploader = it) }
}
