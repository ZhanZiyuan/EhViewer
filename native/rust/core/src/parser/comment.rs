//! Safe, platform-neutral comment text, using the same pinned DOM as gallery parsing.
use anyhow::{Result, ensure};
use quick_xml::escape::unescape;
use serde::Serialize;
use tl::{HTMLTag, Node, Parser};

pub(super) const GALLERY_COMMENT_BUDGET: usize = 8 * 1024 * 1024;
const SINGLE_COMMENT_BUDGET: usize = 1024 * 1024;

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct GalleryCommentText {
    pub text: String,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub strike: bool,
    pub link: Option<String>,
    pub image: Option<String>,
}

fn attribute(tag: &HTMLTag, name: &str) -> Option<String> {
    let value = tag.attributes().get(name)??.try_as_utf8_str()?;
    if value.len() > 8192 {
        return None;
    }
    let value = unescape(value).unwrap_or_else(|_| value.into());
    (value.len() <= 8192).then(|| value.into_owned())
}

fn append(
    out: &mut Vec<GalleryCommentText>,
    style: &GalleryCommentText,
    text: &str,
    remaining: &mut usize,
) -> Result<()> {
    if !text.is_empty() {
        // Charge owned strings before cloning inherited links/images for every text run.
        let bytes = size_of::<GalleryCommentText>()
            + text.len()
            + style.link.as_ref().map_or(0, String::len)
            + style.image.as_ref().map_or(0, String::len);
        ensure!(bytes <= *remaining, "Comment rendering size limit");
        *remaining -= bytes;
        out.push(GalleryCommentText {
            text: text.into(),
            ..style.clone()
        });
    }
    Ok(())
}

fn newline(out: &mut Vec<GalleryCommentText>, remaining: &mut usize) -> Result<()> {
    if out.last().is_some_and(|last| !last.text.ends_with('\n')) {
        append(out, &GalleryCommentText::default(), "\n", remaining)?;
    }
    Ok(())
}

fn walk(
    tag: &HTMLTag,
    parser: &Parser,
    inherited: &GalleryCommentText,
    out: &mut Vec<GalleryCommentText>,
    depth: usize,
    pre: bool,
    remaining: &mut usize,
) -> Result<()> {
    ensure!(depth <= 64 && out.len() <= 4096, "Comment structure limit");
    for handle in tag.children().top().iter() {
        let Some(node) = handle.get(parser) else {
            continue;
        };
        match node {
            Node::Raw(raw) => {
                let raw = raw.as_utf8_str();
                let decoded = unescape(&raw).unwrap_or_else(|_| raw.clone());
                let text = if pre {
                    decoded.into_owned()
                } else {
                    let mut text = String::new();
                    let mut space = out
                        .last()
                        .is_none_or(|last| last.text.ends_with(char::is_whitespace));
                    for c in decoded.chars() {
                        if c.is_ascii_whitespace() {
                            if !space {
                                text.push(' ');
                            }
                            space = true;
                        } else {
                            text.push(c);
                            space = false;
                        }
                    }
                    text
                };
                append(out, inherited, &text, remaining)?;
            }
            Node::Tag(child) => {
                let name = child.name().as_utf8_str().to_ascii_lowercase();
                if matches!(
                    name.as_str(),
                    "script" | "style" | "iframe" | "object" | "template"
                ) {
                    continue;
                }
                if name == "br" {
                    append(out, &GalleryCommentText::default(), "\n", remaining)?;
                    continue;
                }
                let mut style = inherited.clone();
                match name.as_str() {
                    "b" | "strong" => style.bold = true,
                    "i" | "em" => style.italic = true,
                    "u" => style.underline = true,
                    "s" | "del" | "strike" => style.strike = true,
                    "a" => style.link = attribute(child, "href"),
                    "img" => {
                        style.image = attribute(child, "src");
                        append(
                            out,
                            &style,
                            &attribute(child, "alt")
                                .filter(|s| !s.is_empty())
                                .unwrap_or_else(|| "\u{fffc}".into()),
                            remaining,
                        )?;
                        continue;
                    }
                    _ => {}
                }
                let block = matches!(name.as_str(), "p" | "div" | "blockquote" | "pre" | "li");
                if block {
                    newline(out, remaining)?;
                }
                if name == "li" {
                    append(out, &GalleryCommentText::default(), "• ", remaining)?;
                }
                walk(
                    child,
                    parser,
                    &style,
                    out,
                    depth + 1,
                    pre || name == "pre",
                    remaining,
                )?;
                if block {
                    newline(out, remaining)?;
                }
            }
            _ => {}
        }
        ensure!(out.len() <= 4096, "Comment structure limit");
    }
    Ok(())
}

pub fn parse_comment_body(
    tag: &HTMLTag,
    parser: &Parser,
    gallery_remaining: &mut usize,
) -> Result<Vec<GalleryCommentText>> {
    let mut out = Vec::new();
    let limit = SINGLE_COMMENT_BUDGET.min(*gallery_remaining);
    let mut remaining = limit;
    walk(
        tag,
        parser,
        &GalleryCommentText::default(),
        &mut out,
        0,
        false,
        &mut remaining,
    )?;
    *gallery_remaining -= limit - remaining;
    while out.last().is_some_and(|last| last.text.trim().is_empty()) {
        out.pop();
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn parse(html: &str) -> Result<Vec<GalleryCommentText>> {
        let dom = tl::parse(html, tl::ParserOptions::default())?;
        let tag = dom
            .query_selector("div")
            .unwrap()
            .next()
            .unwrap()
            .get(dom.parser())
            .unwrap()
            .as_tag()
            .unwrap();
        let mut budget = GALLERY_COMMENT_BUDGET;
        parse_comment_body(tag, dom.parser(), &mut budget)
    }

    #[test]
    fn nested_styles_entities_breaks_images_and_links_keep_real_content() {
        let parts = parse(r##"<div><b>Owned &amp; <i>日本語 &#x1f4da;</i></b><br><a href="#c12">reply</a><p><u>next</u> <s>old</s></p><img src="https://ehgt.org/a.png?x=1&amp;y=2" alt="sample"></div>"##).unwrap();
        assert_eq!(
            parts.iter().map(|p| p.text.as_str()).collect::<String>(),
            "Owned & 日本語 📚\nreply\nnext old\nsample"
        );
        assert!(
            parts
                .iter()
                .any(|p| p.bold && p.italic && p.text == "日本語 📚")
        );
        assert!(parts.iter().any(|p| p.link.as_deref() == Some("#c12")));
        assert!(parts.iter().any(|p| p.underline));
        assert!(parts.iter().any(|p| p.strike));
        assert_eq!(
            parts.last().unwrap().image.as_deref(),
            Some("https://ehgt.org/a.png?x=1&y=2")
        );
    }

    #[test]
    fn active_html_is_discarded_and_whitespace_and_pre_are_preserved() {
        let parts = parse("<div>  one\n <b>two</b> three<script>alert(1)</script><iframe>hidden</iframe><pre> x\n  y</pre><ul><li>item</li></ul></div>").unwrap();
        assert_eq!(
            parts.iter().map(|p| p.text.as_str()).collect::<String>(),
            "one two three\n x\n  y\n• item"
        );
        assert!(
            parse(&format!(
                "<div>{}text{}</div>",
                "<b>".repeat(66),
                "</b>".repeat(66)
            ))
            .is_err()
        );
    }
}
