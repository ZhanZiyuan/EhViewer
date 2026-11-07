package com.ehviewer.shared

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryTag
import com.ehviewer.core.model.GalleryTagGroup
import com.ehviewer.core.model.PowerStatus
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.model.VoteStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GallerySimilarSearchTest {
    private fun detail(title: String?, tags: List<GalleryTagGroup> = emptyList(), uploader: String? = null) = GalleryDetail(
        galleryInfo = BaseGalleryInfo(title = title, uploader = uploader),
        tagGroups = tags,
        comments = GalleryCommentList(emptyList(), false),
        previewList = emptyList(),
    )

    @Test fun decoratedAndTranslatedTitlesUseTheOriginalQuotedTitleSearch() {
        assertEquals(GallerySimilarSearch("\"Alice in Wonderland\""), similarGallerySearch(detail("(Event) [Artist] {Release} ~Extra~ Alice in Wonderland ch. 1-23 [English]")))
        assertEquals(GallerySimilarSearch("\"Romaji title\""), similarGallerySearch(detail("Romaji title | English title")))
        assertEquals(GallerySimilarSearch("\"Kelly's English - Alice in Wonderland\""), similarGallerySearch(detail("Kelly's English - Alice in Wonderland (Chinese Release Ver., Comics Only)")))
    }

    @Test fun missingTitleUsesAnExactArtistRouteBeforeTheUploaderFallback() {
        for (namespace in listOf(TagNamespace.Artist, TagNamespace.Cosplayer)) {
            val literal = "日本語 / quoted\" ?x=1#"
            val tags = listOf(GalleryTagGroup(namespace, listOf(GalleryTag(literal, PowerStatus.Weak, VoteStatus.None))))
            val tag = "${namespace.value}:$literal"
            assertEquals(GallerySimilarSearch(tag, tag = tag), similarGallerySearch(detail(" [Release only] ", tags, "Other uploader")))
        }
        val literalUploader = "Name / 日本語 ?x=1#"
        assertEquals(GallerySimilarSearch("uploader:$literalUploader", uploader = literalUploader), similarGallerySearch(detail(null, uploader = literalUploader)))
    }

    @Test fun noUsableIdentityDoesNotExposeAnEmptyOrInvalidSearchAction() {
        assertNull(similarGallerySearch(detail(null)))
        assertNull(similarGallerySearch(detail("[Only a release label]", uploader = "(Disowned)")))
        assertNull(similarGallerySearch(detail("x".repeat(4095))))
    }
}
