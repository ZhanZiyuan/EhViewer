//! Shared account documents reuse the original forum and user-config parsers.
use super::{
    config::parse_fav_cat,
    profile::{parse_profile, parse_profile_url},
};
use anyhow::{Context, Result, ensure};
use serde::Serialize;

fn document(input: &[u8]) -> Result<tl::VDom<'_>> {
    ensure!(
        !input.is_empty() && input.len() <= 8 * 1024 * 1024,
        "Invalid account document size"
    );
    let text = std::str::from_utf8(input).context("Invalid account document encoding")?;
    let dom = tl::parse(text, tl::ParserOptions::default())?;
    ensure!(dom.version().is_some(), "Missing account HTML document");
    Ok(dom)
}

fn encode(value: impl Serialize) -> Result<Vec<u8>> {
    let bytes = serde_cbor::to_vec(&value)?;
    ensure!(bytes.len() <= 8 * 1024 * 1024, "Account result size limit");
    Ok(bytes)
}

pub fn parse_profile_link_cbor(input: &[u8]) -> Result<Vec<u8>> {
    let dom = document(input)?;
    // A well-formed guest forum page has no user link. Preserve that distinction
    // from a truncated/binary response so Kotlin reports expired authentication.
    encode(parse_profile_url(&dom, dom.parser()).ok())
}

pub fn parse_profile_cbor(input: &[u8]) -> Result<Vec<u8>> {
    let dom = document(input)?;
    let profile = parse_profile(&dom, dom.parser())?;
    ensure!(
        !profile.displayName.trim().is_empty() && profile.displayName.len() <= 1024,
        "Invalid profile name"
    );
    ensure!(
        profile
            .avatar
            .as_ref()
            .is_none_or(|value| value.len() <= 8192),
        "Invalid avatar URL size"
    );
    encode(profile)
}

pub fn parse_user_config_cbor(input: &[u8]) -> Result<Vec<u8>> {
    document(input)?;
    let categories = parse_fav_cat(std::str::from_utf8(input)?);
    ensure!(
        categories.len() == 10 && categories.iter().all(|value| value.len() <= 1024),
        "Missing authenticated user configuration"
    );
    encode(categories)
}

#[derive(Serialize)]
#[allow(non_snake_case)]
struct FavoriteNote {
    note: String,
    maximumCharacters: u32,
}

/// Read the existing popup's textarea without losing numeric entities or literal markup.
/// The limit is a client resource budget, further restricted by an advertised maxlength.
pub fn parse_favorite_note_cbor(input: &[u8]) -> Result<Vec<u8>> {
    let dom = document(input)?;
    let parser = dom.parser();
    let mut areas = dom
        .query_selector("textarea")
        .context("Missing favorite note")?;
    let area = areas
        .next()
        .and_then(|node| node.get(parser))
        .and_then(tl::Node::as_tag)
        .context("Missing favorite note")?;
    ensure!(areas.next().is_none(), "Ambiguous favorite note");
    ensure!(
        crate::get_tag_attr(area, "name").is_none_or(|name| name == "favnote"),
        "Unexpected favorite textarea"
    );
    let maximum = crate::get_tag_attr(area, "maxlength")
        .map(str::parse::<u32>)
        .transpose()?
        .unwrap_or(4096)
        .min(4096);
    ensure!(maximum > 0, "Invalid favorite note limit");
    let raw = area.inner_html(parser);
    let note = super::utils::unescape(&raw)?
        .replace("\r\n", "\n")
        .replace('\r', "\n");
    ensure!(
        !note.contains('\0') && note.encode_utf16().count() <= maximum as usize,
        "Favorite note exceeds the supported limit"
    );
    encode(FavoriteNote {
        note,
        maximumCharacters: maximum,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn favorite_note_preserves_entities_unicode_newlines_and_its_declared_limit() {
        let html = "<!DOCTYPE html><html><body><textarea name=\"favnote\" maxlength=\"200\">A &amp; B &#x1F4D6; &#20013; &lt;b&gt;\r\nsecond</textarea></body></html>";
        let result: std::collections::BTreeMap<String, serde_cbor::Value> =
            serde_cbor::from_slice(&parse_favorite_note_cbor(html.as_bytes()).unwrap()).unwrap();
        assert_eq!(
            result.get("note"),
            Some(&serde_cbor::Value::Text("A & B 📖 中 <b>\nsecond".into()))
        );
        assert_eq!(
            result.get("maximumCharacters"),
            Some(&serde_cbor::Value::Integer(200))
        );
        assert!(parse_favorite_note_cbor(b"<!DOCTYPE html><textarea></textarea>").is_ok());
    }

    #[test]
    fn favorite_note_rejects_ambiguous_wrong_and_over_budget_documents() {
        for html in [
            "<!DOCTYPE html><textarea name=\"reply\">wrong field</textarea>".to_string(),
            "<!DOCTYPE html><textarea></textarea><textarea></textarea>".into(),
            "<!DOCTYPE html><textarea maxlength=\"0\"></textarea>".into(),
            "<!DOCTYPE html><textarea maxlength=\"invalid\"></textarea>".into(),
            "<!DOCTYPE html><textarea maxlength=\"1\">&#x1F4D6;</textarea>".into(),
            format!("<!DOCTYPE html><textarea>{}</textarea>", "x".repeat(4097)),
            "<!DOCTYPE html><textarea>&unknown;</textarea>".into(),
            "<!DOCTYPE html><body>Sign in</body>".into(),
        ] {
            assert!(parse_favorite_note_cbor(html.as_bytes()).is_err());
        }
        assert!(parse_favorite_note_cbor(&[255, 0, 255]).is_err());
    }

    #[test]
    fn original_forum_parser_preserves_name_avatar_and_guest_state() {
        let html = br#"<!DOCTYPE html><html><body><div id="userlinks"><a href="https://forums.e-hentai.org/index.php?showuser=123">Profile</a></div><div class="row1"><div>Reader &amp; Writer</div><div><img src="/avatar.png"></div></div></body></html>"#;
        let link: Option<String> =
            serde_cbor::from_slice(&parse_profile_link_cbor(html).unwrap()).unwrap();
        assert_eq!(
            link.as_deref(),
            Some("https://forums.e-hentai.org/index.php?showuser=123")
        );
        let profile: std::collections::BTreeMap<String, serde_cbor::Value> =
            serde_cbor::from_slice(&parse_profile_cbor(html).unwrap()).unwrap();
        assert_eq!(
            profile.get("displayName"),
            Some(&serde_cbor::Value::Text("Reader & Writer".into()))
        );
        assert_eq!(
            profile.get("avatar"),
            Some(&serde_cbor::Value::Text(
                "https://forums.e-hentai.org/avatar.png".into()
            ))
        );
        let guest: Option<String> = serde_cbor::from_slice(
            &parse_profile_link_cbor(b"<!DOCTYPE html><html><body>Guest</body></html>").unwrap(),
        )
        .unwrap();
        assert!(guest.is_none());
    }

    #[test]
    fn config_requires_all_original_categories_and_rejects_binary_login_and_oversized_documents() {
        let mut html = String::from("<!DOCTYPE html><html><body>");
        for index in 0..10 {
            html.push_str(&format!(
                r#"<input type="text" name="favorite_{index}" value="Shelf {index}">"#
            ));
        }
        html.push_str("</body></html>");
        let categories: Vec<String> =
            serde_cbor::from_slice(&parse_user_config_cbor(html.as_bytes()).unwrap()).unwrap();
        assert_eq!(categories.len(), 10);
        assert_eq!(categories[9], "Shelf 9");
        for invalid in [
            b"GIF89a".as_slice(),
            b"<!DOCTYPE html><html><body>Sign in</body></html>",
            &[255, 0, 255],
            &vec![b'x'; 8 * 1024 * 1024 + 1],
        ] {
            assert!(parse_user_config_cbor(invalid).is_err());
        }
    }
}
