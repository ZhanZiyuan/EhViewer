package com.hippo.ehviewer

// Schema 23 uses the default FTS4 tokenizer: ASCII punctuation separates terms, non-ASCII does not.
private val ftsSeparators = Regex("[\\x00-\\x2f\\x3a-\\x40\\x5b-\\x60\\x7b-\\x7f]+")

internal fun gallerySearchExpression(keyword: String): String {
    val terms = keyword.split(ftsSeparators).filter(String::isNotEmpty)
    if (terms.isEmpty()) return "\"\""
    // Each earlier term is exact and the last term is a prefix, as the user types.
    return terms.mapIndexed { index, term ->
        if (index == terms.lastIndex) "\"$term*\"" else "\"$term\""
    }.joinToString(" ")
}
