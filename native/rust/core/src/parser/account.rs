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

#[cfg(test)]
mod tests {
    use super::*;

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
