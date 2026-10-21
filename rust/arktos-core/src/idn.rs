// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
//! SEC12 — IDN / homograph display protection.
//!
//! An attacker can register a domain whose Unicode host *looks* like a
//! familiar ASCII brand (`раураl.com` with Cyrillic letters). Chrome
//! resolves this with a full UTS-39 confusability pipeline; the
//! simpler deterministic policy here — the same one Firefox exposes
//! via `network.IDN_show_punycode` — is to render the host in its
//! A-label (punycode) form whenever it is an internationalized domain
//! at all, so `раураl.com` can never masquerade as `paypal.com` in the
//! URL bar.
//!
//! [`idn_display`] is a *display-only* transform, called from
//! `SearchBoxModel` when `showPunycodeEnabled` is set — navigation,
//! history and bookmarks keep the original URL.

use idna::domain_to_ascii;

/// Display-time IDN verdict for one URL.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct IdnDisplay {
    /// True when the URL's host is an internationalized domain — it
    /// carries non-ASCII characters or `xn--` A-labels (WebView hands
    /// canonicalized URLs, so an already-encoded host still flags).
    pub is_idn: bool,
    /// The URL with its host rewritten to the ASCII (A-label) form —
    /// identical to the input when the host needs no rewrite or IDNA
    /// processing rejects it.
    pub ascii_url: String,
}

/// Map [url] to its punycode-display form. Host extraction is a
/// lenient authority split (userinfo/port/IPv6 aware), not the
/// ASCII-only `uri_host` used by `domain_name`, so Unicode hosts are
/// handled too. Strings without an authority (`about:blank`, `data:`,
/// `javascript:`…) and bare `host/path` display strings are both
/// understood.
#[uniffi::export]
pub fn idn_display(url: String) -> IdnDisplay {
    let Some((start, end)) = host_span(&url) else {
        return IdnDisplay {
            is_idn: false,
            ascii_url: url,
        };
    };
    let host = &url[start..end];
    let ascii = if host.is_ascii() {
        host.to_owned()
    } else {
        // UTS-46 rejects an invalid IDN — keep the original host rather
        // than dropping display text; the flag still reports it.
        domain_to_ascii(host).unwrap_or_else(|_| host.to_owned())
    };
    IdnDisplay {
        is_idn: !host.is_ascii() || has_alabel(host),
        ascii_url: format!("{}{}{}", &url[..start], ascii, &url[end..]),
    }
}

/// `(start, end)` byte span of the host inside [url], or `None` when
/// the URL has no authority host.
fn host_span(url: &str) -> Option<(usize, usize)> {
    let auth_start = match url.find("://") {
        Some(i) => i + 3,
        None => {
            // A `scheme:` head means an opaque URI (`about:blank`,
            // `data:…`); anything else is treated as a bare
            // `host[/path]` display string (the DOMAIN url-box mode
            // emits those).
            let head = match url.find(['/', '?', '#']) {
                Some(i) => &url[..i],
                None => url,
            };
            match head.find(':') {
                Some(i)
                    if head[..i].bytes().next().is_some_and(|b| b.is_ascii_alphabetic())
                        && head[..i]
                            .bytes()
                            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'+' | b'-' | b'.')) =>
                {
                    return None;
                }
                _ => 0,
            }
        }
    };
    let rest = &url[auth_start..];
    let auth_len = rest
        .find(|c| matches!(c, '/' | '?' | '#'))
        .unwrap_or(rest.len());
    if auth_len == 0 {
        return None;
    }
    let auth_end = auth_start + auth_len;
    let authority = &url[auth_start..auth_end];
    // Userinfo ends at the LAST '@' before the authority's end.
    let host_start = auth_start + authority.rfind('@').map_or(0, |i| i + 1);
    let hostport = &url[host_start..auth_end];
    let host_end = if hostport.starts_with('[') {
        // IPv6 literal — the port, if any, follows the closing bracket.
        match hostport.find(']') {
            Some(i) => host_start + i + 1,
            None => auth_end,
        }
    } else {
        match hostport.rfind(':') {
            // A trailing `:` only splits when what follows is a port.
            Some(i)
                if !hostport[i + 1..].is_empty()
                    && hostport[i + 1..].bytes().all(|b| b.is_ascii_digit()) =>
            {
                host_start + i
            }
            _ => auth_end,
        }
    };
    (host_end > host_start).then_some((host_start, host_end))
}

/// True when any host label is an A-label (`xn--…`) — i.e. the host is
/// already punycode-encoded.
fn has_alabel(host: &str) -> bool {
    host.split('.')
        .any(|label| label.get(..4).is_some_and(|p| p.eq_ignore_ascii_case("xn--")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unicode_host_renders_as_punycode() {
        let info = idn_display("https://bücher.de/path?q=1".into());
        assert!(info.is_idn);
        assert_eq!(info.ascii_url, "https://xn--bcher-kva.de/path?q=1");
        let info = idn_display("http://münchen.de".into());
        assert!(info.is_idn);
        assert_eq!(info.ascii_url, "http://xn--mnchen-3ya.de");
    }

    #[test]
    fn cyrillic_lookalike_flags_and_encodes() {
        // "раураl.com" — Cyrillic lookalike of paypal.com.
        let info = idn_display("https://раураl.com/login".into());
        assert!(info.is_idn);
        assert!(
            info.ascii_url.starts_with("https://xn--"),
            "expected punycode host, got {}",
            info.ascii_url
        );
        assert!(info.ascii_url.ends_with(".com/login"));
    }

    #[test]
    fn ascii_hosts_pass_through() {
        for url in [
            "https://example.com/x?y=1#f",
            "http://www.example.com",
            "https://user:pw@example.com:8443/p",
            "https://192.168.0.1/admin",
            "https://[::1]:8080/x",
        ] {
            let info = idn_display(url.into());
            assert!(!info.is_idn, "{url} must not flag");
            assert_eq!(info.ascii_url, url, "{url} must pass through");
        }
    }

    #[test]
    fn already_punycode_host_flags() {
        let info = idn_display("https://xn--bcher-kva.de/".into());
        assert!(info.is_idn);
        assert_eq!(info.ascii_url, "https://xn--bcher-kva.de/");
    }

    #[test]
    fn userinfo_port_and_idn_host() {
        let info = idn_display("https://user@bücher.de:8443/x".into());
        assert!(info.is_idn);
        assert_eq!(info.ascii_url, "https://user@xn--bcher-kva.de:8443/x");
    }

    #[test]
    fn bare_host_display_string_rewrites() {
        // SearchBoxDisplayChoice.DOMAIN emits schemeless host strings.
        let info = idn_display("bücher.de".into());
        assert!(info.is_idn);
        assert_eq!(info.ascii_url, "xn--bcher-kva.de");
        let info = idn_display("https://bücher.de".into());
        assert_eq!(info.ascii_url, "https://xn--bcher-kva.de");
    }

    #[test]
    fn non_http_uris_have_no_host() {
        for url in [
            "about:blank",
            "data:text/html,x",
            "javascript:void(0)",
            "file:///x/downloads.html",
            "arktos://changelog",
            "",
        ] {
            let info = idn_display(url.into());
            assert!(!info.is_idn, "{url} must not flag");
            assert_eq!(info.ascii_url, url, "{url} must pass through");
        }
    }

    #[test]
    fn invalid_idn_keeps_host_but_flags() {
        // `domain_to_ascii` runs without DNS-length/STD3 checks
        // (WHATWG-URL style), so only an unconditionally disallowed
        // code point fails — the host is then left untouched but
        // still reported as IDN.
        let info = idn_display("https://bü\u{FFFE}cher.de/".into());
        assert!(info.is_idn);
        assert_eq!(info.ascii_url, "https://bü\u{FFFE}cher.de/");
    }
}
