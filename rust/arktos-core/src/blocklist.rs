//! Unified blocklist engine (BLOCK01).
//!
//! One engine behind UniFFI replacing the three parallel Kotlin
//! blocklists that used to live in `SmartCookieWebClient`:
//!
//! * ads — hosts-file domains (`hosts.txt` et al.), exact-match on the
//!   request domain, parity with `HostsFileParser` /
//!   `BloomFilterAdBlocker.getDomainName` (no MurmurHash parity needed —
//!   the filter is rebuilt at load).
//! * malware — `malware.txt` comma-separated terms, substring match on
//!   the main-frame URL (aho-corasick, single pass).
//! * site block — parental `siteBlockNames` list, substring match with
//!   the upstream whitelist/blacklist mode semantics.
//! * LAN — upstream SmartCookieWeb #244: block requests from public
//!   pages to private-IP/localhost hosts.
//!
//! Kotlin keeps data *sourcing* (assets/files/prefs) and pushes raw
//! list content here once at init; the per-request `verdict` hot path
//! takes a read lock and does not allocate beyond the two FFI strings.

use std::borrow::Cow;
use std::collections::{HashMap, HashSet};
use std::net::{Ipv4Addr, Ipv6Addr};
use std::sync::{Arc, RwLock};

use aho_corasick::AhoCorasick;

/// Decision for a single web request, returned by
/// [`BlocklistEngine::verdict`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RequestVerdict {
    /// The request may proceed.
    Allow,
    /// The request domain is on the ad hosts list.
    BlockAds,
    /// The main-frame URL matches a malware-list term.
    BlockMalware,
    /// The main-frame URL is denied by the site-block list/mode.
    BlockSite,
    /// A public page requested a private-IP/localhost resource
    /// (upstream #244).
    BlockLocalNetwork,
}

/// Site-block mode, mirroring `SiteBlockChoice` on the Kotlin side.
/// The upstream naming is inverted and kept for parity: `Whitelist`
/// blocks URLs *matching* the list, `Blacklist` blocks URLs *not*
/// matching the list.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Enum)]
pub enum SiteBlockMode {
    /// Site blocking disabled.
    #[default]
    None,
    /// Block main-frame URLs containing a list entry.
    Whitelist,
    /// Block main-frame URLs not containing any list entry.
    Blacklist,
}

/// Feature toggles pushed from `UserPreferences` in one atomic call.
#[derive(Debug, Clone, Copy, Default, uniffi::Record)]
pub struct BlocklistConfig {
    /// Ad hosts list matching enabled.
    pub ads_enabled: bool,
    /// Malware term matching enabled (main frame only).
    pub malware_enabled: bool,
    /// Public→private-network request blocking enabled (#244).
    pub lan_block_enabled: bool,
}

/// Key under which the single-source hosts set lives when loaded via
/// [`BlocklistEngine::load_ad_hosts`] (the pre-LIST01 one-shot API).
const BASELINE_KEY: &str = "__baseline__";

#[derive(Default)]
struct Inner {
    config: BlocklistConfig,
    /// Merged view over [`Inner::subscription_hosts`] — the only set
    /// the per-request verdict path touches.
    ad_hosts: HashSet<String>,
    /// Per-subscription domain sets (LIST01). Each enabled list is
    /// parsed on load; the merged `ad_hosts` is rebuilt on change, so
    /// the verdict hot path stays a single HashSet lookup.
    subscription_hosts: HashMap<String, HashSet<String>>,
    malware: Option<AhoCorasick>,
    site_block_mode: SiteBlockMode,
    site_block_entries: Vec<String>,
}

impl Inner {
    fn rebuild_ad_hosts(&mut self) {
        self.ad_hosts = self
            .subscription_hosts
            .values()
            .flat_map(|set| set.iter().cloned())
            .collect();
    }
}

/// The unified blocklist engine. One instance lives behind the
/// `ArktosCore` Kotlin seam; list content is loaded once at init and
/// `verdict` is the per-request hot path.
#[derive(uniffi::Object)]
pub struct BlocklistEngine {
    inner: RwLock<Inner>,
}

#[uniffi::export]
impl BlocklistEngine {
    /// Create an empty engine — everything is allowed until lists and
    /// config are pushed.
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: RwLock::new(Inner::default()),
        })
    }

    /// Push the feature toggles.
    pub fn configure(&self, config: BlocklistConfig) {
        self.inner.write().unwrap().config = config;
    }

    /// Parse hosts-file `content` and replace the ad hosts set.
    /// Returns the number of blocked domains loaded.
    ///
    /// Single-source API from BLOCK01: replaces every loaded list,
    /// including any subscriptions — kept for the asset-baseline call
    /// and backwards compatibility. Multi-list loading goes through
    /// [`BlocklistEngine::load_subscription_hosts`].
    pub fn load_ad_hosts(&self, content: String) -> u32 {
        let hosts: HashSet<String> = parse_hosts_file(&content).into_iter().collect();
        let count = hosts.len() as u32;
        let mut inner = self.inner.write().unwrap();
        inner.subscription_hosts.clear();
        inner.subscription_hosts.insert(BASELINE_KEY.to_owned(), hosts);
        inner.rebuild_ad_hosts();
        count
    }

    /// Parse one subscription's list `content` and merge its domains
    /// into the ad hosts set under `id` (LIST01). Re-loading the same
    /// `id` replaces that list's contribution only. Returns the number
    /// of domains extracted from this list.
    ///
    /// Content may be hosts-file format or an AdBlockPlus-style
    /// filter list — `parse_list_domains` understands both.
    pub fn load_subscription_hosts(&self, id: String, content: String) -> u32 {
        let hosts: HashSet<String> = parse_list_domains(&content).into_iter().collect();
        let count = hosts.len() as u32;
        let mut inner = self.inner.write().unwrap();
        inner.subscription_hosts.insert(id, hosts);
        inner.rebuild_ad_hosts();
        count
    }

    /// Drop a subscription's contribution to the ad hosts set (LIST01)
    /// — called when a list is disabled or removed.
    pub fn remove_subscription_hosts(&self, id: String) {
        let mut inner = self.inner.write().unwrap();
        if inner.subscription_hosts.remove(&id).is_some() {
            inner.rebuild_ad_hosts();
        }
    }

    /// Load comma-separated malware terms (`malware.txt` format).
    /// Matching is substring-based, on the main-frame URL only.
    /// An empty string clears the list. Returns the term count.
    pub fn load_malware_terms(&self, content: String) -> u32 {
        // Kotlin parity: split(',') drops trailing empties, keeps inner
        // ones — an inner "" term matches every URL, exactly like the
        // old `url.contains("")` loop.
        let mut terms: Vec<&str> = content.split(',').collect();
        while terms.last().is_some_and(|t| t.is_empty()) {
            terms.pop();
        }
        let count = terms.len() as u32;
        let matcher = if terms.is_empty() {
            None
        } else {
            AhoCorasick::new(terms).ok()
        };
        self.inner.write().unwrap().malware = matcher;
        count
    }

    /// Push the raw `siteBlockNames` preference string and mode.
    pub fn set_site_block_list(&self, names: String, mode: SiteBlockMode) {
        let entries = split_site_block_names(&names, mode);
        let mut inner = self.inner.write().unwrap();
        inner.site_block_entries = entries;
        inner.site_block_mode = mode;
    }

    /// Per-request verdict — the JNI hot path. `page_url` is the URL
    /// of the loading page ("" when unknown); `is_main_frame` gates
    /// the page-level checks (malware/site block).
    pub fn verdict(
        &self,
        request_url: String,
        page_url: String,
        is_main_frame: bool,
    ) -> RequestVerdict {
        self.inner
            .read()
            .unwrap()
            .verdict(&request_url, &page_url, is_main_frame)
    }
}

impl Inner {
    fn verdict(&self, request_url: &str, page_url: &str, is_main_frame: bool) -> RequestVerdict {
        if is_main_frame && !is_internal_url(request_url) {
            match self.site_block_mode {
                SiteBlockMode::Whitelist
                    if contains_any(request_url, &self.site_block_entries) =>
                {
                    return RequestVerdict::BlockSite;
                }
                SiteBlockMode::Blacklist
                    if !self.site_block_entries.is_empty()
                        && !contains_any(request_url, &self.site_block_entries) =>
                {
                    return RequestVerdict::BlockSite;
                }
                _ => {}
            }
            if self.config.malware_enabled
                && self
                    .malware
                    .as_ref()
                    .is_some_and(|m| m.is_match(request_url))
            {
                return RequestVerdict::BlockMalware;
            }
        }

        if self.config.lan_block_enabled {
            let request_host = extract_domain(request_url);
            if is_private_or_local_host(&request_host) {
                let page_is_public = (page_url.starts_with("http://")
                    || page_url.starts_with("https://"))
                    && !is_private_or_local_host(&extract_domain(page_url));
                if page_is_public {
                    return RequestVerdict::BlockLocalNetwork;
                }
            }
        }

        if self.config.ads_enabled
            && self.ad_hosts.contains(extract_domain(request_url).as_ref())
        {
            return RequestVerdict::BlockAds;
        }

        RequestVerdict::Allow
    }
}

fn contains_any(url: &str, entries: &[String]) -> bool {
    entries.iter().any(|e| url.contains(e.as_str()))
}

/// `file:///android_asset` and `about:blank` are exempt from site
/// blocking — substring check, matching the Kotlin original.
fn is_internal_url(url: &str) -> bool {
    url.contains("file:///android_asset") || url.contains("about:blank")
}

/// Split the `siteBlockNames` preference the way the Kotlin code did:
/// the separator is picked by content, and differs between modes.
/// Only trailing empties are dropped — an inner "" entry matches every
/// URL, same as upstream.
fn split_site_block_names(names: &str, mode: SiteBlockMode) -> Vec<String> {
    if names.is_empty() {
        return Vec::new();
    }
    let separator = match mode {
        SiteBlockMode::Whitelist => {
            if names.contains(", ") {
                ", "
            } else if names.contains(",\n") {
                ",\n"
            } else {
                ","
            }
        }
        _ => {
            if names.contains(", ") {
                ", "
            } else {
                ","
            }
        }
    };
    let mut parts: Vec<String> = names.split(separator).map(str::to_owned).collect();
    while parts.last().is_some_and(|p| p.is_empty()) {
        parts.pop();
    }
    parts
}

/// Parse a hosts file into blocked domains — port of
/// `HostsFileParser.parseInput`, parity-pinned by `HostParserTest`.
///
/// Quirks preserved from the Kotlin original:
/// * only the FIRST occurrence of each local IP is stripped;
/// * only the FIRST tab becomes a space;
/// * "localhost" is skipped only when it is the whole remaining line;
/// * `#` comments truncate the line.
pub fn parse_hosts_file(content: &str) -> Vec<String> {
    let mut domains = Vec::new();
    for line in content.lines() {
        parse_hosts_line(line, &mut domains);
    }
    domains
}

fn parse_hosts_line(line: &str, domains: &mut Vec<String>) {
    if line.is_empty() || line.starts_with('#') {
        return;
    }

    let mut s = line.replacen("127.0.0.1", "", 1);
    s = s.replacen("0.0.0.0", "", 1);
    s = s.replacen("::1", "", 1);
    s = s.replacen('\t', " ", 1);

    match s.find('#') {
        Some(0) => return,
        Some(i) => s.truncate(i),
        None => {}
    }

    let mut rest = s.trim_matches(' ');
    if rest.is_empty() || rest == "localhost" {
        return;
    }

    while let Some(space) = rest.find(' ') {
        let token = rest[..space].trim_matches(' ');
        domains.push(token.to_owned());
        // Kotlin removed the first occurrence of the trimmed token —
        // it sits at offset 0 — then trimmed both ends.
        rest = rest[token.len()..].trim_matches(' ');
    }
    if !rest.is_empty() {
        domains.push(rest.to_owned());
    }
}

/// Parse subscription list content into blocked domains (LIST01).
///
/// The preset catalog mixes hosts-file lists (Dan Pollock, MVPS) with
/// AdBlockPlus-syntax filter lists (EasyList, uBlock, AdGuard). ABP
/// cosmetic/element rules can't be applied by a domain engine, so
/// this extracts what's enforceable:
///
/// * `||domain^` host anchors (the network-level domain rules),
/// * hosts-file / bare-domain lines via [`parse_hosts_line`].
///
/// Skipped: `!`/`[` comments and headers, `@@` exception rules (the
/// engine has no allow syntax), cosmetic rules (`##`, `#@#`, `#?#`),
/// URL-pattern rules containing scheme/`$` options/`*` wildcards.
/// Domains are lowercased (no parity constraint — new format).
pub fn parse_list_domains(content: &str) -> Vec<String> {
    let mut domains = Vec::new();
    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty()
            || trimmed.starts_with('!')
            || trimmed.starts_with('[')
            || trimmed.starts_with("@@")
            || trimmed.contains("##")
            || trimmed.contains("#@#")
            || trimmed.contains("#?#")
        {
            continue;
        }
        if let Some(rest) = trimmed.strip_prefix("||") {
            // Host anchor: the domain runs to the next `^`, `/` or `|`.
            let host = rest
                .split(|c| c == '^' || c == '/' || c == '|')
                .next()
                .unwrap_or("");
            if is_plausible_host(host) {
                domains.push(host.to_lowercase());
            }
            continue;
        }
        // URL-pattern / option / wildcard rules can't map to a bare
        // domain — skip anything that isn't a plain hosts line.
        if trimmed.contains("://")
            || trimmed.contains('$')
            || trimmed.contains('*')
            || trimmed.starts_with('|')
        {
            continue;
        }
        parse_hosts_line(trimmed, &mut domains);
    }
    domains
}

/// Rough host sanity check for `||` anchors: non-empty, contains a
/// dot, and made of domain characters only (no wildcards/scheme
/// leftovers).
fn is_plausible_host(host: &str) -> bool {
    !host.is_empty()
        && host.contains('.')
        && host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b'_')
}

/// Extract the probable domain from a URL — port of
/// `BloomFilterAdBlocker.getDomainName`: the URL is truncated at the
/// first `/` at index ≥ 8 (path stripped), the authority host is taken
/// (lowercased like `java.net.URI.getHost()`), and a leading `www.`
/// is dropped. Unparseable input yields the truncated URL itself.
fn extract_domain(url: &str) -> Cow<'_, str> {
    let candidate = match url.as_bytes()[8.min(url.len())..]
        .iter()
        .position(|&b| b == b'/')
    {
        Some(pos) => &url[..8 + pos],
        None => url,
    };

    let after_scheme = candidate
        .split_once("://")
        .map(|(_, rest)| rest)
        .unwrap_or(candidate);
    let after_user = after_scheme
        .rsplit_once('@')
        .map(|(_, rest)| rest)
        .unwrap_or(after_scheme);
    let host = if let Some(rest) = after_user.strip_prefix('[') {
        match rest.find(']') {
            Some(end) => &rest[..end],
            None => after_user,
        }
    } else {
        after_user.split(':').next().unwrap_or(after_user)
    };

    let lowered = if host.bytes().any(|b| b.is_ascii_uppercase()) {
        Cow::Owned(host.to_lowercase())
    } else {
        Cow::Borrowed(host)
    };
    match lowered.strip_prefix("www.") {
        Some(stripped) => Cow::Owned(stripped.to_owned()),
        None => lowered,
    }
}

/// True for localhost names and private/loopback/link-local IP
/// literals — the LAN-block decision for upstream #244.
fn is_private_or_local_host(host: &str) -> bool {
    let host = host.trim_end_matches('.');
    if host.eq_ignore_ascii_case("localhost") || host.ends_with(".localhost") {
        return true;
    }
    if let Ok(v4) = host.parse::<Ipv4Addr>() {
        return is_private_v4(v4);
    }
    let v6 = host
        .strip_prefix('[')
        .and_then(|h| h.strip_suffix(']'))
        .unwrap_or(host);
    if let Ok(v6) = v6.parse::<Ipv6Addr>() {
        if v6.is_loopback() || v6.is_unspecified() {
            return true;
        }
        if let Some(v4) = v6.to_ipv4_mapped() {
            return is_private_v4(v4);
        }
        let seg0 = v6.segments()[0];
        return (seg0 & 0xfe00) == 0xfc00   // fc00::/7 unique-local
            || (seg0 & 0xffc0) == 0xfe80; // fe80::/10 link-local
    }
    false
}

fn is_private_v4(v4: Ipv4Addr) -> bool {
    v4.is_private()        // 10/8, 172.16/12, 192.168/16
        || v4.is_loopback()    // 127/8
        || v4.is_link_local()  // 169.254/16
        || v4.is_unspecified() // 0.0.0.0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn engine() -> BlocklistEngine {
        BlocklistEngine {
            inner: RwLock::new(Inner::default()),
        }
    }

    // ---- hosts parser — parity vs HostParserTest.kt ----------------

    #[test]
    fn hosts_parser_matches_kotlin_fixture() {
        // Same input and expectation as `HostParserTest.line parsing is valid`.
        let input = "\
127.0.0.1 localhost #comment comment
0.0.0.0 localhost #comment comment
::1 localhost #comment comment
#
# comment
#
127.0.0.1\tfake.domain1.com
127.0.0.1\tfake.domain2.com    # comment
0.0.0.0\tfake.domain3.com    # comment
# comment
::1 example.com
0.0.0.0 multi1.com multi2.com # comment
0.0.0.0 close.comment.com#comment
";
        let domains = parse_hosts_file(input);
        assert_eq!(domains.len(), 7);
        for expected in [
            "fake.domain1.com",
            "fake.domain2.com",
            "fake.domain3.com",
            "example.com",
            "multi1.com",
            "multi2.com",
            "close.comment.com",
        ] {
            assert!(domains.contains(&expected.to_owned()), "missing {expected}");
        }
    }

    #[test]
    fn hosts_parser_strips_only_first_ip_occurrence() {
        // Kotlin replaced the first occurrence of each token, in order.
        let domains = parse_hosts_file("127.0.0.1 127.0.0.1.evil.com\n");
        assert_eq!(domains, vec!["127.0.0.1.evil.com".to_owned()]);
    }

    #[test]
    fn hosts_parser_keeps_localhost_when_not_alone() {
        let domains = parse_hosts_file("127.0.0.1 localhost other.com\n");
        assert_eq!(domains, vec!["localhost".to_owned(), "other.com".to_owned()]);
    }

    #[test]
    fn hosts_parser_keeps_second_tab_in_token() {
        // Only the first tab becomes a space — a later tab stays inside
        // the token, matching the Kotlin parser.
        let domains = parse_hosts_file("0.0.0.0\ta.com\tb.com\n");
        assert_eq!(domains, vec!["a.com\tb.com".to_owned()]);
    }

    // ---- domain extraction ------------------------------------------

    #[test]
    fn extract_domain_strips_path_www_and_port() {
        assert_eq!(extract_domain("https://www.a.b.com/x/y"), "a.b.com");
        assert_eq!(extract_domain("https://a.b.com:8443/x"), "a.b.com");
        assert_eq!(extract_domain("http://user@a.b.com"), "a.b.com");
        assert_eq!(extract_domain("a.b.com"), "a.b.com");
        assert_eq!(extract_domain("http://[2001:db8::1]:81/x"), "2001:db8::1");
    }

    // ---- private host detection -------------------------------------

    #[test]
    fn private_hosts_detected() {
        for host in [
            "localhost",
            "foo.localhost",
            "127.0.0.1",
            "10.0.0.5",
            "172.16.3.4",
            "192.168.1.1",
            "169.254.1.1",
            "0.0.0.0",
            "::1",
            "[::1]",
            "fd00::1",
            "fe80::1",
            "::ffff:192.168.0.1",
        ] {
            assert!(is_private_or_local_host(host), "expected private: {host}");
        }
        for host in ["example.com", "8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", ""] {
            assert!(!is_private_or_local_host(host), "expected public: {host}");
        }
    }

    // ---- verdict ----------------------------------------------------

    #[test]
    fn ads_blocked_by_exact_domain() {
        let e = engine();
        e.configure(BlocklistConfig {
            ads_enabled: true,
            ..Default::default()
        });
        e.load_ad_hosts("0.0.0.0 ads.example.com\n".to_owned());
        assert_eq!(
            e.verdict("https://ads.example.com/banner.js".into(), "https://ok.com".into(), false),
            RequestVerdict::BlockAds
        );
        // www. prefix stripped before lookup — parity with Kotlin.
        assert_eq!(
            e.verdict("https://www.ads.example.com/".into(), "".into(), false),
            RequestVerdict::BlockAds
        );
        // No parent-domain matching — parity with the exact repository lookup.
        assert_eq!(
            e.verdict("https://sub.ads.example.com/".into(), "".into(), false),
            RequestVerdict::Allow
        );
    }

    #[test]
    fn malware_main_frame_only() {
        let e = engine();
        e.configure(BlocklistConfig {
            malware_enabled: true,
            ..Default::default()
        });
        e.load_malware_terms("evil.com,bad.org".to_owned());
        assert_eq!(
            e.verdict("https://evil.com/page".into(), "".into(), true),
            RequestVerdict::BlockMalware
        );
        assert_eq!(
            e.verdict("https://evil.com/res.js".into(), "https://x.com".into(), false),
            RequestVerdict::Allow
        );
    }

    #[test]
    fn site_block_modes() {
        let e = engine();
        e.set_site_block_list("blockme.com".to_owned(), SiteBlockMode::Whitelist);
        assert_eq!(
            e.verdict("https://blockme.com/".into(), "".into(), true),
            RequestVerdict::BlockSite
        );
        assert_eq!(
            e.verdict("https://fine.com/".into(), "".into(), true),
            RequestVerdict::Allow
        );

        e.set_site_block_list("ok.com".to_owned(), SiteBlockMode::Blacklist);
        assert_eq!(
            e.verdict("https://ok.com/".into(), "".into(), true),
            RequestVerdict::Allow
        );
        assert_eq!(
            e.verdict("https://other.com/".into(), "".into(), true),
            RequestVerdict::BlockSite
        );
        // Internal pages exempt — parity with the Kotlin early-return.
        assert_eq!(
            e.verdict("file:///android_asset/homepage.html".into(), "".into(), true),
            RequestVerdict::Allow
        );
        assert_eq!(
            e.verdict("about:blank".into(), "".into(), true),
            RequestVerdict::Allow
        );
    }

    #[test]
    fn site_block_separator_parity() {
        // "a, b,c" — ", " present → bare commas stay inside entries.
        assert_eq!(
            split_site_block_names("a.com, b.com,c.com", SiteBlockMode::Whitelist),
            vec!["a.com".to_owned(), "b.com,c.com".to_owned()]
        );
        assert_eq!(
            split_site_block_names("a.com,b.com", SiteBlockMode::Blacklist),
            vec!["a.com".to_owned(), "b.com".to_owned()]
        );
    }

    #[test]
    fn lan_block_public_page_to_private_host() {
        let e = engine();
        e.configure(BlocklistConfig {
            lan_block_enabled: true,
            ..Default::default()
        });
        // Public page → LAN resource: blocked.
        assert_eq!(
            e.verdict(
                "http://192.168.1.1/probe".into(),
                "https://scanner.example".into(),
                false
            ),
            RequestVerdict::BlockLocalNetwork
        );
        assert_eq!(
            e.verdict(
                "http://localhost:8000/x".into(),
                "https://public.example".into(),
                false
            ),
            RequestVerdict::BlockLocalNetwork
        );
        // LAN page → LAN resource: allowed.
        assert_eq!(
            e.verdict(
                "http://192.168.1.1/style.css".into(),
                "http://192.168.1.1/".into(),
                false
            ),
            RequestVerdict::Allow
        );
        // Cold start / empty page URL: user-initiated navigation allowed.
        assert_eq!(
            e.verdict("http://192.168.1.1/".into(), "".into(), true),
            RequestVerdict::Allow
        );
        // Public → public untouched.
        assert_eq!(
            e.verdict(
                "https://cdn.example/x.js".into(),
                "https://public.example".into(),
                false
            ),
            RequestVerdict::Allow
        );
    }

    // ---- subscription lists (LIST01) -------------------------------

    #[test]
    fn list_parser_handles_hosts_and_abp_syntax() {
        let content = "\
! Title: Mixed list
[Adblock Plus 2.0]
0.0.0.0 host.example
plain-domain.example
||ads.example.com^
||Tracker.Example.org^$third-party
||wild*.example.com^
@@||allowed.example.com^
example.com##.banner
example.com#@#.unblock
/advertising/*$image
|https://subscribe.example.com/feed
||no-dot^
";
        let mut domains = parse_list_domains(content);
        domains.sort_unstable();
        assert_eq!(
            domains,
            vec![
                "ads.example.com",
                "host.example",
                "plain-domain.example",
                "tracker.example.org"
            ]
        );
    }

    #[test]
    fn subscription_merge_and_remove() {
        let e = engine();
        e.configure(BlocklistConfig {
            ads_enabled: true,
            ..Default::default()
        });

        e.load_subscription_hosts(
            "list-a".into(),
            "0.0.0.0 shared.example a-only.example\n".into(),
        );
        e.load_subscription_hosts(
            "list-b".into(),
            "0.0.0.0 shared.example b-only.example\n".into(),
        );

        for url in [
            "https://a-only.example/x",
            "https://b-only.example/x",
            "https://shared.example/x",
        ] {
            assert_eq!(
                e.verdict(url.into(), "".into(), false),
                RequestVerdict::BlockAds,
                "expected blocked: {url}"
            );
        }

        // Removing list-b keeps list-a's domains (incl. the shared one).
        e.remove_subscription_hosts("list-b".into());
        assert_eq!(
            e.verdict("https://b-only.example/x".into(), "".into(), false),
            RequestVerdict::Allow
        );
        assert_eq!(
            e.verdict("https://shared.example/x".into(), "".into(), false),
            RequestVerdict::BlockAds
        );
        assert_eq!(
            e.verdict("https://a-only.example/x".into(), "".into(), false),
            RequestVerdict::BlockAds
        );

        // Removing an unknown id is a harmless no-op.
        e.remove_subscription_hosts("missing".into());
        assert_eq!(
            e.verdict("https://shared.example/x".into(), "".into(), false),
            RequestVerdict::BlockAds
        );
    }

    #[test]
    fn subscription_reload_replaces_only_that_list() {
        let e = engine();
        e.configure(BlocklistConfig {
            ads_enabled: true,
            ..Default::default()
        });
        e.load_subscription_hosts("a".into(), "0.0.0.0 old.example\n".into());
        e.load_subscription_hosts("b".into(), "0.0.0.0 keep.example\n".into());
        // Reload "a" with different content.
        e.load_subscription_hosts("a".into(), "0.0.0.0 new.example\n".into());

        assert_eq!(
            e.verdict("https://old.example/".into(), "".into(), false),
            RequestVerdict::Allow
        );
        assert_eq!(
            e.verdict("https://new.example/".into(), "".into(), false),
            RequestVerdict::BlockAds
        );
        assert_eq!(
            e.verdict("https://keep.example/".into(), "".into(), false),
            RequestVerdict::BlockAds
        );
    }

    #[test]
    fn load_ad_hosts_replaces_subscriptions() {
        let e = engine();
        e.configure(BlocklistConfig {
            ads_enabled: true,
            ..Default::default()
        });
        e.load_subscription_hosts("a".into(), "0.0.0.0 sub.example\n".into());
        e.load_ad_hosts("0.0.0.0 base.example\n".into());
        // Single-source API keeps BLOCK01 semantics: full replacement.
        assert_eq!(
            e.verdict("https://sub.example/".into(), "".into(), false),
            RequestVerdict::Allow
        );
        assert_eq!(
            e.verdict("https://base.example/".into(), "".into(), false),
            RequestVerdict::BlockAds
        );
    }
}
