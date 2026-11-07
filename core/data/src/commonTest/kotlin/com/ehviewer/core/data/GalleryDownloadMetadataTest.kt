@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ehviewer.core.data

import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadMetadata
import com.ehviewer.core.domain.download.downloadMetadata
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.cbor.Cbor

class GalleryDownloadMetadataTest {
    @Test fun cardSnapshotRetainsPublicInformationWithoutAccountOrFavoriteSecrets() {
        assertNull(BaseGalleryInfo().downloadMetadata())
        val gallery = GalleryDetail(BaseGalleryInfo(1, "0123456789", "Owned book", uploader = "Owned artist", disowned = true, rating = 4.25f, category = 512, favoriteNote = "owned-private-note"), apiUid = 42, apiKey = "owned-private-api-key", tagGroups = emptyList(), comments = GalleryCommentList(emptyList(), false), previewList = emptyList())
        val metadata = requireNotNull(gallery.downloadMetadata())
        metadata.validate()
        val encoded = Cbor.encodeToByteArray(GalleryDownloadMetadata.serializer(), metadata)
        assertTrue(encoded.decodeToString().contains("Owned artist"))
        assertFalse(encoded.decodeToString().contains("owned-private"))
        assertEquals(metadata, Cbor.decodeFromByteArray(GalleryDownloadMetadata.serializer(), encoded))
        for (invalid in listOf(metadata.copy(rating = Float.NaN), metadata.copy(rating = 6f), metadata.copy(uploader = "x".repeat(4097)))) {
            assertFailsWith<IllegalArgumentException> { GalleryDownload(1, "0123456789", "Book", 2, false, metadata = invalid).validate() }
        }
    }
}
