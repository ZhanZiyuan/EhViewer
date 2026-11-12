package com.hippo.ehviewer

import org.junit.Assert.assertEquals
import org.junit.Test

class FtsSearchTest {
    @Test
    fun ftsOperatorsAndQuotesBecomeLiteralSearchTerms() {
        assertEquals("\"A\" \"OR\" \"B*\"", gallerySearchExpression("A OR \"B("))
        assertEquals("\"needle*\"", gallerySearchExpression("needle ("))
        assertEquals("\"\"", gallerySearchExpression("(\"*)"))
    }

    @Test
    fun preservesNonAsciiTokensAndOnlyPrefixesTheLastTerm() {
        assertEquals("\"漫画📖\" \"ca*\"", gallerySearchExpression("漫画📖 ca"))
    }
}
