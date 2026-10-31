package com.hippo.ehviewer.desktop

import com.ehviewer.core.network.gallery.RustGalleryDecoder
import com.ehviewer.shared.verifyAccountProduct
import com.ehviewer.shared.verifyGalleryProduct
import java.nio.file.Path

/** Explicit offline regression entry point using the production decoder and actual platform pixels. */
fun main(arguments: Array<String>) {
    require(arguments.size == 1)
    val library = Path.of(arguments.single()).toRealPath()
    val decoder = if (System.getProperty("os.name").startsWith("Windows")) {
        WindowsGalleryDecoder(library)
    } else {
        System.load(library.toString())
        RustGalleryDecoder
    }
    check(verifyGalleryProduct(decoder) == 3)
    check(verifyAccountProduct(decoder) == 3)
    check(verifyDesktopHistory() == 3)
    println("Gallery product regression passed: 3 checks")
    println("Account product regression passed: 3 checks")
    println("History product regression passed: 3 checks")
}
