package com.ehviewer.shared

import com.ehviewer.core.domain.GalleryHistoryStore
import com.ehviewer.core.model.BaseGalleryInfo

/** Explicit owned runtime regressions only; normal browsing always records actual gallery metadata. */
suspend fun verifyHistoryProduct(store: GalleryHistoryStore): Int {
    val gid = 8_000_000_000_042L
    check(store.recent().none { it.gid == gid })
    store.record(BaseGalleryInfo(gid = gid, token = "0123456789", title = "Owned history 日本語", pages = 3))
    check(store.recent().single { it.gid == gid }.let { it.title == "Owned history 日本語" && it.pages == 3 })
    store.record(BaseGalleryInfo(gid = gid, token = "0123456789", title = "Updated owned history", pages = 4))
    check(store.recent().filter { it.gid == gid }.single().let { it.title == "Updated owned history" && it.pages == 4 })
    store.forget(gid)
    check(store.recent().none { it.gid == gid })
    return 3
}
