use crate::parser::utils::unescape;
use crate::regex;
use anyhow::{Context, Result, ensure};
use serde::Serialize;
use tl::{Parser, VDom};

#[derive(Serialize)]
pub struct Torrent {
    outdated: bool,
    posted: String,
    size: String,
    seeds: i32,
    peers: i32,
    downloads: i32,
    uploader: String,
    url: String,
    name: String,
}

/// Reuse the original parser while rejecting unrelated, partial or amplification-heavy documents.
pub fn parse_torrent_list_cbor(input: &[u8]) -> Result<Vec<u8>> {
    const MAX_BYTES: usize = 8 * 1024 * 1024;
    ensure!(
        !input.is_empty() && input.len() <= MAX_BYTES,
        "Invalid torrent document size"
    );
    let html = std::str::from_utf8(input).context("Invalid torrent document encoding")?;
    let dom = tl::parse(html, tl::ParserOptions::default())?;
    ensure!(dom.version().is_some(), "Missing torrent document");
    let parser = dom.parser();
    let scope = dom
        .get_element_by_id("torrentinfo")
        .and_then(|node| node.get(parser)?.as_tag())
        .context("Missing torrent list")?;
    // Charge nested table content before calling the legacy parser's allocating regex captures.
    let mut count = 0usize;
    let mut budget = 0usize;
    for node in dom
        .query_selector("table")
        .context("Invalid torrent tables")?
    {
        count += 1;
        ensure!(count <= 1024, "Torrent table limit");
        let body = node
            .get(parser)
            .context("Invalid torrent table")?
            .inner_html(parser);
        budget = budget
            .checked_add(body.len())
            .context("Torrent table budget overflow")?;
        ensure!(budget <= MAX_BYTES, "Torrent table content limit");
    }
    let mut scoped_count = 0usize;
    let mut active = 0usize;
    for node in scope
        .query_selector(parser, "table")
        .context("Invalid torrent list")?
    {
        scoped_count += 1;
        if !node
            .get(parser)
            .context("Invalid torrent record")?
            .inner_html(parser)
            .contains("Expunged")
        {
            active += 1;
        }
    }
    let list = parse_torrent_list(&dom, parser)?;
    ensure!(list.len() == active, "Partial torrent list");
    ensure!(
        scoped_count > 0
            || scope
                .inner_text(parser)
                .contains("There are no torrents for this gallery."),
        "Unrecognized empty torrent list"
    );
    let output = serde_cbor::to_vec(&list)?;
    ensure!(output.len() <= MAX_BYTES, "Torrent result limit");
    Ok(output)
}

pub fn parse_torrent_list(dom: &VDom, parser: &Parser) -> Result<Vec<Torrent>> {
    let list = dom.query_selector("table").context("No Table")?.filter_map(|e| {
        let html = e.get(parser)?.inner_html(parser);
        if html.contains("Expunged") {
            None
        } else {
            let grp = regex!("<span( style=\"color:red\")?>([0-9-]+) [0-9:]+</span>[\\s\\S]+</span> ([0-9.]+ [KMGT]iB)</td>[\\s\\S]+</span> ([0-9]+)</td>[\\s\\S]+</span> ([0-9]+)</td>[\\s\\S]+</span> ([0-9]+)</td>[\\s\\S]+</span> ([^<]+)</td>[\\s\\S]+onclick=\"document.location='([^\"]+)'[^<]+>([^<]+)</a>").captures(&html)?;
            let name = unescape(&grp[9]).ok()?;
            Some(Torrent {
                outdated: grp.get(1).is_some(),
                posted: grp[2].to_string(),
                size: grp[3].to_string(),
                seeds: grp[4].parse().ok()?,
                peers: grp[5].parse().ok()?,
                downloads: grp[6].parse().ok()?,
                uploader: grp[7].to_string(),
                url: grp[8].to_string(),
                name: name.to_string()
            })
        }
    }).collect::<Vec<Torrent>>();
    Ok(list)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn document(body: &str) -> String {
        format!("<!DOCTYPE html><html><body><div id=\"torrentinfo\">{body}</div></body></html>")
    }

    fn record() -> String {
        concat!(
            "<table><tr><td><span style=\"color:red\">2026-11-05 12:34</span></td>",
            "<td><span>Size:</span> 63.14 MiB</td>",
            "<td><span>Seeds:</span> 12</td><td><span>Peers:</span> 3</td>",
            "<td><span>Downloads:</span> 42</td><td><span>Uploader:</span> Reader</td>",
            "<td><a onclick=\"document.location='https://ehtracker.org/get/1/0123456789abcdef0123456789abcdef01234567.torrent'\">Alice &amp; friends.zip</a></td></tr></table>"
        ).to_string()
    }

    #[test]
    fn portable_torrents_reuse_original_fields_and_exclude_expunged_entries() {
        let html = document(&(record() + "<table><tr><td>Expunged</td></tr></table>"));
        let values: Vec<serde_json::Value> =
            serde_cbor::from_slice(&parse_torrent_list_cbor(html.as_bytes()).unwrap()).unwrap();
        assert_eq!(values.len(), 1);
        assert_eq!(values[0]["name"], "Alice & friends.zip");
        assert_eq!(values[0]["outdated"], true);
        assert_eq!(values[0]["posted"], "2026-11-05");
        assert_eq!(values[0]["seeds"], 12);
        assert_eq!(values[0]["peers"], 3);
        assert_eq!(values[0]["downloads"], 42);
    }

    #[test]
    fn portable_torrents_distinguish_explicit_empty_from_missing_or_partial_documents() {
        let empty = document("<p>There are no torrents for this gallery.</p>");
        let values: Vec<serde_json::Value> =
            serde_cbor::from_slice(&parse_torrent_list_cbor(empty.as_bytes()).unwrap()).unwrap();
        assert!(values.is_empty());
        for invalid in [
            "<!DOCTYPE html><html>challenge</html>".to_string(),
            document(""),
            document("<table><tr><td>Broken record</td></tr></table>"),
            document(&(record() + "<table><td>Broken record</td></table>")),
        ] {
            assert!(parse_torrent_list_cbor(invalid.as_bytes()).is_err());
        }
        assert!(parse_torrent_list_cbor(&[0xff]).is_err());
    }

    #[test]
    fn nested_torrent_tables_have_a_shared_budget_before_legacy_capture_allocation() {
        let nested = "<table>".repeat(64) + &"a".repeat(256 * 1024) + &"</table>".repeat(64);
        assert!(parse_torrent_list_cbor(document(&nested).as_bytes()).is_err());
        let many = "<table><td>Expunged</td></table>".repeat(1025);
        assert!(parse_torrent_list_cbor(document(&many).as_bytes()).is_err());
    }
}
