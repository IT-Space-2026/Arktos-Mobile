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
}
