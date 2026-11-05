package com.ehviewer.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GalleryCommentLinkTest {
    @Test fun inlineImagesKeepOriginalPhysicalBoundsWithoutUnboundedLayoutAllocations() {
        assertEquals(200 to 300, commentImageDimensions("https://ehgt.org/owned.png"))
        assertEquals(200 to 100, commentImageDimensions("https://ehgt.org/abc-def-800-400-note_200.jpg"))
        assertEquals(200 to 300, commentImageDimensions("https://ehgt.org/abc-def-0-400-note_200.jpg"))
        assertEquals(200 to 300, commentImageDimensions("https://ehgt.org/abc-def-800-400-note_999999.jpg"))
    }

    @Test fun commentLinksRetainWebEscapesAndResolveServiceRelativeReferences() {
        assertEquals("https://e-hentai.org/s/1111111111/1-2", commentWebLink("/s/1111111111/1-2", false, 1, "0123456789"))
        assertEquals("https://exhentai.org/g/1/0123456789/?p=2", commentWebLink("?p=2", true, 1, "0123456789"))
        assertEquals("https://ehgt.org/sample%20image.png?x=1&y=2", commentWebLink("//ehgt.org/sample%20image.png?x=1&y=2", false, 1, "0123456789"))
        assertEquals("http://example.org/public", commentWebLink("http://example.org/public", false, 1, "0123456789"))
    }

    @Test fun anchorsAndExecutableOrCredentialBearingUrlsNeverReachAPlatformOpener() {
        for (value in listOf("#c12", "javascript:alert(1)", "file:///private/owned", "intent://app", "data:text/html,owned", "https://member:secret@example.org/", "https://example.org/\nowned", "x".repeat(8193))) {
            assertNull(commentWebLink(value, false, 1, "0123456789"), value)
        }
    }
}
