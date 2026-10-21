// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
//! Netscape Bookmark File Format importer (STORE01).
//!
//! Port of `NetscapeBookmarkFormatImporter` — same semantics as the
//! jsoup version, expressed as a streaming pass over the
//! `html5gum` tokenizer instead of a DOM walk:
//!
//! * the first top-level `<DL>` is the root list (folder `""`);
//! * a `<DT>` whose first child is `<H3>` names the `<DL>` sibling
//!   that follows it — the folder path accumulates with `/`;
//! * a `<DT>` whose first child is `<A>` is a bookmark —
//!   `HREF` attribute → url, element text → title;
//! * anything else nested in a `<DL>` (including `<DT><DL>` without a
//!   preceding `<H3>`) contributes nothing, matching the jsoup code
//!   that only ever looked at `child(0)` and `nextElementSibling()`.
//!
//! Positions/created_at come back as 0 — the store assigns them on
//! insert.

use crate::storage::StoreBookmark;
use html5gum::{StartTag, Token, Tokenizer};

/// Parse Netscape bookmark HTML into flat bookmark rows. Never fails
/// — the tokenizer recovers from malformed input like jsoup did.
#[uniffi::export]
pub fn parse_netscape_bookmarks(html: String) -> Vec<StoreBookmark> {
    parse(&html)
}

fn parse(html: &str) -> Vec<StoreBookmark> {
    // Folder path of each open <DL> level; `None` marks a level whose
    // subtree is ignored (a <DL> without a preceding <H3>, or nested
    // outside <DT> — never reached by the jsoup traversal).
    let mut levels: Vec<Option<String>> = Vec::new();
    let mut pending_folder: Option<String> = None;
    let mut in_dt = false;
    let mut first_child_done = false;
    // Text accumulator for the element currently being captured
    // (<H3> or <A>), plus the HREF seen on the <A>.
    let mut capture = Capture::None;
    let mut out = Vec::new();

    for token in Tokenizer::new(html.as_bytes()).flatten() {
        match token {
            Token::StartTag(tag) => {
                let name = tag.name.as_slice();
                if eq(name, b"dt") {
                    in_dt = true;
                    first_child_done = false;
                } else if eq(name, b"dl") {
                    let level = if in_dt {
                        match pending_folder.take() {
                            Some(name) => levels
                                .last()
                                .and_then(|p| p.as_ref())
                                .map(|parent| compose(parent, &name)),
                            // <DT><DL> with no <H3>: first child is a
                            // list — the jsoup code skipped the subtree.
                            None => {
                                first_child_done = true;
                                None
                            }
                        }
                    } else {
                        // Only the first stray top-level <DL> is the
                        // root list; any other is unreachable.
                        levels.is_empty().then(String::new)
                    };
                    levels.push(level);
                } else if in_dt && !first_child_done && eq(name, b"h3") {
                    capture = Capture::Folder(String::new());
                    first_child_done = true;
                } else if in_dt && !first_child_done && eq(name, b"a") {
                    capture = Capture::Bookmark {
                        href: href_of(&tag),
                        text: String::new(),
                    };
                    first_child_done = true;
                } else if in_dt && !first_child_done {
                    first_child_done = true;
                }
            }
            Token::EndTag(tag) => {
                let name = tag.name.as_slice();
                if eq(name, b"dl") {
                    levels.pop();
                    in_dt = false;
                } else if eq(name, b"dt") {
                    in_dt = false;
                } else if eq(name, b"h3") {
                    if let Capture::Folder(text) =
                        std::mem::replace(&mut capture, Capture::None)
                    {
                        pending_folder = Some(text);
                    }
                } else if eq(name, b"a") {
                    if let Capture::Bookmark { href, text } =
                        std::mem::replace(&mut capture, Capture::None)
                    {
                        if let Some(Some(folder)) = levels.last() {
                            out.push(StoreBookmark {
                                url: href,
                                title: text,
                                folder: folder.clone(),
                                position: 0,
                                created_at: 0,
                            });
                        }
                    }
                }
            }
            Token::String(s) => capture.push_str(&String::from_utf8_lossy(&s.value)),
            _ => {}
        }
    }
    out
}

enum Capture {
    None,
    Folder(String),
    Bookmark { href: String, text: String },
}

impl Capture {
    fn push_str(&mut self, s: &str) {
        match self {
            Capture::Folder(t) | Capture::Bookmark { text: t, .. } => t.push_str(s),
            Capture::None => {}
        }
    }
}

fn eq(a: &[u8], b: &[u8]) -> bool {
    a.eq_ignore_ascii_case(b)
}

/// `computeFolderName` from the Kotlin importer: parent "" → child,
/// otherwise "parent/child".
fn compose(parent: &str, child: &str) -> String {
    if parent.is_empty() {
        child.to_owned()
    } else {
        format!("{parent}/{child}")
    }
}

fn href_of(tag: &StartTag<()>) -> String {
    tag.attributes
        .iter()
        .find(|(k, _)| eq(k.as_slice(), b"href"))
        .map(|(_, v)| String::from_utf8_lossy(&v.value).into_owned())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "<!DOCTYPE NETSCAPE-Bookmark-file-1>
<TITLE>Bookmarks</TITLE>
<H1>Bookmarks</H1>
<DL><p>
    <DT><H3>Dev</H3>
    <DL><p>
        <DT><A HREF=\"https://rust-lang.org\">Rust</A>
        <DT><A HREF=\"https://doc.rust-lang.org\">Docs &amp; Stuff</A>
        <DT><H3>Inner</H3>
        <DL><p>
            <DT><A HREF=\"https://crates.io\">Crates</A>
        </DL><p>
    </DL><p>
    <DT><A HREF=\"https://example.com\" ADD_DATE=\"1\">Example</A>
</DL><p>";

    #[test]
    fn parses_nested_folders_and_root() {
        let out = parse(SAMPLE);
        assert_eq!(out.len(), 4);
        assert_eq!(out[0].url, "https://rust-lang.org");
        assert_eq!(out[0].title, "Rust");
        assert_eq!(out[0].folder, "Dev");
        assert_eq!(out[2].url, "https://crates.io");
        assert_eq!(out[2].folder, "Dev/Inner");
        assert_eq!(out[3].url, "https://example.com");
        assert_eq!(out[3].folder, "");
    }

    #[test]
    fn decodes_entities_in_titles() {
        let out = parse(SAMPLE);
        assert_eq!(out[1].title, "Docs & Stuff");
    }

    #[test]
    fn folder_without_h3_is_skipped() {
        // <DT><DL> — the jsoup importer ignored this subtree.
        let html = "<DL><DT><DL><DT><A HREF=\"https://x.com\">X</A></DL></DT>\
                    <DT><A HREF=\"https://y.com\">Y</A></DL>";
        let out = parse(html);
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].url, "https://y.com");
    }

    #[test]
    fn uppercase_tags_and_quoted_attrs() {
        let html = "<DL><DT><A HREF='https://q.com'>Q</A></DL>";
        let out = parse(html);
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].url, "https://q.com");
    }

    #[test]
    fn empty_and_garbage_input_do_not_panic() {
        assert!(parse("").is_empty());
        assert!(parse("not html at all").is_empty());
        assert!(parse("<DL><DT>").is_empty());
    }
}
