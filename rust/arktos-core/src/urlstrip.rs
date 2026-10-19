//! ClearURLs-style tracking-parameter stripping, ported from the
//! desktop core (`rustcore/src/urlstrip.rs`).  The vendored ruleset
//! drives a pure string-surgery strip: kept query segments pass
//! through byte-for-byte, so percent-encoding and ordering survive
//! and only the matched `name=value` pairs leave the URL.
//!
//! Ruleset format (`data/urlstrip-rules.json`):
//! ```json
//! {
//!   "version": 1,
//!   "params": ["utm_*", "gclid"],          // trailing '*' = prefix
//!   "scoped": [                            // params that are trackers
//!     {"hosts": ["amazon.*"],              //   only on the listed
//!      "params": ["ref_*", "tag"]}         //   domains
//!   ],
//!   "unwrap": [                            // redirector rules — the
//!     {"hosts": ["google.*"],              //   named param's value
//!      "path": "/url", "param": "q"}       //   decodes to the real
//!   ],                                     //   destination
//!   "exceptions": [
//!     {"host": "ex.com"},                  // no cleaning on ex.com or *.ex.com
//!     {"host": "a.com", "keep": ["si"]}    // all params stripped except "si"
//!   ]
//! }
//! ```
//! Unlike the desktop module there is no data-dir override or reload —
//! the vendored ruleset is the only source, parsed lazily once.

use serde_json::Value;
use std::sync::OnceLock;

/// Vendored default, compiled in.  Edits ride the same review as code.
const BUILTIN_RULES: &str = include_str!("../data/urlstrip-rules.json");

/// Query-parameter name matcher.  A trailing `*` in the rule text is
/// a prefix match ("utm_*" catches utm_source, utm_anything); anything
/// else is an exact name so "si" cannot eat "simple".
#[derive(Debug)]
enum Pattern {
    Exact(String),
    Prefix(String),
}

impl Pattern {
    fn compile(rule: &str) -> Option<Pattern> {
        let rule = rule.trim().to_lowercase();
        if rule.is_empty() {
            return None;
        }
        Some(match rule.strip_suffix('*') {
            Some(prefix) => Pattern::Prefix(prefix.to_owned()),
            None => Pattern::Exact(rule),
        })
    }

    fn matches(&self, name: &str) -> bool {
        match self {
            Pattern::Exact(n) => name == n,
            Pattern::Prefix(p) => name.starts_with(p.as_str()),
        }
    }
}

/// Host matcher for scoped/unwrap rules.  "example.com" matches the
/// host itself or any subdomain; a trailing ".*" is a TLD wildcard —
/// "amazon.*" matches wherever the base appears as a whole label run
/// followed by a dot (amazon.com, www.amazon.co.uk).  Deliberately
/// heuristic: no public-suffix list, so "amazon.*" also catches
/// lookalike hosts like amazon.com.evil.example — stripping a known
/// tracker name there is harmless.
#[derive(Debug)]
enum HostPattern {
    Suffix(String),
    TldWildcard(String),
}

impl HostPattern {
    fn compile(rule: &str) -> Option<HostPattern> {
        let rule = rule.trim().to_lowercase();
        if rule.is_empty() {
            return None;
        }
        if let Some(base) = rule.strip_suffix(".*") {
            if base.is_empty() || base.contains('*') {
                return None;
            }
            return Some(HostPattern::TldWildcard(base.to_owned()));
        }
        Some(HostPattern::Suffix(rule))
    }

    fn matches(&self, host: &str) -> bool {
        match self {
            HostPattern::Suffix(s) => {
                host == s.as_str() || host.ends_with(&format!(".{s}"))
            }
            HostPattern::TldWildcard(base) => {
                host.starts_with(&format!("{base}."))
                    || host.contains(&format!(".{base}."))
            }
        }
    }
}

/// Params that are trackers only on the listed domains — "ref" is an
/// Amazon affiliate id but a functional parameter on countless other
/// sites, so it only strips where scoped.
#[derive(Debug)]
struct ScopedParams {
    hosts: Vec<HostPattern>,
    params: Vec<Pattern>,
}

/// A redirector rule — on a matching host+path the named query
/// parameter's percent-decoded value is the real destination.  The
/// whole original URL is replaced by that target.
#[derive(Debug)]
struct UnwrapRule {
    hosts: Vec<HostPattern>,
    path: String,
    param: Pattern,
}

/// Per-site carve-out: an empty keep list exempts the host from
/// stripping entirely; a non-empty list names params that survive
/// while the rest still strip.
#[derive(Debug)]
struct Exception {
    host: String,
    keep: Vec<Pattern>,
}

#[derive(Debug)]
struct RuleSet {
    params: Vec<Pattern>,
    scoped: Vec<ScopedParams>,
    unwrap: Vec<UnwrapRule>,
    exceptions: Vec<Exception>,
}

fn parse_patterns(v: &Value) -> Vec<Pattern> {
    v.as_array()
        .map(|a| a.iter().filter_map(Value::as_str).filter_map(Pattern::compile).collect())
        .unwrap_or_default()
}

fn parse_rules(text: &str) -> Result<RuleSet, String> {
    let doc: Value = serde_json::from_str(text)
        .map_err(|e| format!("urlstrip rules: {e}"))?;
    if let Some(version) = doc.get("version") {
        // A newer schema must fail loudly rather than be half-read.
        if version.as_i64() != Some(1) {
            return Err(format!("urlstrip rules: unsupported version {version}"));
        }
    }
    let params = parse_patterns(doc.get("params").unwrap_or(&Value::Null));
    let parse_hosts = |v: Option<&Value>| -> Vec<HostPattern> {
        v.and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(Value::as_str)
                    .filter_map(HostPattern::compile)
                    .collect()
            })
            .unwrap_or_default()
    };
    let mut scoped = Vec::new();
    if let Some(list) = doc.get("scoped").and_then(Value::as_array) {
        for entry in list {
            let hosts = parse_hosts(entry.get("hosts"));
            let params = parse_patterns(entry.get("params").unwrap_or(&Value::Null));
            if hosts.is_empty() || params.is_empty() {
                continue;
            }
            scoped.push(ScopedParams { hosts, params });
        }
    }
    let mut unwrap = Vec::new();
    if let Some(list) = doc.get("unwrap").and_then(Value::as_array) {
        for entry in list {
            // "hosts" is a list; "host" is a single-value shorthand.
            let mut hosts = parse_hosts(entry.get("hosts"));
            if let Some(single) = entry.get("host").and_then(Value::as_str) {
                hosts.extend(HostPattern::compile(single));
            }
            let param = entry
                .get("param")
                .and_then(Value::as_str)
                .and_then(Pattern::compile);
            let (Some(param), false) = (param, hosts.is_empty()) else {
                continue;
            };
            let path = entry
                .get("path")
                .and_then(Value::as_str)
                .map(|p| p.trim().to_lowercase())
                .unwrap_or_default();
            unwrap.push(UnwrapRule { hosts, path, param });
        }
    }
    let mut exceptions = Vec::new();
    if let Some(list) = doc.get("exceptions").and_then(Value::as_array) {
        for ex in list {
            let host = ex
                .get("host")
                .and_then(Value::as_str)
                .map(|h| h.trim().to_lowercase())
                .unwrap_or_default();
            if host.is_empty() {
                continue;
            }
            let keep = ex
                .get("keep")
                .map(parse_patterns)
                .unwrap_or_default();
            exceptions.push(Exception { host, keep });
        }
    }
    Ok(RuleSet {
        params,
        scoped,
        unwrap,
        exceptions,
    })
}

/// The vendored ruleset, parsed once.  An unparsable builtin would
/// mean a broken build, not a runtime event — hence expect().
fn builtin_rules() -> &'static RuleSet {
    static RULES: OnceLock<RuleSet> = OnceLock::new();
    RULES.get_or_init(|| {
        parse_rules(BUILTIN_RULES).expect("vendored urlstrip rules must parse")
    })
}

/// Bound on chained unwrapping — a real chain like l.facebook.com ->
/// t.co still resolves while a self-referential rule pair cannot
/// loop.
const MAX_UNWRAP_HOPS: usize = 3;

fn clean_url(rules: &RuleSet, url: &str) -> Option<String> {
    let sep = url.find("://")?;
    let scheme = &url[..sep];
    if !scheme.eq_ignore_ascii_case("http") && !scheme.eq_ignore_ascii_case("https") {
        return None;
    }

    // The fragment swallows everything after '#', so a '?' behind it
    // is not a query — and without a query nothing here can fire.
    let frag = url.find('#');
    let qpos = url.find('?')?;
    if frag.is_some_and(|f| qpos > f) {
        return None;
    }

    let host = url_host(url);
    if let Some(ex) = exception_for(rules, &host) {
        if ex.keep.is_empty() {
            return None; // fully exempt — cleaning breaks this site
        }
    }

    let mut current = url.to_string();
    for _ in 0..MAX_UNWRAP_HOPS {
        let Some(target) = try_unwrap(rules, &current) else {
            break;
        };
        // A fully-excepted destination keeps the redirector hop.
        let target_host = url_host(&target);
        if exception_for(rules, &target_host)
            .is_some_and(|ex| ex.keep.is_empty())
        {
            break;
        }
        current = target;
    }
    let unwrapped = current != url;

    // Parameter stripping runs under the FINAL host's rules — an
    // unwrapped destination's own keep-list and scoped rules apply.
    let host = url_host(&current);
    let keep: &[Pattern] = exception_for(rules, &host)
        .map(|ex| ex.keep.as_slice())
        .unwrap_or(&[]);
    let cfrag = current.find('#');
    let cqpos = match current.find('?') {
        Some(q) if cfrag.is_none_or(|f| q < f) => q,
        _ => {
            return if unwrapped { Some(current) } else { None };
        }
    };
    let stripped = strip_query(rules, &current, &host, cqpos, cfrag, keep)
        .unwrap_or(current);
    if stripped == url {
        None
    } else {
        Some(stripped)
    }
}

/// The bare host of an http(s) URL: authority minus userinfo/port,
/// IPv6 brackets dropped, lowercased.
fn url_host(url: &str) -> String {
    let auth_start = match url.find("://") {
        Some(i) => i + 3,
        None => return String::new(),
    };
    let auth_end = url[auth_start..]
        .find(['/', '?', '#'])
        .map(|i| auth_start + i)
        .unwrap_or(url.len());
    let authority = &url[auth_start..auth_end];
    let hostport = authority.rsplit('@').next().unwrap_or("");
    if hostport.starts_with('[') {
        hostport
            .find(']')
            .map(|i| hostport[1..i].to_lowercase())
            .unwrap_or_else(|| hostport.to_lowercase())
    } else {
        hostport
            .split(':')
            .next()
            .unwrap_or("")
            .to_lowercase()
    }
}

/// The exception row governing a host, if any (exact or parent
/// suffix match).
fn exception_for<'a>(rules: &'a RuleSet, host: &str) -> Option<&'a Exception> {
    rules
        .exceptions
        .iter()
        .find(|ex| host == ex.host || host.ends_with(&format!(".{}", ex.host)))
}

/// The path portion of an http(s) URL — between the authority and the
/// '?'/'#' boundary — for unwrap-rule matching.
fn url_path(url: &str) -> &str {
    let auth_start = match url.find("://") {
        Some(i) => i + 3,
        None => return "",
    };
    let rest = &url[auth_start..];
    let start = match rest.find('/') {
        Some(i) => auth_start + i,
        None => return "",
    };
    let end = url[start..]
        .find(['?', '#'])
        .map(|i| start + i)
        .unwrap_or(url.len());
    &url[start..end]
}

/// Rule path match: empty or "/" constrains nothing; "/x" matches
/// "/x" and "/x/..."; a trailing-slash rule is a strict prefix.
fn path_matches(path: &str, rule_path: &str) -> bool {
    let prefix = rule_path.trim_end_matches('/');
    if prefix.is_empty() {
        return true;
    }
    path == prefix
        || (rule_path.ends_with('/') && path.starts_with(rule_path))
        || (path.starts_with(prefix)
            && path.as_bytes().get(prefix.len()) == Some(&b'/'))
}

/// Absolute http(s) URL without whitespace/control bytes — anything
/// else is not a safe redirect target.
fn is_web_target(s: &str) -> bool {
    (s.get(..7).is_some_and(|p| p.eq_ignore_ascii_case("http://"))
        || s.get(..8).is_some_and(|p| p.eq_ignore_ascii_case("https://")))
        && !s.bytes().any(|b| b <= b' ')
}

/// Redirect unwrapping — when a rule's host+path match and the named
/// param percent-decodes (once, or twice for double-encoded wrappers)
/// to an absolute http(s) URL, that URL replaces the whole request.
/// The first matching rule wins.
fn try_unwrap(rules: &RuleSet, url: &str) -> Option<String> {
    if rules.unwrap.is_empty() {
        return None;
    }
    let host = url_host(url);
    if host.is_empty() {
        return None;
    }
    let path = url_path(url);
    let frag = url.find('#');
    let qpos = url.find('?')?;
    if frag.is_some_and(|f| qpos > f) {
        return None;
    }
    let query = &url[qpos + 1..frag.unwrap_or(url.len())];
    for rule in &rules.unwrap {
        if !rule.hosts.iter().any(|h| h.matches(&host)) {
            continue;
        }
        if !path_matches(path, &rule.path) {
            continue;
        }
        for segment in query.split('&') {
            let Some((raw_name, raw_value)) = segment.split_once('=') else {
                continue;
            };
            if !rule.param.matches(&decode_name(raw_name).to_lowercase()) {
                continue;
            }
            let decoded = decode_value(raw_value);
            if is_web_target(&decoded) {
                return Some(decoded);
            }
            // Double-encoded wrappers nest a second percent pass —
            // one more decode, still http(s) only.
            let twice = decode_value(&decoded);
            if twice != decoded && is_web_target(&twice) {
                return Some(twice);
            }
        }
    }
    None
}

/// Percent-decode for an embedded URL VALUE: '%XX' only — '+' is a
/// literal plus inside a nested URL, not a space.
fn decode_value(raw: &str) -> String {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                match u8::from_str_radix(&raw[i + 1..i + 3], 16) {
                    Ok(v) => {
                        out.push(v);
                        i += 3;
                    }
                    Err(_) => {
                        out.push(b'%');
                        i += 1;
                    }
                }
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn strip_query(
    rules: &RuleSet,
    url: &str,
    host: &str,
    qpos: usize,
    frag: Option<usize>,
    keep: &[Pattern],
) -> Option<String> {
    let qend = frag.unwrap_or(url.len());
    let query = &url[qpos + 1..qend];
    let mut kept: Vec<&str> = Vec::with_capacity(query.len() / 8 + 1);
    for segment in query.split('&') {
        let raw_name = segment.split('=').next().unwrap_or("");
        let name = decode_name(raw_name).to_lowercase();
        // Global params strip everywhere; scoped params only on the
        // domains where they are trackers; a keep list wins over both.
        let strippable = (rules.params.iter().any(|p| p.matches(&name))
            || rules.scoped.iter().any(|s| {
                s.hosts.iter().any(|h| h.matches(host))
                    && s.params.iter().any(|p| p.matches(&name))
            }))
            && !keep.iter().any(|p| p.matches(&name));
        if !strippable {
            kept.push(segment);
        }
    }
    if kept.len() == query.split('&').count() {
        return None;
    }
    let tail = frag.map(|f| &url[f..]).unwrap_or("");
    let mut out = String::with_capacity(url.len());
    out.push_str(&url[..qpos]);
    if !kept.is_empty() {
        out.push('?');
        out.push_str(&kept.join("&"));
    }
    out.push_str(tail);
    Some(out)
}

/// Percent-decode a raw query name for matching (`+` folds to space
/// like form decoding).  Undecodable sequences pass through — they
/// simply match nothing.
fn decode_name(raw: &str) -> String {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                let hex = &raw[i + 1..i + 3];
                match u8::from_str_radix(hex, 16) {
                    Ok(v) => {
                        out.push(v);
                        i += 3;
                    }
                    Err(_) => {
                        out.push(b'%');
                        i += 1;
                    }
                }
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// `strip_url`: the UniFFI-facing entry — the cleaned URL, or the
/// input verbatim when no rule fired (or the URL is not http/https).
/// Never fails on content — a malformed URL simply returns unchanged.
#[uniffi::export]
pub fn strip_url(url: String) -> String {
    clean_url(builtin_rules(), &url).unwrap_or(url)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rules() -> RuleSet {
        parse_rules(BUILTIN_RULES).expect("vendored rules parse")
    }

    fn strip(r: &RuleSet, url: &str) -> Option<String> {
        clean_url(r, url)
    }

    #[test]
    fn builtin_rules_parse() {
        let r = rules();
        assert!(!r.params.is_empty());
        assert!(!r.scoped.is_empty());
        assert!(!r.unwrap.is_empty());
    }

    #[test]
    fn strips_and_preserves() {
        let r = rules();
        assert_eq!(
            strip(&r, "https://a.com/?utm_source=x&real=1").as_deref(),
            Some("https://a.com/?real=1")
        );
        assert_eq!(
            strip(&r, "https://a.com/p?utm_source=x&utm_medium=y").as_deref(),
            Some("https://a.com/p")
        );
        assert_eq!(
            strip(&r, "https://a.com/?b=1&utm_source=x&a=2#f").as_deref(),
            Some("https://a.com/?b=1&a=2#f")
        );
        assert_eq!(strip(&r, "https://a.com/#frag?utm_source=x"), None);
        assert_eq!(strip(&r, "ftp://a.com/f?utm_source=x"), None);
        assert_eq!(strip(&r, "data:text/plain,utm_source=x"), None);
        assert_eq!(strip(&r, "https://a.com/"), None);
        assert_eq!(
            strip(&r, "https://a.com/?id=42&ok=1"),
            None
        );
        assert_eq!(
            strip(&r, "https://u:p@a.com:8443/?gclid=1&ok=1").as_deref(),
            Some("https://u:p@a.com:8443/?ok=1")
        );
    }

    #[test]
    fn prefix_params_strip_the_family_only() {
        let r = rules();
        // "utm_*" is a prefix rule — utm_source drops, the unrelated
        // "utmost" name survives.
        assert_eq!(
            strip(&r, "https://a.com/?utm_campaign=c&utmost=1").as_deref(),
            Some("https://a.com/?utmost=1")
        );
    }

    #[test]
    fn scoped_params_strip_only_where_listed() {
        let r = rules();
        // amazon-scoped "ref_*" strips on amazon hosts…
        assert_eq!(
            strip(&r, "https://www.amazon.com/dp/B00TEST?ref_=sr_1_1&th=1&psc=1")
                .as_deref(),
            Some("https://www.amazon.com/dp/B00TEST?psc=1")
        );
        // …but not elsewhere, where "ref" is functional.
        assert_eq!(strip(&r, "https://blog.example/?ref=nav"), None);
        // The TLD wildcard covers amazon.co.uk.
        assert_eq!(
            strip(&r, "https://www.amazon.co.uk/dp/B00TEST?ref_=sr_1_1").as_deref(),
            Some("https://www.amazon.co.uk/dp/B00TEST")
        );
    }

    #[test]
    fn unwrap_rules_resolve_redirectors() {
        let r = rules();
        assert_eq!(
            strip(&r, "https://www.google.com/url?q=https%3A%2F%2Freal.example%2Fp%3Fa%3D1&sa=D&ved=x")
                .as_deref(),
            Some("https://real.example/p?a=1")
        );
        // Non-http(s) targets are never adopted.
        assert_eq!(
            strip(&r, "https://www.google.com/url?q=javascript%3Aalert(1)"),
            None
        );
        // Rules scope to host+path — a q param elsewhere is untouched.
        assert_eq!(
            strip(&r, "https://www.google.com/search?q=cats"),
            None
        );
    }

    #[test]
    fn ffi_entry_never_fails() {
        assert_eq!(
            strip_url("https://x/?utm_source=a&real=1".into()),
            "https://x/?real=1"
        );
        assert_eq!(strip_url("not a url".into()), "not a url");
        assert_eq!(strip_url(String::new()), "");
    }
}
