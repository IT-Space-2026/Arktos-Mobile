//! Minimal `bookmarkhtml` surface — only the URL-scheme guard the
//! ported `bookmarks` store calls on writes; the HTML import/export
//! converter stays desktop-side.

use crate::error::{Fail, RcResult, RcStatus};

fn invalid<T>(msg: impl Into<String>) -> RcResult<T> {
    Err(Fail {
        status: RcStatus::InvalidArgument,
        msg: msg.into(),
    })
}

/// The scheme part of `url` (lowercased) — None when the value is
/// a relative/path-only string the caller accepts as-is.
fn url_scheme(url: &str) -> Option<String> {
    let i = url.find(':')?;
    if i == 0 {
        return None;
    }
    let s = &url[..i];
    if s.chars().all(|c| c.is_ascii_alphanumeric() || c == '+' || c == '-' || c == '.') {
        Some(s.to_ascii_lowercase())
    } else {
        None
    }
}

/// Same gate as the desktop: only mainstream schemes pass; a
/// `javascript:` url needs the caller's explicit opt-in.
pub fn check_bookmark_url(url: &str, allow_javascript: bool) -> RcResult<()> {
    let Some(scheme) = url_scheme(url) else {
        return Ok(());
    };
    let ok = match scheme.as_str() {
        "http" | "https" | "ftp" | "mailto" => true,
        "javascript" => allow_javascript,
        _ => false,
    };
    if ok {
        Ok(())
    } else {
        invalid(format!("bookmark url scheme refused: {scheme}"))
    }
}
