package com.hippo.ehviewer.spider

import okio.Path

/** Owned by SpiderDen's index lock. Retain alternate extensions until their files are removed. */
internal class PageFileIndex {
    private data class Key(val index: Int, val temporary: Boolean)

    private val files = mutableMapOf<Key, LinkedHashSet<Path>>()

    fun refresh(paths: Sequence<Path>) {
        files.clear()
        paths.forEach(::add)
    }

    fun add(path: Path) {
        val key = key(path) ?: return
        files.getOrPut(key) { linkedSetOf() }.add(path)
    }

    fun remove(path: Path) {
        val key = key(path) ?: return
        files[key]?.let {
            it.remove(path)
            if (it.isEmpty()) files.remove(key)
        }
    }

    fun candidates(index: Int, temporary: Boolean) = files[Key(index, temporary)]?.toList().orEmpty()

    private fun key(path: Path): Key? {
        val name = path.name
        if (name.length < 10 || name[8] != '.') return null
        val page = name.take(8).toIntOrNull()?.takeIf { it > 0 && name[0].isDigit() } ?: return null
        return Key(page - 1, name.endsWith(TEMP_SUFFIX))
    }
}
