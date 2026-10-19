//! Shared hostname helpers (RANK01) — a compact port of the desktop
//! rustcore `util.rs` pieces the rank table needs: stack-buffered
//! host normalization plus the conservative DNS-shape gate, so
//! `site_rank` probes stay allocation-free on the FFI path.

/// A query hostname normalized for probing: trimmed, ASCII-lowercased,
/// trailing dots stripped, `[v6]` brackets removed — held in a fixed
/// stack buffer so membership probes stay allocation-free.
/// Longer-than-DNS input is still probed on its rightmost bytes:
/// every listed entry is <= 253 bytes, so any matching label-
/// boundary suffix lives entirely inside the tail.
pub struct HostNorm {
    buf: [u8; 253],
    len: usize,
}

impl HostNorm {
    pub fn new(host: &str) -> Option<HostNorm> {
        let mut buf = [0u8; 253];
        let mut n = 0usize;
        let trimmed = host.trim();
        let input = if trimmed.len() > buf.len() {
            let mut start = trimmed.len() - buf.len();
            while !trimmed.is_char_boundary(start) {
                start += 1;
            }
            &trimmed[start..]
        } else {
            trimmed
        };
        for &b in input.as_bytes() {
            buf[n] = b.to_ascii_lowercase();
            n += 1;
        }
        while n > 0 && buf[n - 1] == b'.' {
            n -= 1;
        }
        if n == 0 {
            return None;
        }
        // URI hosts report IPv6 literals bracketed.
        if buf[0] == b'[' && buf[n - 1] == b']' {
            buf.copy_within(1..n - 1, 0);
            n -= 2;
        }
        if n == 0 {
            return None;
        }
        // The normalization only touched ASCII bytes, so the result
        // stays valid UTF-8.
        std::str::from_utf8(&buf[..n]).ok()?;
        Some(HostNorm { buf, len: n })
    }

    pub fn as_str(&self) -> &str {
        // Valid UTF-8: checked at construction.
        std::str::from_utf8(&self.buf[..self.len]).unwrap_or("")
    }

    /// The whole name, then each label-boundary parent:
    /// "a.b.c" yields "a.b.c", "b.c", "c".
    pub fn suffixes(&self) -> HostSuffixes<'_> {
        HostSuffixes {
            rest: self.as_str(),
        }
    }
}

/// Conservative DNS-name gate for rank-list entries: one dot minimum
/// (no TLD or localhost entries), labels of [a-z0-9_-] only, sane
/// lengths. IP literals pass the shape test — the rank table rejects
/// them on top (rank lists name domains only).
pub fn valid_dns_shape(host: &str) -> bool {
    if host.len() > 253 || !host.contains('.') {
        return false;
    }
    host.split('.').all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && label
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    })
}

pub struct HostSuffixes<'a> {
    rest: &'a str,
}

impl<'a> Iterator for HostSuffixes<'a> {
    type Item = &'a str;

    fn next(&mut self) -> Option<&'a str> {
        if self.rest.is_empty() {
            return None;
        }
        let out = self.rest;
        self.rest = match self.rest.find('.') {
            Some(i) => &self.rest[i + 1..],
            None => "",
        };
        Some(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hostnorm_normalizes() {
        assert_eq!(HostNorm::new(" WWW.Example.COM. ").unwrap().as_str(), "www.example.com");
        assert_eq!(HostNorm::new("[::1]").unwrap().as_str(), "::1");
        assert!(HostNorm::new("").is_none());
        assert!(HostNorm::new("   ").is_none());
        assert!(HostNorm::new("...").is_none());
        // Overlong input keeps the rightmost 253 bytes.
        let long = format!("{}.example", "a".repeat(300));
        assert_eq!(HostNorm::new(&long).unwrap().as_str().len(), 253);
    }

    #[test]
    fn suffixes_walk_label_boundaries() {
        let h = HostNorm::new("a.b.c").unwrap();
        let got: Vec<&str> = h.suffixes().collect();
        assert_eq!(got, vec!["a.b.c", "b.c", "c"]);
    }

    #[test]
    fn dns_shape_gate() {
        assert!(valid_dns_shape("example.com"));
        assert!(valid_dns_shape("a-b_c.example.co.uk"));
        assert!(!valid_dns_shape("localhost"));
        assert!(!valid_dns_shape("no-dot"));
        assert!(!valid_dns_shape("bad..dot.com"));
        // Hyphen position is not enforced (desktop gate parity).
        assert!(valid_dns_shape("-bad.example"));
        // 63-char label ok, 64 not.
        assert!(valid_dns_shape(&format!("{}.com", "a".repeat(63))));
        assert!(!valid_dns_shape(&format!("{}.com", "a".repeat(64))));
    }
}
