package com.ehviewer.core.data

import okio.Path

/** Enumerate incrementally on each platform, rejecting the limit before collecting another name. */
internal expect suspend fun directoryNames(directory: Path, maximum: Int): List<String>

/** Numeric runs have unbounded precision; ASCII folding and a final lexical tie make order stable. */
internal fun compareDirectoryNames(first: String, second: String): Int {
    var a = 0
    var b = 0
    while (a < first.length && b < second.length) {
        if (first[a] in '0'..'9' && second[b] in '0'..'9') {
            val startA = a
            val startB = b
            while (a < first.length && first[a] in '0'..'9') a++
            while (b < second.length && second[b] in '0'..'9') b++
            val left = first.substring(startA, a).trimStart('0')
            val right = second.substring(startB, b).trimStart('0')
            val numbers = left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
            if (numbers != 0) return numbers
        } else {
            fun folded(char: Char) = if (char in 'A'..'Z') char + ('a' - 'A') else char
            val letters = folded(first[a]).compareTo(folded(second[b]))
            if (letters != 0) return letters
            a++
            b++
        }
    }
    return (first.length - a).compareTo(second.length - b).takeIf { it != 0 } ?: first.compareTo(second)
}
