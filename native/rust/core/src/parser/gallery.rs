//! Portable gallery documents for the shared detail and online-reader flow.
use super::{detail::parse_gallery_detail, utils::unescape};
use anyhow::{Context, Result, ensure};
use serde::Serialize;

const MAX_DOCUMENT: usize = 8 * 1024 * 1024;

fn document(input: &[u8]) -> Result<&str> {
    ensure!(
        !input.is_empty() && input.len() <= MAX_DOCUMENT,
        "Invalid gallery document size"
    );
    std::str::from_utf8(input).context("Invalid gallery document encoding")
}

fn encode(value: impl Serialize) -> Result<Vec<u8>> {
    let bytes = serde_cbor::to_vec(&value)?;
    ensure!(bytes.len() <= MAX_DOCUMENT, "Gallery result size limit");
    Ok(bytes)
}

pub fn parse_detail_cbor(input: &[u8]) -> Result<Vec<u8>> {
    let html = document(input)?;
    let mut dom = tl::parse(html, tl::ParserOptions::default().track_ids())?;
    ensure!(dom.version().is_some(), "Missing gallery document");
    encode(parse_gallery_detail(&mut dom, html)?)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct GalleryImagePage {
    image_url: String,
    skip_hath_key: Option<String>,
    origin_image_url: Option<String>,
    show_key: Option<String>,
    next_page_url: Option<String>,
    previous_page_url: Option<String>,
}

pub fn parse_page_cbor(input: &[u8]) -> Result<Vec<u8>> {
    let html = document(input)?;
    // The pinned parser's closing-tag ID cache omits void elements such as <img>.
    // Its ordinary ID lookup scans all nodes, including the actual reader image.
    let dom = tl::parse(html, tl::ParserOptions::default())?;
    ensure!(dom.version().is_some(), "Missing image page document");
    let parser = dom.parser();
    let image = dom
        .get_element_by_id("img")
        .and_then(|node| node.get(parser)?.as_tag())
        .filter(|tag| tag.name() == "img")
        .context("Missing gallery image")?;
    let attribute = |tag: &tl::HTMLTag, name| -> Option<String> {
        let value = tag.attributes().get(name)??.try_as_utf8_str()?;
        unescape(value).ok().map(|text| text.to_string())
    };
    let image_url = attribute(image, "src")
        .filter(|url| !url.is_empty())
        .context("Missing gallery image URL")?;
    let link = |id| {
        dom.get_element_by_id(id)
            .and_then(|node| node.get(parser)?.as_tag())
            .and_then(|tag| attribute(tag, "href"))
    };
    let original = dom
        .query_selector("a[href]")
        .context("Invalid image page links")?
        .filter_map(|node| node.get(parser)?.as_tag())
        .filter_map(|tag| attribute(tag, "href"))
        .find(|url| url.contains("/fullimg/"));
    encode(GalleryImagePage {
        image_url,
        skip_hath_key: crate::regex!(r"nl\('([^']+)'\)")
            .captures(html)
            .map(|value| value[1].to_string()),
        origin_image_url: original,
        show_key: crate::regex!(r#"var showkey="([0-9a-z]+)";"#)
            .captures(html)
            .map(|value| value[1].to_string()),
        next_page_url: link("next"),
        previous_page_url: link("prev"),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const DETAIL: &[u8] = br#"<!DOCTYPE html><html><body>
        <script>var gid = 1; var token = "0123456789"; var apiuid = -1; var apikey = "0000000000"; var average_rating = 4.5;</script>
        <div id="gd5"><a onclick="one()">Archive</a><a onclick="two()">Torrent (0)</a></div>
        <div id="gd1"><div style="background:url(https://ehgt.org/fixture.jpg)"></div></div>
        <h1 id="gn">Shared &amp; tested</h1><h1 id="gj">Fixture title</h1><div id="gdc">Manga</div>
        <div id="gdd"><table><tr><td>Length:</td><td class="gdt2">2 pages</td></tr><tr><td>File Size:</td><td>1 MB</td></tr></table></div>
        <span id="rating_count">3</span><div id="fav"></div><div id="taglist"><table></table></div>
        <div id="cdiv"><div id="chd"><a id="postnewcomment">Comment</a></div></div>
        <div id="gdt"><a href="https://e-hentai.org/s/1111111111/1-1"><div title="Page 1" style="width:80px;height:100px;background:url(https://ehgt.org/one.jpg)"></div></a>
        <a href="https://e-hentai.org/s/2222222222/1-2"><div title="Page 2" style="width:80px;height:100px;background:url(https://ehgt.org/two.jpg)"></div></a></div>
        </body></html>"#;

    #[test]
    fn portable_details_reuse_the_existing_metadata_and_preview_wire_model() {
        let bytes = parse_detail_cbor(DETAIL).unwrap();
        let actual: serde_json::Value = serde_cbor::from_slice(&bytes).unwrap();
        assert_eq!(actual["galleryInfo"]["gid"], 1);
        assert_eq!(actual["galleryInfo"]["title"], "Shared & tested");
        assert_eq!(actual["galleryInfo"]["pages"], 2);
        assert_eq!(actual["size"], "1 MB");
        assert_eq!(actual["previewList"].as_array().unwrap().len(), 2);
        assert_eq!(actual["previewList"][1][0], "V1");
        assert_eq!(actual["previewList"][1][1]["pToken"], "2222222222");
    }

    #[test]
    fn image_pages_keep_authorized_source_and_navigation_without_decoding_url_escapes() {
        let html = br#"<!DOCTYPE html><html><body><img id="img" src="https://ehgt.org/fixture%20image.png?first=1&amp;second=2" style="width:80px">
            <a id="next" href="https://e-hentai.org/s/2222222222/1-2">Next</a>
            <a href="https://e-hentai.org/fullimg/1/3333333333">Original</a><a onclick="return nl('reload-key')">Reload</a>
            <script>var showkey="abcdef0123";</script></body></html>"#;
        let actual: serde_json::Value =
            serde_cbor::from_slice(&parse_page_cbor(html).unwrap()).unwrap();
        assert_eq!(
            actual["imageUrl"],
            "https://ehgt.org/fixture%20image.png?first=1&second=2"
        );
        assert_eq!(actual["skipHathKey"], "reload-key");
        assert_eq!(actual["showKey"], "abcdef0123");
        assert_eq!(
            actual["nextPageUrl"],
            "https://e-hentai.org/s/2222222222/1-2"
        );
        assert!(actual["previousPageUrl"].is_null());
    }

    #[test]
    fn invalid_or_challenge_documents_never_become_successful_empty_details_or_images() {
        for bytes in [
            &b""[..],
            &b"\xff"[..],
            &b"<!DOCTYPE html><html>challenge</html>"[..],
        ] {
            assert!(parse_detail_cbor(bytes).is_err());
            assert!(parse_page_cbor(bytes).is_err());
        }
    }
}
