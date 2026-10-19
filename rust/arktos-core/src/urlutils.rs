//! PORT02 — port of `utils/UrlUtils.kt` plus the scheme logic shared
//! by `utils/IntentUtils.java` and `BrowserPresenter.isIntentUrlAllowed`.
//!
//! `smart_url_filter` reproduces Android's `Patterns.WEB_URL`,
//! `URLUtil.guessUrl`/`composeSearchUrl`, and the shared
//! `ACCEPTED_URI_SCHEMA` regex without Android or the `url` crate —
//! WHATWG `Url::parse` can't reproduce WEB_URL's lenient
//! "looks like a host" heuristic or guessUrl's `www.…​.com`
//! expansion, and skipping it keeps the dependency surface smaller.
//! WEB_URL's giant IANA TLD alternation is approximated as
//! "lowercase-alpha ≥2 or `xn--`" (see [`looks_like_web_url`]), and
//! `java.net.URI.getHost` is approximated by a lenient
//! authority/host split in [`domain_name`].

const FILE_SCHEME: &str = "file://";
const BOOKMARK_PAGE: &str = "bookmark.html";
const DOWNLOADS_PAGE: &str = "downloads.html";
const HISTORY_PAGE: &str = "history.html";
const HOME_PAGE: &str = "homepage.html";
const INCOGNITO_PAGE: &str = "private.html";
const CHANGELOG_PAGE: &str = "changelog.html";
/// Canonical in-app URL of the bundled changelog (CHLOG01) — served
/// out of assets by `ArktosCookieWebClient.shouldInterceptRequest`.
const CHANGELOG_URL: &str = "arktos://changelog";

/// Search-template token substituted with the encoded query —
/// `UrlUtils.QUERY_PLACE_HOLDER` on the Kotlin side.
const QUERY_PLACEHOLDER: &str = "%s";

/// `UrlUtils.smartUrlFilter` — decide whether user input is a URL or
/// search terms. Returns the (possibly normalized) URL, the composed
/// search URL, or `""` when [can_be_search] is false and the input is
/// not a URL.
#[uniffi::export]
pub fn smart_url_filter(url: String, can_be_search: bool, search_url: String) -> String {
    let mut in_url = url.trim().to_owned();
    let has_space = in_url.contains(' ');

    if let Some(scheme_end) = accepted_scheme_end(&in_url) {
        // Force the scheme to lowercase. Kotlin used
        // `Locale.getDefault()`; ASCII schemes make this identical.
        let lowered = in_url[..scheme_end].to_lowercase();
        if lowered != in_url[..scheme_end] {
            in_url = format!("{lowered}{}", &in_url[scheme_end..]);
        }
        if has_space && looks_like_web_url(&in_url) {
            in_url = in_url.replace(' ', "%20");
        }
        return in_url;
    }

    if !has_space && looks_like_web_url(&in_url) {
        return guess_url(&in_url);
    }

    if can_be_search {
        // URLUtil.composeSearchUrl returns null when the template has
        // no placeholder — Kotlin's non-null return made that an NPE;
        // the Rust port yields "" instead.
        compose_search_url(in_url, search_url, QUERY_PLACEHOLDER.to_owned())
    } else {
        String::new()
    }
}

/// The scheme check shared by `UrlUtils` and `IntentUtils`
/// (`ACCEPTED_URI_SCHEMA`): `(?i)((?:http|https|file):// |
/// (?:inline|data|about|javascript): | (?:.*:.*@))(.*)`.
#[uniffi::export]
pub fn accepted_uri_scheme(url: String) -> bool {
    accepted_scheme_end(&url).is_some()
}

/// Length of the regex's group 1 — the part Kotlin lowercases.
fn accepted_scheme_end(s: &str) -> Option<usize> {
    const PREFIXES: &[&str] = &[
        "http://",
        "https://",
        "file://",
        "inline:",
        "data:",
        "about:",
        "javascript:",
        "arktos:",
    ];
    for prefix in PREFIXES {
        if s
            .get(..prefix.len())
            .is_some_and(|head| head.eq_ignore_ascii_case(prefix))
        {
            return Some(prefix.len());
        }
    }
    // `.*:.*@` is greedy: group 1 runs through the LAST '@' in the
    // string, provided some ':' precedes it.
    let at = s.rfind('@')?;
    if s[..at].contains(':') {
        Some(at + 1)
    } else {
        None
    }
}

/// `BrowserPresenter.isIntentUrlAllowed` — allowlist the schemes an
/// incoming VIEW intent may hand to the browser.
#[uniffi::export]
pub fn is_intent_url_allowed(url: String) -> bool {
    const INTENT_URL_SCHEMES: &[&str] =
        &["http", "https", "file", "about", "inline", "content", "arktos"];
    let scheme = url.split(':').next().unwrap_or_default().to_lowercase();
    INTENT_URL_SCHEMES.contains(&scheme.as_str())
}

/// `URLUtil.composeSearchUrl` — substitute the placeholder with the
/// form-urlencoded query. Empty string when the placeholder is absent
/// (the Java original returned null there).
#[uniffi::export]
pub fn compose_search_url(query: String, template: String, placeholder: String) -> String {
    match template.find(&placeholder) {
        Some(i) => format!(
            "{}{}{}",
            &template[..i],
            form_url_encode(&query),
            &template[i + placeholder.len()..]
        ),
        None => String::new(),
    }
}

/// `java.net.URLEncoder.encode(s, "UTF-8")`: alphanumerics and
/// `.-*_` stay literal, space becomes `+`, everything else is
/// percent-encoded by UTF-8 byte.
fn form_url_encode(s: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut out = String::with_capacity(s.len());
    for &b in s.as_bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => {
                out.push(b as char)
            }
            b' ' => out.push('+'),
            _ => {
                out.push('%');
                out.push(HEX[(b >> 4) as usize] as char);
                out.push(HEX[(b & 0xf) as usize] as char);
            }
        }
    }
    out
}

/// `Patterns.WEB_URL` approximation for the scheme-less (or
/// http/https/rtsp-schemed) inputs `smart_url_filter` sees:
/// `[scheme://][userinfo@]host[:port][/path]` where host is a dotted
/// name chain ending in an alpha TLD (the AOSP pattern's TLD
/// alternation is lowercase literals, so an UPPERCASE TLD rejects —
/// quirk preserved), an IPv4 literal, or `localhost`.
fn looks_like_web_url(s: &str) -> bool {
    let mut rest = s;
    // WEB_URL's scheme alternation is literally
    // (http|https|Http|Https|rtsp|Rtsp) — case-sensitive.
    for scheme in ["http://", "https://", "Http://", "Https://", "rtsp://", "Rtsp://"] {
        if let Some(tail) = rest.strip_prefix(scheme) {
            rest = tail;
            break;
        }
    }
    let auth_end = rest
        .find(|c| c == '/' || c == '?' || c == '#')
        .unwrap_or(rest.len());
    if !valid_web_path(&rest[auth_end..]) {
        return false;
    }
    let authority = &rest[..auth_end];
    let hostport = match authority.rfind('@') {
        Some(at) => &authority[at + 1..],
        None => authority,
    };
    let host = match hostport.rfind(':') {
        Some(i) => {
            let port = &hostport[i + 1..];
            if port.len() > 5 || !port.bytes().all(|b| b.is_ascii_digit()) {
                return false;
            }
            &hostport[..i]
        }
        None => hostport,
    };
    valid_web_host(host)
}

fn valid_web_host(host: &str) -> bool {
    if host.is_empty() {
        return false;
    }
    if host.eq_ignore_ascii_case("localhost") {
        return true;
    }
    if is_ipv4_literal(host) {
        return true;
    }
    let mut labels = host.split('.');
    let tld = labels.next_back().unwrap_or_default();
    // TLD alternation ≈ lowercase-alpha >= 2 chars or an IDN A-label.
    let tld_ok = (tld.len() >= 2 && tld.bytes().all(|b| b.is_ascii_lowercase()))
        || tld.get(..4).is_some_and(|p| p.eq_ignore_ascii_case("xn--"));
    if !tld_ok || labels.clone().next().is_none() {
        return false;
    }
    labels.all(|label| {
        !label.is_empty()
            && label.len() <= 65
            && label.as_bytes()[0].is_ascii_alphanumeric()
            && label
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-')
    })
}

fn is_ipv4_literal(host: &str) -> bool {
    let parts: Vec<&str> = host.split('.').collect();
    parts.len() == 4
        && parts.iter().all(|p| {
            !p.is_empty()
                && p.len() <= 3
                && p.bytes().all(|b| b.is_ascii_digit())
                && p.parse::<u16>().is_ok_and(|v| v <= 255)
        })
}

/// WEB_URL's path/query/fragment char set — the AOSP pattern's
/// `(/…)?` tail with `%XX` escapes accepted leniently.
fn valid_web_path(path: &str) -> bool {
    path.bytes().all(|b| {
        b.is_ascii_alphanumeric() || b";/?:@&=#~-.+!*'(),_%\\^`|{}[]".contains(&b)
    })
}

/// `URLUtil.guessUrl` — reached only after [`looks_like_web_url`]
/// passed: keep schemed input, else prepend `http://`; a dotless host
/// (e.g. `localhost`) is expanded to `www.<host>.com` like
/// `WebAddress` does — quirk preserved.
fn guess_url(in_url: &str) -> String {
    if in_url.is_empty() {
        return String::new();
    }
    for prefix in ["about:", "data:", "file:", "javascript:"] {
        if in_url.starts_with(prefix) {
            return in_url.to_owned();
        }
    }
    let trimmed = in_url.strip_suffix('.').unwrap_or(in_url);

    // WebAddress accepts only these scheme spellings; anything else
    // throws ParseException → "" upstream.
    let (scheme, rest) = match trimmed.split_once("://") {
        Some((scheme, rest)) => match scheme {
            "http" | "HTTP" | "https" | "HTTPS" | "file" | "FILE" => (scheme, rest),
            _ => return String::new(),
        },
        None => ("http", trimmed),
    };

    let auth_end = rest.find('/').unwrap_or(rest.len());
    let authority = &rest[..auth_end];
    let path = &rest[auth_end..];
    let (userinfo, hostport) = match authority.rfind('@') {
        Some(at) => (&authority[..at + 1], &authority[at + 1..]),
        None => ("", authority),
    };
    let (host, port) = match hostport.rfind(':') {
        Some(i) => {
            let digits = &hostport[i + 1..];
            if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
                return String::new();
            }
            (&hostport[..i], &hostport[i..])
        }
        None => (hostport, ""),
    };
    if host.is_empty() {
        return String::new();
    }
    let host = if host.contains('.') {
        host.to_owned()
    } else {
        format!("www.{host}.com")
    };
    format!("{scheme}://{userinfo}{host}{port}{path}")
}

/// `Utils.getDomainName` — display-only domain extraction: truncate at
/// the first `/` at index ≥ 8, take the authority host, keep the
/// `https://` prefix on SSL URLs, and strip `www.` otherwise.
#[uniffi::export]
pub fn domain_name(url: String) -> String {
    if url.is_empty() {
        return String::new();
    }
    let ssl = url.len() >= 8 && url.as_bytes()[..8].eq_ignore_ascii_case(b"https://");
    let truncated = match url.as_bytes()[8.min(url.len())..]
        .iter()
        .position(|&b| b == b'/')
    {
        Some(pos) => &url[..8 + pos],
        None => url.as_str(),
    };
    match uri_host(truncated) {
        Some(host) if !host.is_empty() => {
            if ssl {
                format!("https://{host}")
            } else {
                host.strip_prefix("www.").unwrap_or(&host).to_owned()
            }
        }
        _ => truncated.to_owned(),
    }
}

/// `java.net.URI.getHost()` approximation: requires `scheme://`,
/// takes the authority up to the next `/`, drops userinfo and the
/// port, brackets for IPv6. Returns `None` for registry-authority or
/// relative input (host chars outside `[a-zA-Z0-9.\-]` fail), mirroring
/// the Kotlin fallback to the truncated URL.
fn uri_host(uri: &str) -> Option<String> {
    let (_, rest) = uri.split_once("://")?;
    let authority = rest.split('/').next()?;
    let hostport = authority.rsplit('@').next()?;
    let host = if let Some(inner) = hostport.strip_prefix('[') {
        match inner.find(']') {
            Some(end) => &inner[..end],
            None => return None,
        }
    } else {
        hostport.split(':').next()?
    };
    if host.is_empty()
        || !host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b':')
    {
        return None;
    }
    Some(host.to_lowercase())
}

/// `String?.isSpecialUrl` — the generated in-app pages (bookmarks,
/// downloads, history, home, incognito) live under `file://…`.
#[uniffi::export]
pub fn is_special_url(url: String) -> bool {
    is_internal_page(&url, &[
        BOOKMARK_PAGE,
        DOWNLOADS_PAGE,
        HISTORY_PAGE,
        HOME_PAGE,
        INCOGNITO_PAGE,
        CHANGELOG_PAGE,
    ]) || is_changelog_page(&url)
}

/// `String?.isChangelogUrl` — true for the canonical
/// `arktos://changelog` URL and for the bundled asset path
/// (`file://…/changelog.html`), e.g. when the page was opened
/// through a file intent (CHLOG01).
#[uniffi::export]
pub fn is_changelog_url(url: String) -> bool {
    is_changelog_page(&url) || is_internal_page(&url, &[CHANGELOG_PAGE])
}

fn is_changelog_page(url: &str) -> bool {
    url == CHANGELOG_URL || url == "arktos://changelog/"
}

/// Asset name inside `arktos://changelog/<name>` — the bundled files
/// the changelog page references (aurora wave, logo). Returns "" when
/// the URL is not a changelog asset request; the name must be a single
/// flat filename (`[A-Za-z0-9._-]+`, no separators, not `.`/`..`) so
/// the interceptor can only ever serve bundled asset files.
#[uniffi::export]
pub fn changelog_asset_name(url: String) -> String {
    match url.strip_prefix("arktos://changelog/") {
        Some(name)
            if name != "."
                && name != ".."
                && !name.is_empty()
                && name.bytes().all(|b| {
                    b.is_ascii_alphanumeric() || b == b'.' || b == b'_' || b == b'-'
                }) =>
        {
            name.to_owned()
        }
        _ => String::new(),
    }
}

/// `String?.isBookmarkUrl`.
#[uniffi::export]
pub fn is_bookmark_url(url: String) -> bool {
    is_internal_page(&url, &[BOOKMARK_PAGE])
}

/// `String?.isDownloadsUrl`.
#[uniffi::export]
pub fn is_downloads_url(url: String) -> bool {
    is_internal_page(&url, &[DOWNLOADS_PAGE])
}

/// `String?.isHistoryUrl`.
#[uniffi::export]
pub fn is_history_url(url: String) -> bool {
    is_internal_page(&url, &[HISTORY_PAGE])
}

/// `String?.isStartPageUrl` — the bundled homepage.
#[uniffi::export]
pub fn is_start_page_url(url: String) -> bool {
    is_internal_page(&url, &[HOME_PAGE])
}

fn is_internal_page(url: &str, names: &[&str]) -> bool {
    url.starts_with(FILE_SCHEME) && names.iter().any(|name| url.ends_with(name))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn smart_filter_keeps_schemed_urls() {
        assert_eq!(
            smart_url_filter("HTTP://Example.COM/x".into(), true, "s".into()),
            "http://Example.COM/x"
        );
        assert_eq!(
            smart_url_filter("  https://a.com/?q=1  ".into(), true, "s".into()),
            "https://a.com/?q=1"
        );
        assert_eq!(
            smart_url_filter("about:blank".into(), true, "s".into()),
            "about:blank"
        );
        // user@host authority form hits the `.*:.*@` alternative.
        assert_eq!(
            smart_url_filter("FOO:user@host/path".into(), true, "s".into()),
            "foo:user@host/path"
        );
    }

    #[test]
    fn smart_filter_space_branch_is_dead_code() {
        // WEB_URL can't match an input containing a space — upstream's
        // `%20` replacement never fires; the schemed URL returns as-is.
        assert_eq!(
            smart_url_filter("http://a.com/x y".into(), true, "s".into()),
            "http://a.com/x y"
        );
    }

    #[test]
    fn smart_filter_guesses_bare_hosts() {
        assert_eq!(
            smart_url_filter("example.com/path".into(), true, "s".into()),
            "http://example.com/path"
        );
        assert_eq!(
            smart_url_filter("192.168.0.1:8080/x".into(), true, "s".into()),
            "http://192.168.0.1:8080/x"
        );
        // WEB_URL matches localhost; guessUrl's WebAddress expands the
        // dotless host — the www./.com quirk is preserved.
        assert_eq!(
            smart_url_filter("localhost:8000".into(), true, "s".into()),
            "http://www.localhost.com:8000"
        );
        assert_eq!(
            smart_url_filter("localhost".into(), true, "s".into()),
            "http://www.localhost.com"
        );
    }

    #[test]
    fn smart_filter_falls_back_to_search() {
        assert_eq!(
            smart_url_filter("hello world".into(), true, "https://s.com/?q=%s".into()),
            "https://s.com/?q=hello+world"
        );
        assert_eq!(
            smart_url_filter("hello world".into(), false, "https://s.com/?q=%s".into()),
            ""
        );
        // Uppercase TLD rejects the WEB_URL TLD alternation → search.
        assert_eq!(
            smart_url_filter("EXAMPLE.COM".into(), true, "https://s.com/?q=%s".into()),
            "https://s.com/?q=EXAMPLE.COM"
        );
        // No placeholder → "" (Kotlin returned null → NPE).
        assert_eq!(
            smart_url_filter("nope".into(), true, "https://s.com/".into()),
            ""
        );
    }

    #[test]
    fn compose_search_url_encodes_form_style() {
        assert_eq!(
            compose_search_url("a+b &c".into(), "https://d.com/?q=%s&x=1".into(), "%s".into()),
            "https://d.com/?q=a%2Bb+%26c&x=1"
        );
        assert_eq!(
            compose_search_url("üñí".into(), "t=%s".into(), "%s".into()),
            "t=%C3%BC%C3%B1%C3%AD"
        );
        assert_eq!(
            compose_search_url("q".into(), "no-token".into(), "%s".into()),
            ""
        );
    }

    #[test]
    fn accepted_scheme_covers_shared_regex() {
        for url in [
            "http://a.com",
            "HTTPS://a.com",
            "file:///x",
            "inline:app",
            "data:text/plain,x",
            "about:blank",
            "javascript:void(0)",
            "foo:bar@host/x",
        ] {
            assert!(accepted_uri_scheme(url.into()), "expected match: {url}");
        }
        for url in ["example.com", "just text", "tel:+1", "a@b:c"] {
            assert!(!accepted_uri_scheme(url.into()), "expected no match: {url}");
        }
    }

    #[test]
    fn intent_schemes_allowlisted() {
        for url in [
            "https://a.com",
            "HTTP://a.com",
            "file:///x",
            "about:blank",
            "inline:y",
            "content://z",
        ] {
            assert!(is_intent_url_allowed(url.into()), "allowed: {url}");
        }
        for url in [
            "javascript:alert(1)",
            "data:text/html,x",
            "vbscript:x",
            "intent://i#Intent;end",
            "tel:+1",
            "example.com/path",
            "just-text",
        ] {
            assert!(!is_intent_url_allowed(url.into()), "rejected: {url}");
        }
    }

    #[test]
    fn domain_name_parity() {
        assert_eq!(domain_name("".into()), "");
        assert_eq!(
            domain_name("http://example.com/path?q=1".into()),
            "example.com"
        );
        assert_eq!(
            domain_name("http://www.example.com/".into()),
            "example.com"
        );
        // SSL keeps the scheme AND the www. (upstream quirk).
        assert_eq!(
            domain_name("https://www.example.com/x".into()),
            "https://www.example.com"
        );
        assert_eq!(domain_name("https://example.com/x".into()), "https://example.com");
        // Unparseable → truncated input, mirroring the URISyntaxException path.
        assert_eq!(domain_name("notaurl".into()), "notaurl");
        assert_eq!(
            domain_name("http://user@a.b.com:81/p".into()),
            "a.b.com"
        );
    }

    #[test]
    fn internal_pages_detected() {
        let files = "file:///data/bookmark.html";
        assert!(is_bookmark_url(files.into()));
        assert!(is_special_url(files.into()));
        assert!(!is_history_url(files.into()));
        assert!(is_history_url("file:///x/history.html".into()));
        assert!(is_downloads_url("file:///x/downloads.html".into()));
        assert!(is_start_page_url("file:///x/homepage.html".into()));
        assert!(is_special_url("file:///x/private.html".into()));
        assert!(!is_special_url("https://a.com/bookmark.html".into()));
        assert!(!is_special_url("".into()));
    }

    #[test]
    fn changelog_url_is_special() {
        // CHLOG01 — the in-app changelog is an internal page in both
        // URL forms: the arktos:// chrome URL and the bundled asset.
        assert!(is_special_url("arktos://changelog".into()));
        assert!(is_special_url("arktos://changelog/".into()));
        assert!(is_special_url("file:///android_asset/changelog.html".into()));
        assert!(is_changelog_url("arktos://changelog".into()));
        assert!(is_changelog_url("file:///android_asset/changelog.html".into()));
        assert!(!is_changelog_url("arktos://changelog/aurora-wave.svg".into()));
        assert!(!is_changelog_url("https://a.com/changelog.html".into()));
        assert!(!is_changelog_url("arktos://settings".into()));
        assert!(!is_special_url("arktos://changelog/aurora-wave.svg".into()));
    }

    #[test]
    fn changelog_assets_are_flat_names() {
        assert_eq!(
            changelog_asset_name("arktos://changelog/aurora-wave.svg".into()),
            "aurora-wave.svg"
        );
        assert_eq!(
            changelog_asset_name("arktos://changelog/arktos-plain.svg".into()),
            "arktos-plain.svg"
        );
        for url in [
            "arktos://changelog/",
            "arktos://changelog/../secret",
            "arktos://changelog/..",
            "arktos://changelog/.",
            "arktos://changelog/a/b.svg",
            "arktos://changelog/a\\b.svg",
            "arktos://changelog/%2e%2e/x",
            "https://a.com/changelog/x.svg",
            "arktos://changelog",
        ] {
            assert_eq!(changelog_asset_name(url.into()), "", "rejected: {url}");
        }
    }
}
