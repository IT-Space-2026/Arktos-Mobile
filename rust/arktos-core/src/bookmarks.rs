//! Legacy bookmark importer (PORT01).
//!
//! Port of `BookmarkExporter.importBookmarksFromFileStream` /
//! `importBookmarksFromAssets`: the pre-Netscape export format is one
//! flat JSON object per line —
//! `{"url": "...", "title": "...", "folder": "...", "order": N}`.
//!
//! Two entry points matching the two upstream call sites:
//!
//! * `parse_default_bookmarks` — lenient, used for the bundled
//!   `raw/default_bookmarks`: a malformed line is logged/skipped like
//!   upstream's per-line `catch (JSONException)`.
//! * `import_legacy_bookmarks` — strict, used for user-picked files:
//!   the first malformed line fails the whole import
//!   (`JSONObject.get*` throws propagated through RxJava into the
//!   error dialog), returned here as `None`.

use crate::storage::StoreBookmark;

/// Lenient parse for the bundled defaults — skips malformed lines.
#[uniffi::export]
pub fn parse_default_bookmarks(text: String) -> Vec<StoreBookmark> {
    text.lines()
        .filter(|line| !line.trim().is_empty())
        .filter_map(parse_line)
        .collect()
}

/// Strict parse for user files — `None` on the first malformed line,
/// matching upstream's exception-propagates-out contract.
#[uniffi::export]
pub fn import_legacy_bookmarks(text: String) -> Option<Vec<StoreBookmark>> {
    text.lines()
        .filter(|line| !line.trim().is_empty())
        .map(parse_line)
        .collect()
}

fn parse_line(line: &str) -> Option<StoreBookmark> {
    let object = serde_json::from_str::<serde_json::Value>(line).ok()?;
    Some(StoreBookmark {
        url: object.get("url")?.as_str()?.to_owned(),
        title: object.get("title")?.as_str()?.to_owned(),
        folder: object.get("folder")?.as_str()?.to_owned(),
        position: object.get("order")?.as_u64()? as u32,
        created_at: 0,
    })
}

/// `BookmarkExporter.exportBookmarksToFile` payload (PORT02) — one
/// JSON object per line in upstream's insertion order
/// (`title, url, folder, order`), escaped like
/// `org.json.JSONObject.quote` (including the `</`→`<\/` quirk).
#[uniffi::export]
pub fn export_legacy_bookmarks(bookmarks: Vec<StoreBookmark>) -> String {
    let mut out = String::new();
    for b in &bookmarks {
        out.push_str("{\"title\":");
        json_quote(&b.title, &mut out);
        out.push_str(",\"url\":");
        json_quote(&b.url, &mut out);
        out.push_str(",\"folder\":");
        json_quote(&b.folder, &mut out);
        out.push_str(&format!(",\"order\":{}}}", b.position));
        out.push('\n');
    }
    out
}

/// `org.json.JSONObject.quote`: `"` and `\` escaped; `/` escaped only
/// after `<`; `\b \t \n \f \r` shorthand; C0, C1 (0x80–0x9F) and
/// U+2000–U+20FF as `\uXXXX`.
fn json_quote(s: &str, out: &mut String) {
    out.push('"');
    let mut prev = '\0';
    for c in s.chars() {
        match c {
            '"' | '\\' => {
                out.push('\\');
                out.push(c);
            }
            '/' if prev == '<' => out.push_str("\\/"),
            '\u{8}' => out.push_str("\\b"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\u{c}' => out.push_str("\\f"),
            '\r' => out.push_str("\\r"),
            c if (c as u32) < 0x20
                || (0x80..0xa0).contains(&(c as u32))
                || (0x2000..0x2100).contains(&(c as u32)) =>
            {
                out.push_str(&format!("\\u{:04x}", c as u32))
            }
            c => out.push(c),
        }
        prev = c;
    }
    out.push('"');
}

#[cfg(test)]
mod tests {
    use super::*;

    const TWO: &str = "{\"url\":\"https://a.com\",\"title\":\"A\",\"folder\":\"Docs\",\"order\":0}\n\
{\"url\":\"https://b.com\",\"title\":\"B\",\"folder\":\"\",\"order\":1}\n";

    #[test]
    fn parses_json_lines() {
        let out = parse_default_bookmarks(TWO.to_owned());
        assert_eq!(out.len(), 2);
        assert_eq!(out[0].url, "https://a.com");
        assert_eq!(out[0].folder, "Docs");
        assert_eq!(out[1].position, 1);
        assert_eq!(out[1].folder, "");
    }

    #[test]
    fn strict_import_fails_on_bad_line() {
        let bad = format!("{TWO}not json\n");
        assert!(import_legacy_bookmarks(bad).is_none());
        // but the lenient parse skips it
        assert_eq!(parse_default_bookmarks(format!("not json\n{TWO}")).len(), 2);
    }

    #[test]
    fn strict_import_fails_on_missing_field() {
        // `order` missing → JSONObject.getInt throws upstream.
        let line = "{\"url\":\"https://a.com\",\"title\":\"A\",\"folder\":\"\"}\n";
        assert!(import_legacy_bookmarks(line.to_owned()).is_none());
    }

    #[test]
    fn empty_and_whitespace_input() {
        assert!(parse_default_bookmarks("\n\n  \n".to_owned()).is_empty());
        assert_eq!(import_legacy_bookmarks("".to_owned()), Some(vec![]));
    }

    #[test]
    fn export_writes_upstream_order_and_escapes() {
        let rows = vec![
            StoreBookmark {
                url: "https://a.com/</x>".into(),
                title: "A \"quoted\"\nline".into(),
                folder: "Docs".into(),
                position: 0,
                created_at: 0,
            },
            StoreBookmark {
                url: "https://b.com".into(),
                title: "B".into(),
                folder: "".into(),
                position: 7,
                created_at: 0,
            },
        ];
        let out = export_legacy_bookmarks(rows);
        assert_eq!(
            out,
            "{\"title\":\"A \\\"quoted\\\"\\nline\",\"url\":\"https://a.com/<\\/x>\",\"folder\":\"Docs\",\"order\":0}\n\
{\"title\":\"B\",\"url\":\"https://b.com\",\"folder\":\"\",\"order\":7}\n"
        );
        // Roundtrip: the strict importer reads the export back.
        let back = import_legacy_bookmarks(out).unwrap();
        assert_eq!(back.len(), 2);
        assert_eq!(back[0].url, "https://a.com/</x>");
        assert_eq!(back[0].title, "A \"quoted\"\nline");
        assert_eq!(back[1].position, 7);
    }
}
