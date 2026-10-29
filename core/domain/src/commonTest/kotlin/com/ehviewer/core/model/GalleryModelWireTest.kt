@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ehviewer.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor

class GalleryModelWireTest {
    // Captured from the original unchanged Android models before the module move.
    private val baseWire = "bf63676964182a65746f6b656e6766697874757265657469746c65714d616e676120f09f939a2043616665cc81687469746c654a706e69e3839ee383b3e382ac6863617465676f72790266726174696e67fa409000006a73696d706c65546167739f706c616e67756167653a6368696e657365ff6570616765730cff"
    private val previewsWire = "9f9f9f625631bf6375726c781b68747470733a2f2f6578616d706c652e696e76616c69642f6f6e6568706f736974696f6e016670546f6b656e6b666978747572652d6f6e65ffff9f625632bf6375726c781b68747470733a2f2f6578616d706c652e696e76616c69642f74776f68706f736974696f6e026670546f6b656e6b666978747572652d74776f676f6666736574580769636c6970576964746818646a636c697048656967687418c8ffffff11ff"
    private fun base() = BaseGalleryInfo(
        gid = 42L,
        token = "fixture",
        title = "Manga 📚 Cafe\u0301",
        titleJpn = "マンガ",
        category = 2,
        rating = 4.5f,
        pages = 12,
        simpleTags = listOf("language:chinese"),
    )

    private fun previews(): List<GalleryPreview> = listOf(
        V1GalleryPreview("https://example.invalid/one", 1, "fixture-one"),
        V2GalleryPreview("https://example.invalid/two", 2, "fixture-two", 7, 100, 200),
    )

    @Test fun baseMetadataRetainsExistingWireBytesAndUnicode() {
        val original = base()
        val bytes = Cbor.encodeToByteArray(BaseGalleryInfo.serializer(), original)
        assertEquals(baseWire, bytes.toHexString())
        val decoded = Cbor.decodeFromByteArray(BaseGalleryInfo.serializer(), baseWire.hexToByteArray())
        assertEquals(original, decoded)
        assertEquals("Manga 📚 Cafe\u0301", decoded.title)
    }

    @Test fun previewArrayAndBothVariantsRetainExistingWireBytes() {
        val original = GalleryPreviewList(previews(), 17)
        val bytes = Cbor.encodeToByteArray(GalleryPreviewList.serializer(), original)
        assertEquals(previewsWire, bytes.toHexString())
        val decoded = Cbor.decodeFromByteArray(GalleryPreviewList.serializer(), previewsWire.hexToByteArray())
        assertEquals(original.previews, decoded.previews)
        assertEquals(17, decoded.total)
    }

    @Test fun nestedDetailsTagsCommentsAndFavoritesRoundTrip() {
        val gallery = base().apply {
            favoriteSlot = GalleryInfo.LOCAL_FAVORITED
            favoriteName = "fixture"
            favoriteNote = "note"
        }
        val original = GalleryDetail(
            galleryInfo = gallery,
            tagGroups = listOf(GalleryTagGroup(TagNamespace.Female, listOf(GalleryTag("fixture", PowerStatus.Solid, VoteStatus.Up)))),
            comments = GalleryCommentList(
                listOf(GalleryComment(9L, 5, false, true, false, true, false, false, null, 1_700_000_000L, null, "<p>fixture 📚</p>", 0)),
                false,
            ),
            previewList = previews(),
        )
        val decoded = Cbor.decodeFromByteArray(GalleryDetail.serializer(), Cbor.encodeToByteArray(GalleryDetail.serializer(), original))
        assertEquals(original, decoded)
        assertEquals(GalleryInfo.LOCAL_FAVORITED, decoded.favoriteSlot)
        assertEquals("fixture", decoded.favoriteName)
        assertEquals(TagNamespace.Female, decoded.tagGroups.single().namespace)
        assertEquals("<p>fixture 📚</p>", decoded.comments.comments.single().comment)
    }

    @Test fun languageTagsAndTitleFallbackKeepExistingPrecedence() {
        for ((tag, expected) in GalleryInfo.S_LANG_TAGS.zip(GalleryInfo.S_LANGS)) {
            val gallery = BaseGalleryInfo(title = "[English] fixture", simpleTags = listOf(tag))
            gallery.generateSLang()
            assertEquals(expected, gallery.simpleLanguage)
        }
        val fallback = BaseGalleryInfo(title = "[Español] fixture", simpleTags = listOf("fixture:unknown"))
        fallback.generateSLang()
        assertEquals("ES", fallback.simpleLanguage)
        val absent = BaseGalleryInfo()
        absent.generateSLang()
        assertNull(absent.simpleLanguage)
    }

    @Test fun unknownNamespaceStillFailsInsteadOfBecomingAValidTag() {
        val bytes = Cbor.encodeToByteArray(String.serializer(), "fixture_unknown_namespace")
        assertFailsWith<SerializationException> { Cbor.decodeFromByteArray(TagNamespaceSerializer, bytes) }
    }
}
