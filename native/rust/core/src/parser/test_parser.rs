#[cfg(test)]
mod tests {
    use crate::parser::api::parse_vote_tag;
    use crate::parser::archive::{parse_archive_url, parse_archives_with_funds};
    use crate::parser::detail::parse_gallery_detail;
    use crate::parser::list::parse_info_list;
    use crate::parser::profile::{parse_profile, parse_profile_url};
    use reqwest::get;
    use tl::ParserOptions;

    #[tokio::test]
    #[ignore = "requires the live gallery service"]
    async fn test_parse_info() {
        let resp = get("https://e-hentai.org/").await.expect("Failed to get!");
        let body = resp.text().await.expect("Failed to receive!");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result = parse_info_list(&dom, dom.parser()).expect("Failed to parse info list");
        dbg!(result);
    }

    #[test]
    fn test_parse_archives_with_funds() {
        let body = r##"<div id="db"><div>
            <div style="width:180px"><div><strong>747,708 GP</strong></div>
            <form><input value="org"></form><p><strong>15.47 GiB</strong></p></div>
            <div style="color:#CCCCCC"><form><input value="res"></form></div>
            </div></div><p>742,189,531 GP [?] &nbsp; 1,561,004,772 Credits [?]</p>
            <p><a href="#" onclick="return do_hathdl('org')">Original</a></p>
            <p>15.47 GiB</p><p>332315 GP</p>"##;
        let dom = tl::parse(body, ParserOptions::default()).expect("Failed to parse html");
        let result =
            parse_archives_with_funds(&dom, dom.parser(), body).expect("Failed to parse archives");
        assert_eq!(result.funds.gp, 742189);
        assert_eq!(result.funds.credit, 1561004772);
        assert_eq!(result.archiveList.len(), 2);
        assert_eq!(result.archiveList[0].res, "org");
        assert_eq!(result.archiveList[0].cost, "747708 GP");
        assert!(!result.archiveList[0].isHath);
        assert!(result.archiveList[1].isHath);
    }

    #[test]
    fn test_parse_archive_url() {
        let body = r#"<div id="continue"><a href="https://0">Continue</a></div>"#;
        let dom = tl::parse(body, ParserOptions::default()).expect("Failed to parse html");
        let result = parse_archive_url(&dom, dom.parser(), body).expect("Failed to parse archives");
        assert_eq!(result, Some("https://0?start=1".to_string()));
    }

    #[tokio::test]
    #[ignore = "requires the live forum service"]
    async fn test_parse_profile() {
        let body = r#"<div id="userlinks"><a href="https://forums.e-hentai.org/index.php?showuser=6">Tenboro</a></div>"#;
        let dom = tl::parse(body, ParserOptions::default()).expect("Failed to parse html");
        let result = parse_profile_url(&dom, dom.parser()).expect("Failed to parse profile url");
        let resp = get(result).await.expect("Failed to get!");
        let body = resp.text().await.expect("Failed to receive!");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result = parse_profile(&dom, dom.parser()).expect("Failed to parse profile");
        assert_eq!(result.displayName, "Tenboro".to_string());
        assert_eq!(
            result.avatar,
            Some("https://forums.e-hentai.org/ehgt/jdk_180.png".to_string())
        );
    }

    #[tokio::test]
    #[ignore = "requires the live gallery service"]
    async fn test_parse_gallery_detail() {
        let resp = get("https://e-hentai.org/g/530350/8b3c7e4a21/")
            .await
            .expect("Failed to get!");
        let body = resp.text().await.expect("Failed to receive!");
        let mut dom =
            tl::parse(&body, ParserOptions::default().track_ids()).expect("Failed to parse HTML");
        let result = parse_gallery_detail(&mut dom, &body).expect("Failed to parse gallery detail");
        dbg!(result);
    }

    #[test]
    fn test_parse_tag_gallery() {
        let json = r#"{"error":"You cannot vote for this tag"}"#;
        let result = parse_vote_tag(json).expect_err("Should fail to parse");
        dbg!(result);
    }

    #[test]
    fn offline_profile_and_missing_login() {
        let body = r#"<div id="userlinks"><a href="https://forums.example/profile">Profile</a></div>
            <div class="row1"><div>Reader</div><div><img src="/avatar.png"></div></div>"#;
        let dom = tl::parse(body, ParserOptions::default()).unwrap();
        assert_eq!(
            parse_profile_url(&dom, dom.parser()).unwrap(),
            "https://forums.example/profile"
        );
        let profile = parse_profile(&dom, dom.parser()).unwrap();
        assert_eq!(profile.displayName, "Reader");
        assert_eq!(
            profile.avatar.as_deref(),
            Some("https://forums.e-hentai.org/avatar.png")
        );
        let empty = tl::parse("<div></div>", ParserOptions::default()).unwrap();
        assert!(parse_profile_url(&empty, empty.parser()).is_err());
    }

    #[test]
    fn archive_errors_and_missing_link() {
        for body in [
            "You must have a H@H client assigned to your account to use this feature.",
            "You do not have enough funds to download this archive.",
        ] {
            let dom = tl::parse(body, ParserOptions::default()).unwrap();
            assert!(parse_archive_url(&dom, dom.parser(), body).is_err());
        }
        let dom = tl::parse("<div></div>", ParserOptions::default()).unwrap();
        assert!(parse_archive_url(&dom, dom.parser(), "").unwrap().is_none());
    }
}
