package com.ehviewer.shared

import com.ehviewer.core.network.session.GalleryAccountDecoder
import com.ehviewer.core.network.session.GalleryAccountProfile

/** Explicit offline native-bridge regression; normal account requests use real responses. */
fun verifyAccountProduct(decoder: GalleryAccountDecoder): Int {
    val html = """<!DOCTYPE html><html><body>
        <div id="userlinks"><a href="https://forums.e-hentai.org/index.php?showuser=123">Profile</a></div>
        <div class="row1"><div>Reader &amp; Writer</div><div><img src="/avatar.png"></div></div>
        </body></html>""".encodeToByteArray()
    check(decoder.decodeProfileLink(html) == "https://forums.e-hentai.org/index.php?showuser=123")
    check(decoder.decodeProfileLink("<!DOCTYPE html><html><body>Guest</body></html>".encodeToByteArray()) == null)
    check(decoder.decodeProfile(html) == GalleryAccountProfile("Reader & Writer", "https://forums.e-hentai.org/avatar.png"))
    val categories = List(10) { "Shelf $it" }
    val config = "<!DOCTYPE html><html><body>" + categories.mapIndexed { index, name ->
        """<input type="text" name="favorite_$index" value="$name">"""
    }.joinToString("") + "</body></html>"
    check(decoder.decodeUserConfig(config.encodeToByteArray()) == categories)
    return 3
}
