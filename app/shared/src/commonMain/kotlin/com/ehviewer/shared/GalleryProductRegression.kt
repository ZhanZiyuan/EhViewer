package com.ehviewer.shared

import androidx.compose.ui.graphics.toPixelMap
import com.ehviewer.core.network.gallery.GalleryDocumentDecoder
import com.ehviewer.core.network.gallery.GalleryQueryError
import kotlin.io.encoding.Base64

/** Offline assertions invoked only by explicit packaged-app regression modes, never normal browsing. */
fun verifyGalleryProduct(decoder: GalleryDocumentDecoder): Int {
    val queryWarning = decoder.decode("""<!DOCTYPE html><html><body><div class="searchwarn"><p>You provided too many keywords. Use fewer keywords, or combine keywords with quotes.</p></div></body></html>""".encodeToByteArray())
    check(queryWarning.queryError == GalleryQueryError.TOO_MANY_KEYWORDS && queryWarning.galleryInfoList.isEmpty() && queryWarning.prev == null && queryWarning.next == null)
    val detail = decoder.decodeDetails(
        """<!DOCTYPE html><html><body>
        <script>var gid = 1; var token = "0123456789"; var apiuid = -1; var apikey = "0000000000"; var average_rating = 4.5;</script>
        <div id="gd5"><a onclick="one()">Archive</a><a onclick="two()">Torrent (0)</a></div>
        <div id="gd1"><div style="background:url(https://ehgt.org/fixture.jpg)"></div></div>
        <h1 id="gn">Shared &amp; tested</h1><h1 id="gj">日本語</h1><div id="gdc">Manga</div>
        <div id="gdd"><table><tr><td>Length:</td><td class="gdt2">2 pages</td></tr><tr><td>File Size:</td><td>1 MB</td></tr></table></div>
        <span id="rating_count">3</span><div id="fav"></div><div id="taglist"><table></table></div>
        <div id="cdiv"><div id="chd"><a id="postnewcomment">Comment</a></div>
        <div class="c1"><div class="c3">Posted on 05 November 2026, 10:00 by: <a>Owned reader</a></div><div class="c6" id="comment_12"><b>Shared &amp; tested</b><br><a href="#c12">日本語 📚</a></div></div></div>
        <div id="gdt"><a href="https://e-hentai.org/s/1111111111/1-1"><div title="Page 1" style="width:80px;height:100px;background:url(https://ehgt.org/one.jpg)"></div></a>
        <a href="https://e-hentai.org/s/2222222222/1-2"><div title="Page 2" style="width:80px;height:100px;background:url(https://ehgt.org/two.jpg)"></div></a></div>
        </body></html>""".encodeToByteArray(),
    )
    check(detail.gid == 1L && detail.token == "0123456789" && detail.title == "Shared & tested" && detail.titleJpn == "日本語" && detail.pages == 2 && detail.size == "1 MB")
    check(detail.previewList.map { it.position } == listOf(0, 1) && detail.previewList[1].pToken == "2222222222")
    val comment = detail.comments.comments.single()
    check(comment.id == 12L && comment.user == "Owned reader" && comment.body.any { it.bold && it.text == "Shared & tested" } && comment.body.any { it.link == "#c12" && it.text == "日本語 📚" })
    val page = decoder.decodePage("""<!DOCTYPE html><html><body><img id="img" src="https://ehgt.org/fixture%20image.png?a=1&amp;b=2"><a id="next" href="https://e-hentai.org/s/2222222222/1-2">Next</a></body></html>""".encodeToByteArray())
    check(page.imageUrl == "https://ehgt.org/fixture%20image.png?a=1&b=2" && page.nextPageUrl == "https://e-hentai.org/s/2222222222/1-2" && page.previousPageUrl == null)
    // Supplement the existing gallery-document group through every installed JNI/C ABI/FFM adapter.
    val torrents = decoder.decodeTorrents(
        """<!DOCTYPE html><html><div id="torrentinfo"><table><tr><td><span style="color:red">2026-11-05 12:34</span></td>
        <td><span>Size:</span> 63.14 MiB</td><td><span>Seeds:</span> 12</td><td><span>Peers:</span> 3</td>
        <td><span>Downloads:</span> 42</td><td><span>Uploader:</span> Reader</td>
        <td><a onclick="document.location='https://ehtracker.org/get/1/0123456789abcdef0123456789abcdef01234567.torrent'">Alice &amp; friends.zip</a></td></tr></table></div></html>""".encodeToByteArray(),
    )
    check(torrents.size == 1 && torrents[0].name == "Alice & friends.zip" && torrents[0].outdated && torrents[0].seeds == 12 && torrents[0].peers == 3 && torrents[0].downloads == 42)
    check(torrents[0].magnet(1).startsWith("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn="))
    check(decoder.decodeTorrents("""<!DOCTYPE html><html><div id="torrentinfo">There are no torrents for this gallery.</div></html>""".encodeToByteArray()).isEmpty())
    // Generated two-pixel RGBA PNG, with correct chunk CRCs; no remote image or user data.
    val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8AAQv8BD/kD/YURmXYAAAAASUVORK5CYII=")
    val image = decodePlatformImage(png)
    check(image.width == 2 && image.height == 1)
    val pixels = image.toPixelMap()
    check(pixels[0, 0].red > 0.99f && pixels[0, 0].blue < 0.01f && pixels[1, 0].blue > 0.99f && pixels[1, 0].red < 0.01f)
    return 3
}
