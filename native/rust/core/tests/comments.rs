use ehviewer_core::parser::detail::parse_comments;

fn document(count: usize, parts: usize, url: &str) -> String {
    let body = "<b>owned</b>".repeat(parts);
    let comments = (1..=count).map(|id| format!(r#"<div class="c1"><div class="c3">Posted on 05 November 2026, 10:00 by: <a>Reader</a></div><div class="c6" id="comment_{id}"><a href="{url}">{body}</a></div></div>"#)).collect::<String>();
    format!(
        r#"<div id="cdiv"><div id="chd"><a id="postnewcomment">Comment</a></div>{comments}</div>"#
    )
}

fn parse(html: &str) -> anyhow::Result<ehviewer_core::parser::detail::GalleryCommentList> {
    let mut dom = tl::parse(html, tl::ParserOptions::default().track_ids())?;
    parse_comments(&mut dom)
}

#[test]
fn repeated_addresses_are_bounded_before_a_small_comment_can_expand_in_memory() {
    let url = format!("https://example.org/{}", "x".repeat(4000));
    let control = parse(&document(1, 2, &url)).unwrap();
    assert_eq!(control.comments[0].body.len(), 2);
    assert_eq!(
        control.comments[0].body[0].link.as_deref(),
        Some(url.as_str())
    );
    let html = document(1, 300, &url);
    assert!(html.len() < 10_000);
    assert!(
        parse(&html).is_err(),
        "Repeated link ownership must be budgeted before cloning"
    );
    let invalid_url = format!("https://example.org/{}", "x".repeat(8193));
    let plain = parse(&document(1, 300, &invalid_url)).unwrap();
    assert!(
        plain.comments[0]
            .body
            .iter()
            .all(|part| part.link.is_none())
    );
}

#[test]
fn the_whole_gallery_has_a_rendering_budget_shared_by_all_comments() {
    let url = format!("https://example.org/{}", "x".repeat(2000));
    assert!(parse(&document(1, 200, &url)).is_ok());
    let html = document(25, 200, &url);
    assert!(html.len() < 200_000);
    assert!(
        parse(&html).is_err(),
        "A separate budget for each comment cannot bound the gallery"
    );
}
