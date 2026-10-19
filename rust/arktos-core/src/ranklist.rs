//! Site-rank (Tranco + Majestic) lists — RANK01.
//!
//! Port of the desktop rustcore `tranco.rs` + the `TrancoList`
//! updater's semantics to the mobile core. The rank table answers
//! "is this host a popular domain" for later site-trust/shield
//! policy; a lookup never leaves the machine — the table is shipped
//! data merged with consent-gated downloads, not a remote service.
//!
//! Data sources:
//!   * vendored `data/tranco-top100k.csv` — ranks 1..100000 of the
//!     Tranco daily top-1M, compiled in so ranking works with no
//!     data dir and no network at all;
//!   * `<rank dir>/tranco-list.dat` — the download-on-demand full
//!     Tranco top-1M the Kotlin `RankListLoader` drops in. The body
//!     lands verbatim (`top-1m.csv.zip`); the loader sniffs the PK
//!     magic and pulls the `*.csv` member out through the zipwalk
//!     central-directory walk, so a plaintext `rank,domain` file
//!     works too (fixtures, hand-drops);
//!   * `<rank dir>/majestic-million.csv` — the Majestic Million
//!     second rank source (~80MB — stream-parsed line-wise, never
//!     loaded whole): `GlobalRank,TldRank,Domain,...` rows.
//!
//! Merge: the effective rank is the BEST (lowest) rank across all
//! sources — Tranco's aggregate and Majestic's backlink-based
//! ranking measure different signals, and membership in either
//! counts. An update can never un-rank a domain and a source that
//! stops listing one simply stops contributing — the vendored seed
//! alone keeps the feature alive.
//!
//! Update model (mirroring the desktop `TrancoList`): Kotlin owns
//! the fetch (OkHttp, https-only, loopback exempt for fixtures) and
//! commits each body under its own file name; the engine reports
//! per-source last-update stamps from the drop files' mtimes, so a
//! deleted copy reads as "never" again. `site_rank_update_if_stale`
//! is the consent-gated automatic path; `site_rank_update_now` is
//! the explicit consent-free one — both return the source list the
//! shell must fetch, and the shell calls `site_rank_reload` after
//! committing.
//!
//! The merged table lives behind a global Arc swap so in-crate
//! consumers (a later "top-N site" policy inside the blocklist
//! engine / allowlist) can probe it without crossing the FFI.

use std::collections::HashMap;
use std::fs::File;
use std::io::{BufRead, BufReader, Read};
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};
use std::time::UNIX_EPOCH;

use crate::util::{valid_dns_shape, HostNorm};
use crate::zipwalk::zip_member_by_suffix;

/// Updated-list drop points inside the rank dir — the Kotlin
/// `RankListLoader` writes these files and calls
/// `site_rank_reload()`.
pub const TRANCO_FILE: &str = "tranco-list.dat";
pub const MAJESTIC_FILE: &str = "majestic-million.csv";

/// Vendored seed, compiled in so ranking works with no rank dir and
/// no network at all.
const BUILTIN_LIST: &str = include_str!("../data/tranco-top100k.csv");

/// Bounds — same values the desktop applies: 2M entries, 512-byte
/// lines, 64MB inflated zip member, 128MB override file
/// (majestic_million.csv is ~80MB today).
const MAX_ENTRIES: usize = 2_000_000;
const MAX_LINE: usize = 512;
const MAX_MEMBER: usize = 64 * 1024 * 1024;
const MAX_FILE: u64 = 128 * 1024 * 1024;

/// Refresh cadence — the same 7 days the adblock subscriptions use.
const REFRESH_INTERVAL_MS: u64 = 7 * 24 * 60 * 60 * 1000;

/// Flat error surface, matching the crate's `StorageError` style.
#[derive(Debug, uniffi::Error)]
#[uniffi(flat_error)]
pub enum RankListError {
    Corrupt(String),
}

impl std::fmt::Display for RankListError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RankListError::Corrupt(msg) => write!(f, "rank list corrupt: {msg}"),
        }
    }
}

/// Which rank source a row refers to — selects the drop file and
/// the grammar it parses under.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RankListSourceKind {
    Tranco,
    Majestic,
}

/// One remote rank list: the URL the shell fetches, the file name it
/// commits the body under inside the rank dir, and the last-update
/// stamp (ms since epoch, 0 = never fetched). Mirrors the desktop
/// `TrancoList::Source` + QSettings stamp pair.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RankListSource {
    pub kind: RankListSourceKind,
    /// Display name for the settings row.
    pub name: String,
    /// Remote fetch URL (https-only; loopback http is the fixture
    /// exception, enforced Kotlin-side).
    pub url: String,
    /// Drop file name inside the rank dir.
    pub file_name: String,
    /// ms since epoch of the last committed copy, 0 = never.
    pub last_update_ms: u64,
}

/// The loaded table — one merged `domain -> best rank` map.
#[derive(Debug)]
struct RankTable {
    ranks: HashMap<String, u32>,
}

/// The active table — Option so the seed parse only happens on first
/// use; `Arc` so a `rank` call in flight survives a reload swapping
/// the map under it.
static ACTIVE: RwLock<Option<Arc<RankTable>>> = RwLock::new(None);

/// The rank dir the Kotlin shell drops updated files into. Set via
/// `site_rank_set_data_dir` at init (the app data dir may not be
/// known when the library loads).
static DATA_DIR: RwLock<Option<PathBuf>> = RwLock::new(None);

#[cfg(test)]
fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

// ------------------------------------------------------------------
// Row grammars (desktop tranco.rs parity)
// ------------------------------------------------------------------

/// Conservative gate: the desktop's `valid_dns_shape` plus no IP
/// literals — rank lists name domains only.
fn validate_domain(host: &str) -> bool {
    valid_dns_shape(host) && host.parse::<IpAddr>().is_err()
}

/// One (domain, rank) entry out of one Tranco-grammar line, or None
/// for comments/junk. `ordinal` is the line's 1-based position and
/// becomes the rank of a bare-domain row.
fn entry_from_line(line: &str, ordinal: u32) -> Option<(String, u32)> {
    let line = line.trim();
    if line.len() > MAX_LINE || line.is_empty() {
        return None;
    }
    match line.as_bytes()[0] {
        b'#' | b'!' | b';' | b'[' | b'/' => return None,
        _ => {}
    }
    let (rank_tok, domain_tok) = match line.split_once(',') {
        Some((r, d)) => (r.trim(), d.trim()),
        None => ("", line),
    };
    let rank = if rank_tok.is_empty() {
        ordinal
    } else {
        // Rank 0 collides with the "unranked" FFI sentinel and is no
        // Tranco rank anyway.
        match rank_tok.parse::<u32>() {
            Ok(r) if r > 0 => r,
            _ => return None,
        }
    };
    // A second comma means the row is not the rank,domain shape —
    // CSV quoting games are junk here, not a domain.
    if domain_tok.contains(',') {
        return None;
    }
    let host = domain_tok.trim_end_matches('.').to_lowercase();
    validate_domain(&host).then_some((host, rank))
}

/// One (domain, rank) entry out of one Majestic Million CSV row, or
/// None for the header/comments/junk. The upstream shape is
/// `GlobalRank,TldRank,Domain,TLD,...` — rank is field 0, the domain
/// field 2.
fn majestic_entry_from_line(line: &str) -> Option<(String, u32)> {
    let line = line.trim();
    if line.len() > MAX_LINE || line.is_empty() {
        return None;
    }
    match line.as_bytes()[0] {
        b'#' | b'!' | b';' | b'[' | b'/' => return None,
        _ => {}
    }
    let mut fields = line.split(',');
    let rank: u32 = fields.next()?.trim().parse().ok()?;
    if rank == 0 {
        return None;
    }
    fields.next()?; // TldRank
    let host = fields.next()?.trim().trim_end_matches('.').to_lowercase();
    validate_domain(&host).then_some((host, rank))
}

// ------------------------------------------------------------------
// Parse + merge
// ------------------------------------------------------------------

/// Fold every parsed row of `lines` into `ranks`, best-rank-wins.
/// Returns the count of usable entries seen (pre-merge — a file whose
/// domains all duplicate the seed still counts as a healthy parse).
fn absorb_lines<S: AsRef<str>>(
    ranks: &mut HashMap<String, u32>,
    lines: impl Iterator<Item = S>,
    majestic: bool,
) -> usize {
    let mut parsed = 0usize;
    for (i, line) in lines.enumerate() {
        if parsed >= MAX_ENTRIES {
            break;
        }
        let entry = if majestic {
            majestic_entry_from_line(line.as_ref())
        } else {
            entry_from_line(line.as_ref(), i as u32 + 1)
        };
        if let Some((host, rank)) = entry {
            parsed += 1;
            ranks
                .entry(host)
                .and_modify(|r| *r = (*r).min(rank))
                .or_insert(rank);
        }
    }
    parsed
}

/// Fold an in-memory body into `ranks` — used for zip members and
/// test fixtures. Lossy UTF-8 like the desktop (bad bytes can't
/// forge a domain through the ASCII gate).
fn absorb_blob(ranks: &mut HashMap<String, u32>, blob: &[u8], majestic: bool) -> usize {
    let text;
    let body = if !majestic && blob.starts_with(b"PK\x03\x04") {
        match zip_member_by_suffix(blob, ".csv", MAX_MEMBER) {
            Ok(member) => {
                text = String::from_utf8_lossy(&member).into_owned();
                text.as_str()
            }
            Err(_) => return 0,
        }
    } else {
        match std::str::from_utf8(blob) {
            Ok(s) => s,
            Err(_) => {
                text = String::from_utf8_lossy(blob).into_owned();
                text.as_str()
            }
        }
    };
    absorb_lines(ranks, body.lines(), majestic)
}

/// Fold one rank-dir file into `ranks`. Plaintext files (incl. the
/// ~80MB Majestic CSV) stream line-wise — never loaded whole; a PK
/// magic file is the Tranco zip and goes through the bounded
/// central-directory walk instead. Returns the usable-entry count
/// (0 = absent, unreadable, oversized or junk — it contributes
/// nothing either way).
fn absorb_file(ranks: &mut HashMap<String, u32>, path: &Path, majestic: bool) -> usize {
    let meta = std::fs::metadata(path).ok();
    match meta {
        Some(m) if m.is_file() && m.len() <= MAX_FILE => {}
        _ => return 0,
    }
    let is_zip = !majestic
        && File::open(path)
            .and_then(|mut f| {
                let mut magic = [0u8; 4];
                f.read_exact(&mut magic).map(|_| magic == *b"PK\x03\x04")
            })
            .unwrap_or(false);
    if is_zip {
        return std::fs::read(path)
            .map(|blob| absorb_blob(ranks, &blob, false))
            .unwrap_or(0);
    }
    let file = match File::open(path) {
        Ok(f) => f,
        Err(_) => return 0,
    };
    // Byte-split lines + per-line lossy decode — a `BufRead::lines`
    // hard-UTF-8 error would kill the tail of an otherwise good file,
    // while a corrupt byte alone can't forge a domain.
    let lines = BufReader::new(file)
        .split(b'\n')
        .map_while(Result::ok)
        .map(|v| String::from_utf8_lossy(&v).into_owned());
    absorb_lines(ranks, lines, majestic)
}

/// Seed ∪ both drop files, best-rank-wins across every source —
/// never fails: absent/unreadable/junk files simply contribute
/// nothing, so a bad download degrades to "no new coverage".
fn load_table(dir: Option<&Path>) -> RankTable {
    let mut ranks = HashMap::new();
    absorb_lines(&mut ranks, BUILTIN_LIST.lines(), false);
    if let Some(dir) = dir {
        absorb_file(&mut ranks, &dir.join(TRANCO_FILE), false);
        absorb_file(&mut ranks, &dir.join(MAJESTIC_FILE), true);
    }
    RankTable { ranks }
}

impl RankTable {
    /// The host's rank, or None when unlisted. The probe walks the
    /// hostname's label boundaries left-to-right: the exact host
    /// first, then each parent domain — the most specific listed
    /// ancestor wins (Tranco entries are pay-level domains, so the
    /// first hit going up is the registrable name). Allocation-free
    /// via `HostNorm`'s stack buffer.
    fn rank_of(&self, host: &str) -> Option<u32> {
        let h = HostNorm::new(host)?;
        let host = h.as_str();
        // IP literals and dot-less names are never ranked.
        if host.parse::<IpAddr>().is_ok() || !host.contains('.') {
            return None;
        }
        for candidate in h.suffixes() {
            // Entries all contain a dot — the TLD alone is never an
            // answer.
            if !candidate.contains('.') {
                return None;
            }
            if candidate.parse::<IpAddr>().is_err() {
                if let Some(&rank) = self.ranks.get(candidate) {
                    return Some(rank);
                }
            }
        }
        None
    }
}

fn data_dir() -> Option<PathBuf> {
    DATA_DIR.read().unwrap().clone()
}

/// The loaded table, lazily populated (seed ∪ drop files) on first
/// use; a `set_data_dir` arriving late still picks up overrides on
/// the first probe after it.
fn active_table() -> Arc<RankTable> {
    if let Some(t) = ACTIVE.read().unwrap().as_ref() {
        return t.clone();
    }
    let mut guard = ACTIVE.write().unwrap();
    if let Some(t) = guard.as_ref() {
        return t.clone();
    }
    let table = Arc::new(load_table(data_dir().as_deref()));
    *guard = Some(table.clone());
    table
}

/// In-crate probe for future consumers (blocklist "top-N site"
/// policy, allowlist trust) — `None` when unranked. Never fails on
/// content: a malformed host simply matches nothing.
#[allow(dead_code)]
pub(crate) fn rank_of(host: &str) -> Option<u32> {
    active_table().rank_of(host)
}

// ------------------------------------------------------------------
// UniFFI surface
// ------------------------------------------------------------------

/// The two remote rank lists — the desktop `TrancoList::defaultSources`
/// pair. `last_update_ms` comes from each drop file's mtime: a written-
/// then-kept file's mtime IS its commit stamp, and a deleted copy
/// reads as "never" again — the same truth the desktop's QSettings
/// stamps encode.
fn sources() -> [RankListSource; 2] {
    [
        RankListSource {
            kind: RankListSourceKind::Tranco,
            name: "Tranco top-1M".to_owned(),
            url: "https://tranco-list.eu/top-1m.csv.zip".to_owned(),
            file_name: TRANCO_FILE.to_owned(),
            last_update_ms: 0,
        },
        RankListSource {
            kind: RankListSourceKind::Majestic,
            name: "Majestic Million".to_owned(),
            url: "https://downloads.majestic.com/majestic_million.csv".to_owned(),
            file_name: MAJESTIC_FILE.to_owned(),
            last_update_ms: 0,
        },
    ]
}

fn file_stamp_ms(dir: &Path, name: &str) -> u64 {
    std::fs::metadata(dir.join(name))
        .ok()
        .and_then(|m| m.modified().ok())
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Point the engine at the rank dir (Kotlin: app data dir under
/// files). Rebuilds the merged table best-effort — a corrupt drop
/// file keeps the previous table instead of disabling ranking.
#[uniffi::export]
pub fn site_rank_set_data_dir(dir: String) {
    *DATA_DIR.write().unwrap() = Some(PathBuf::from(dir));
    let _ = site_rank_reload();
}

/// The two rank-list sources with their current last-update stamps —
/// the rows a settings screen shows.
#[uniffi::export]
pub fn site_rank_sources() -> Vec<RankListSource> {
    let dir = data_dir();
    let mut out = sources().to_vec();
    if let Some(dir) = dir {
        for s in &mut out {
            s.last_update_ms = file_stamp_ms(&dir, &s.file_name);
        }
    }
    out
}

/// ms-since-epoch stamp of one source's committed copy; 0 = never.
#[uniffi::export]
pub fn site_rank_last_update(kind: RankListSourceKind) -> u64 {
    let name = match kind {
        RankListSourceKind::Tranco => TRANCO_FILE,
        RankListSourceKind::Majestic => MAJESTIC_FILE,
    };
    data_dir()
        .map(|dir| file_stamp_ms(&dir, name))
        .unwrap_or(0)
}

/// Consent-gated automatic update (desktop `updateIfStale`): the
/// sources to fetch when remote-list consent was granted AND a copy
/// is missing or older than the refresh cadence; empty otherwise.
/// `now_ms` comes from the caller so staleness stays testable.
#[uniffi::export]
pub fn site_rank_update_if_stale(consent_granted: bool, now_ms: u64) -> Vec<RankListSource> {
    if !consent_granted {
        return Vec::new();
    }
    let all = site_rank_sources();
    // The honest answer is the stalest list — a source that never
    // fetched is older than any date, so either side missing stamps
    // the whole set "never" and triggers a refetch of everything
    // (desktop: min over per-source stamps, invalid if any absent).
    let stale = all.iter().any(|s| s.last_update_ms == 0)
        || all
            .iter()
            .map(|s| s.last_update_ms)
            .min()
            .map(|oldest| oldest.saturating_add(REFRESH_INTERVAL_MS) < now_ms)
            .unwrap_or(true);
    if stale {
        all
    } else {
        Vec::new()
    }
}

/// Explicit update (desktop `updateNow`) — consent-free; returns the
/// full source list to fetch.
#[uniffi::export]
pub fn site_rank_update_now() -> Vec<RankListSource> {
    site_rank_sources()
}

/// Usable-entry count of one rank-dir drop file, parsed under the
/// grammar its name selects (majestic-million.csv → columnar CSV,
/// anything else → Tranco `rank,domain`/zip). The Kotlin updater's
/// per-file validation probe: a committed body yielding zero entries
/// is junk and must be dropped, not merged over a good copy.
#[uniffi::export]
pub fn site_rank_file_entries(name: String) -> u64 {
    let Some(dir) = data_dir() else { return 0 };
    let mut ranks = HashMap::new();
    absorb_file(&mut ranks, &dir.join(&name), name == MAJESTIC_FILE) as u64
}

/// Re-run the seed ∪ drop-file merge after an update lands. Errors
/// when an EXISTING drop file yields zero usable entries (corrupt
/// zip, error page) — the previous table stays active and the caller
/// sweeps the junk file before retrying.
#[uniffi::export]
pub fn site_rank_reload() -> Result<(), RankListError> {
    let dir = data_dir();
    let mut ranks = HashMap::new();
    absorb_lines(&mut ranks, BUILTIN_LIST.lines(), false);
    if let Some(dir) = &dir {
        for (name, majestic) in [(TRANCO_FILE, false), (MAJESTIC_FILE, true)] {
            let path = dir.join(name);
            if path.exists() && absorb_file(&mut ranks, &path, majestic) == 0 {
                return Err(RankListError::Corrupt(format!(
                    "{name} yields no usable entries"
                )));
            }
        }
    }
    *ACTIVE.write().unwrap() = Some(Arc::new(RankTable { ranks }));
    Ok(())
}

/// The host's site rank; 0 when unranked (ranks start at 1). Lazy —
/// the seed ∪ drop merge builds on the first call. This is the
/// consumer seam: `SmartCookieWebClient`-side callers can consult it
/// on demand, keeping JNI off the per-request hot path.
#[uniffi::export]
pub fn site_rank(host: String) -> u32 {
    active_table().rank_of(&host).unwrap_or(0)
}

/// Entry count of the active table — diagnostics/settings.
#[uniffi::export]
pub fn site_rank_entry_count() -> u64 {
    active_table().ranks.len() as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Mutex, MutexGuard};

    /// The active table + data dir are process-global — serialize the
    /// tests that touch them.
    static TEST_LOCK: Mutex<()> = Mutex::new(());

    fn guard() -> MutexGuard<'static, ()> {
        TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn temp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "arktos-rank-{tag}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn table(text: &str) -> RankTable {
        let mut ranks = HashMap::new();
        absorb_lines(&mut ranks, text.lines(), false);
        RankTable { ranks }
    }

    /// Minimal zip container holding one stored-or-deflated member —
    /// enough for the update-path tests without a zip crate.
    fn test_zip(name: &str, body: &[u8], deflate: bool) -> Vec<u8> {
        let (method, stored): (u16, Vec<u8>) = if deflate {
            (8, miniz_oxide::deflate::compress_to_vec(body, 6))
        } else {
            (0, body.to_vec())
        };
        let uncomp = body.len() as u32;
        let comp = stored.len() as u32;
        let name_b = name.as_bytes();
        let mut zip = Vec::new();
        // Local file header
        zip.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        zip.extend_from_slice(&20u16.to_le_bytes()); // version
        zip.extend_from_slice(&0u16.to_le_bytes()); // flags
        zip.extend_from_slice(&method.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes()); // mod time
        zip.extend_from_slice(&0u16.to_le_bytes()); // mod date
        zip.extend_from_slice(&0u32.to_le_bytes()); // crc32 (unchecked)
        zip.extend_from_slice(&comp.to_le_bytes());
        zip.extend_from_slice(&uncomp.to_le_bytes());
        zip.extend_from_slice(&(name_b.len() as u16).to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes()); // extra len
        zip.extend_from_slice(name_b);
        zip.extend_from_slice(&stored);
        // Central directory
        let cd_off = zip.len() as u32;
        zip.extend_from_slice(&0x0201_4b50u32.to_le_bytes());
        zip.extend_from_slice(&20u16.to_le_bytes()); // version made by
        zip.extend_from_slice(&20u16.to_le_bytes()); // version needed
        zip.extend_from_slice(&0u16.to_le_bytes()); // flags
        zip.extend_from_slice(&method.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u32.to_le_bytes()); // crc32
        zip.extend_from_slice(&comp.to_le_bytes());
        zip.extend_from_slice(&uncomp.to_le_bytes());
        zip.extend_from_slice(&(name_b.len() as u16).to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes()); // extra
        zip.extend_from_slice(&0u16.to_le_bytes()); // comment
        zip.extend_from_slice(&0u16.to_le_bytes()); // disk
        zip.extend_from_slice(&0u16.to_le_bytes()); // internal attrs
        zip.extend_from_slice(&0u32.to_le_bytes()); // external attrs
        zip.extend_from_slice(&0u32.to_le_bytes()); // local header off
        zip.extend_from_slice(name_b);
        let cd_size = zip.len() as u32 - cd_off;
        // EOCD
        zip.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes()); // disk
        zip.extend_from_slice(&0u16.to_le_bytes()); // cd disk
        zip.extend_from_slice(&1u16.to_le_bytes()); // entries this disk
        zip.extend_from_slice(&1u16.to_le_bytes()); // entries total
        zip.extend_from_slice(&cd_size.to_le_bytes());
        zip.extend_from_slice(&cd_off.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes()); // comment len
        zip
    }

    #[test]
    fn parses_every_row_shape() {
        let t = table(
            "# comment\n\
             ! comment\n\
             ; comment\n\
             // comment\n\
             [section]\n\
             \n\
             1,google.com\n\
             2, example.org \n\
             bare.example.net\n\
             0,zerorank.example\n\
             notarank,junk.example\n\
             4,still-ok.example\n\
             5,1.2.3.4\n\
             6,localhost\n\
             7,sub,comma.example\n",
        );
        assert_eq!(t.rank_of("google.com"), Some(1));
        assert_eq!(t.rank_of("example.org"), Some(2));
        assert_eq!(t.rank_of("bare.example.net"), Some(9));
        assert_eq!(t.rank_of("still-ok.example"), Some(4));
        // rank-0 / non-numeric-rank / IP / single-label / extra-comma
        // rows all dropped.
        assert_eq!(t.ranks.len(), 4);
    }

    #[test]
    fn rank_probes_exact_then_registrable() {
        let t = table("10,google.com\n20,sub.shop.example\n");
        assert_eq!(t.rank_of("google.com"), Some(10));
        // Unlisted subdomain walks up to the listed parent.
        assert_eq!(t.rank_of("www.google.com"), Some(10));
        assert_eq!(t.rank_of("a.b.c.google.com"), Some(10));
        // A listed subdomain's own rank wins over the parent's.
        assert_eq!(t.rank_of("sub.shop.example"), Some(20));
        assert_eq!(t.rank_of("deep.sub.shop.example"), Some(20));
        // The parent alone is not listed — a listed subdomain never
        // reaches up.
        assert_eq!(t.rank_of("shop.example"), None);
        // Label-boundary only.
        assert_eq!(t.rank_of("notgoogle.com"), None);
        assert_eq!(t.rank_of("google.com.evil.example"), None);
        // Normalization: case, trailing dot, brackets/IPs, junk.
        assert_eq!(t.rank_of("WWW.GOOGLE.COM."), Some(10));
        assert_eq!(t.rank_of("1.2.3.4"), None);
        assert_eq!(t.rank_of("[2001:db8::1]"), None);
        assert_eq!(t.rank_of("localhost"), None);
        assert_eq!(t.rank_of(""), None);
        assert_eq!(t.rank_of(&"a".repeat(300)), None);
    }

    #[test]
    fn builtin_seed_parses_and_ranks() {
        let t = table(BUILTIN_LIST);
        assert!(t.ranks.len() >= 100_000);
        assert_eq!(t.rank_of("google.com"), Some(1));
        assert!(t.rank_of("facebook.com").is_some());
        assert_eq!(t.rank_of("www.google.com"), Some(1));
        assert_eq!(t.rank_of("definitely-not-ranked.invalid"), None);
    }

    #[test]
    fn update_blob_accepts_plaintext_and_zip() {
        let csv = b"7,updated.example\n";
        let mut ranks = HashMap::new();
        assert_eq!(absorb_blob(&mut ranks, csv, false), 1);
        // Stored member.
        let zip_store = test_zip("top-1m.csv", csv, false);
        let mut ranks = HashMap::new();
        assert_eq!(absorb_blob(&mut ranks, &zip_store, false), 1);
        assert_eq!(ranks.get("updated.example"), Some(&7));
        // Deflated member — the real top-1m.csv.zip shape.
        let zip_defl = test_zip("top-1m.csv", csv, true);
        let mut ranks = HashMap::new();
        assert_eq!(absorb_blob(&mut ranks, &zip_defl, false), 1);
        assert_eq!(ranks.get("updated.example"), Some(&7));
        // A zip with no .csv member and a garbage blob both parse
        // empty — the caller treats that as "no usable update".
        let zip_other = test_zip("readme.txt", csv, false);
        assert_eq!(absorb_blob(&mut HashMap::new(), &zip_other, false), 0);
        assert_eq!(absorb_blob(&mut HashMap::new(), b"\x00\x01\x02garbage", false), 0);
        assert_eq!(absorb_blob(&mut HashMap::new(), b"PK\x03\x04truncated", false), 0);
    }

    #[test]
    fn majestic_blob_parses_columns() {
        // The real majestic_million.csv shape: header row, then
        // GlobalRank,TldRank,Domain,TLD,... — fields 0 and 2 carry
        // the entry.
        let mut ranks = HashMap::new();
        let parsed = absorb_blob(
            &mut ranks,
            b"GlobalRank,TldRank,Domain,TLD,RefSubNets,RefIPs,IDN_Domain,IDN_TLD,PrevGlobalRank,PrevTldRank,PrevRefSubNets,PrevRefIPs\n\
              1,1,google.com,com,508408,2224152,google.com,com,1,1,508504,2225468\n\
              42,9,mj.example,example,10,20,mj.example,example,40,8,11,22\n\
              # comment\n\
              \n\
              7,junk.example,example\n\
              0,1,zerorank.example,example\n\
              9,1,1.2.3.4,com\n\
              xx,1,notarank.example,com\n",
            true,
        );
        assert_eq!(ranks.get("google.com"), Some(&1));
        assert_eq!(ranks.get("mj.example"), Some(&42));
        // Short row (no Domain field), rank-0, IP literal and
        // non-numeric-rank rows all drop out.
        assert_eq!(parsed, 2);
        // The majestic grammar never sniffs zip containers.
        let zip = test_zip("majestic_million.csv", b"5,z.example\n", false);
        assert_eq!(absorb_blob(&mut HashMap::new(), &zip, true), 0);
    }

    #[test]
    fn merge_takes_best_rank_across_lists() {
        // The effective rank is the best (lowest) rank any source
        // assigns — an update can improve a rank but never un-rank
        // anything, and coverage only grows.
        let mut ranks = HashMap::new();
        absorb_lines(&mut ranks, "5,seed.example\n9,also-seed.example\n".lines(), false);
        let seed_len = ranks.len();
        absorb_blob(&mut ranks, b"1,seed.example\n2,new.example\n", false);
        absorb_blob(
            &mut ranks,
            b"GlobalRank,TldRank,Domain,TLD\n7,3,seed.example,example\n8,4,mj-only.example,example\n",
            true,
        );
        let t = RankTable { ranks };
        assert_eq!(t.rank_of("seed.example"), Some(1)); // tranco update bested seed+majestic
        assert_eq!(t.rank_of("also-seed.example"), Some(9)); // no source can un-rank
        assert_eq!(t.rank_of("new.example"), Some(2));
        assert_eq!(t.rank_of("mj-only.example"), Some(8)); // membership in either counts
        assert_eq!(t.rank_of("never-listed.example"), None);
        assert_eq!(t.ranks.len(), seed_len + 2);
    }

    #[test]
    fn reload_picks_up_datadir_overrides() {
        let _g = guard();
        let dir = temp_dir("reload");
        site_rank_set_data_dir(dir.to_str().unwrap().to_owned());
        // A plaintext override adds ranks on top of the seed.
        std::fs::write(dir.join(TRANCO_FILE), "42,added-by-update.example\n").unwrap();
        site_rank_reload().unwrap();
        assert_eq!(site_rank("added-by-update.example".to_owned()), 42);
        assert_eq!(site_rank("google.com".to_owned()), 1); // seed coverage intact
        // A poisoned override trying to drop seed coverage can't.
        std::fs::write(dir.join(TRANCO_FILE), "999999,google.com\n").unwrap();
        site_rank_reload().unwrap();
        assert_eq!(site_rank("google.com".to_owned()), 1);
        // An empty override is Corrupt — previous table stays.
        std::fs::write(dir.join(TRANCO_FILE), "# nothing usable\n").unwrap();
        assert!(site_rank_reload().is_err());
        assert_eq!(site_rank("google.com".to_owned()), 1);
        std::fs::remove_file(dir.join(TRANCO_FILE)).unwrap();
        site_rank_reload().unwrap();
        assert_eq!(site_rank("added-by-update.example".to_owned()), 0);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn majestic_file_merges_on_reload() {
        let _g = guard();
        let dir = temp_dir("majestic");
        site_rank_set_data_dir(dir.to_str().unwrap().to_owned());
        let tranco_path = dir.join(TRANCO_FILE);
        let majestic_path = dir.join(MAJESTIC_FILE);
        std::fs::write(&tranco_path, "50,both-lists.example\n").unwrap();
        std::fs::write(
            &majestic_path,
            "GlobalRank,TldRank,Domain,TLD\n\
             12,4,both-lists.example,example\n\
             77,9,mj-only.example,example\n",
        )
        .unwrap();
        // Per-file probe: usable-entry counts under each grammar.
        assert_eq!(site_rank_file_entries(TRANCO_FILE.to_owned()), 1);
        assert_eq!(site_rank_file_entries(MAJESTIC_FILE.to_owned()), 2);
        assert_eq!(site_rank_file_entries("missing-file.csv".to_owned()), 0);
        site_rank_reload().unwrap();
        // Best rank across the lists wins; either-source membership
        // counts; an unlisted domain stays unranked.
        assert_eq!(site_rank("both-lists.example".to_owned()), 12);
        assert_eq!(site_rank("mj-only.example".to_owned()), 77);
        assert_eq!(site_rank("unlisted-still.example".to_owned()), 0);
        assert_eq!(site_rank("google.com".to_owned()), 1); // seed coverage intact
        // A corrupt sibling override fails the reload — the shell
        // sweeps it before retrying.
        std::fs::write(&majestic_path, "<html>404</html>\n").unwrap();
        assert!(site_rank_reload().is_err());
        assert_eq!(site_rank("mj-only.example".to_owned()), 77); // previous table stays
        std::fs::remove_file(&majestic_path).unwrap();
        std::fs::remove_file(&tranco_path).unwrap();
        site_rank_reload().unwrap();
        assert_eq!(site_rank("mj-only.example".to_owned()), 0);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn zip_dropfile_merges_on_reload() {
        let _g = guard();
        let dir = temp_dir("zip");
        site_rank_set_data_dir(dir.to_str().unwrap().to_owned());
        // The real update path: the downloader commits the zip body
        // verbatim and the loader pulls the .csv member out.
        let zip = test_zip("top-1m.csv", b"33,zipped.example\n", true);
        std::fs::write(dir.join(TRANCO_FILE), zip).unwrap();
        site_rank_reload().unwrap();
        assert_eq!(site_rank("zipped.example".to_owned()), 33);
        assert_eq!(site_rank_file_entries(TRANCO_FILE.to_owned()), 1);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn update_decision_gates_on_consent_and_staleness() {
        let _g = guard();
        let dir = temp_dir("stale");
        site_rank_set_data_dir(dir.to_str().unwrap().to_owned());
        let now = now_millis();
        // No consent → never anything to fetch.
        assert!(site_rank_update_if_stale(false, now).is_empty());
        // Missing copies → stale, both sources listed.
        assert_eq!(site_rank_update_if_stale(true, now).len(), 2);
        // updateNow is consent-free.
        assert_eq!(site_rank_update_now().len(), 2);
        // Fresh copies → nothing to fetch at `now`...
        std::fs::write(dir.join(TRANCO_FILE), "1,a.example\n").unwrap();
        std::fs::write(dir.join(MAJESTIC_FILE), "1,1,a.example,a\n").unwrap();
        assert!(site_rank_update_if_stale(true, now).is_empty());
        // ...but stale once the refresh interval has lapsed.
        assert_eq!(
            site_rank_update_if_stale(true, now + REFRESH_INTERVAL_MS + 1).len(),
            2
        );
        // One missing copy marks the whole set stale.
        std::fs::remove_file(dir.join(MAJESTIC_FILE)).unwrap();
        assert_eq!(site_rank_update_if_stale(true, now).len(), 2);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn per_source_stamps_follow_file_lifetime() {
        let _g = guard();
        let dir = temp_dir("stamps");
        site_rank_set_data_dir(dir.to_str().unwrap().to_owned());
        assert_eq!(site_rank_last_update(RankListSourceKind::Tranco), 0);
        std::fs::write(dir.join(TRANCO_FILE), "1,a.example\n").unwrap();
        let stamp = site_rank_last_update(RankListSourceKind::Tranco);
        assert!(stamp > 0);
        assert_eq!(
            site_rank_sources()
                .iter()
                .find(|s| s.kind == RankListSourceKind::Tranco)
                .unwrap()
                .last_update_ms,
            stamp
        );
        assert_eq!(site_rank_last_update(RankListSourceKind::Majestic), 0);
        std::fs::remove_file(dir.join(TRANCO_FILE)).unwrap();
        assert_eq!(site_rank_last_update(RankListSourceKind::Tranco), 0);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn sources_are_the_desktop_defaults() {
        let all = sources();
        assert_eq!(all[0].kind, RankListSourceKind::Tranco);
        assert_eq!(all[0].file_name, TRANCO_FILE);
        assert_eq!(all[1].kind, RankListSourceKind::Majestic);
        assert_eq!(all[1].file_name, MAJESTIC_FILE);
        assert!(all.iter().all(|s| s.url.starts_with("https://")));
    }
}
