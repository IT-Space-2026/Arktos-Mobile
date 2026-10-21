// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
//! Minimal zip central-directory walk (RANK01) — ported from the
//! desktop rustcore `extverify.rs`, trimmed to the one consumer the
//! mobile crate has: pulling the `*.csv` member out of the Tranco
//! `top-1m.csv.zip` download without a full zip crate. Bounds and
//! rejection rules are kept 1:1.

/// Central-directory / member caps, same as desktop.
const MAX_ZIP_ENTRIES: u64 = 4096;

fn le16(b: &[u8], off: usize) -> u16 {
    u16::from_le_bytes([b[off], b[off + 1]])
}

fn le32(b: &[u8], off: usize) -> u32 {
    u32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}

/// The stored bytes of one member: local header skipped, bounded to
/// the archive.
fn member_data(zip: &[u8], local_off: u32, comp_size: u32) -> Result<&[u8], String> {
    let off = local_off as usize;
    if off + 30 > zip.len() || le32(zip, off) != 0x0403_4b50 {
        return Err("member local header missing".into());
    }
    let name_len = le16(zip, off + 26) as usize;
    let extra_len = le16(zip, off + 28) as usize;
    let data = off
        .checked_add(30)
        .and_then(|v| v.checked_add(name_len))
        .and_then(|v| v.checked_add(extra_len))
        .ok_or_else(|| "member offsets overflow".to_string())?;
    let end = data
        .checked_add(comp_size as usize)
        .ok_or_else(|| "member size overflows".to_string())?;
    if end > zip.len() {
        return Err("member data extends past end of archive".into());
    }
    Ok(&zip[data..end])
}

/// One parsed central-directory entry — offsets point at the stored
/// member the archive maps it to.
struct CentralEntry<'a> {
    name: &'a [u8],
    method: u16,
    comp_size: u32,
    uncomp_size: u32,
    local_off: u32,
}

/// True when `name` must never reach an extractor: absolute paths,
/// drive letters, NUL bytes and `..` components (both separators).
fn unsafe_member_name(name: &[u8]) -> bool {
    if name.is_empty() || name.contains(&0) {
        return true;
    }
    if name[0] == b'/' || name[0] == b'\\' {
        return true;
    }
    if name.len() >= 2 && name[1] == b':' && name[0].is_ascii_alphabetic() {
        return true;
    }
    name.split(|&c| c == b'/' || c == b'\\')
        .any(|comp| comp == b"..")
}

/// Locates the end-of-central-directory record and yields every
/// central-directory entry in order.
fn walk_central_dir(
    zip: &[u8],
    mut visit: impl FnMut(&CentralEntry) -> Result<(), String>,
) -> Result<(), String> {
    // End of central directory: signature 50 4b 05 06, fixed 22-byte
    // record plus a comment of up to 64 KiB — scan the tail window.
    if zip.len() < 22 {
        return Err("not a zip archive (no end of central directory)".into());
    }
    let window = zip.len().min(22 + 0xFFFF);
    let tail = &zip[zip.len() - window..];
    let mut eocd = None;
    for i in (0..tail.len() - 3).rev() {
        if tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 0x05 && tail[i + 3] == 0x06 {
            eocd = Some(zip.len() - window + i);
            break;
        }
    }
    let eocd = eocd
        .ok_or_else(|| "not a zip archive (no end of central directory)".to_string())?;
    if eocd + 22 > zip.len() {
        return Err("end of central directory is truncated".into());
    }
    let comment_len = le16(zip, eocd + 20) as usize;
    if eocd + 22 + comment_len != zip.len() {
        return Err("trailing data after the end of central directory".into());
    }
    if le16(zip, eocd + 4) != 0 || le16(zip, eocd + 6) != 0 {
        return Err("multi-disk archives are not supported".into());
    }
    let entries = le16(zip, eocd + 10) as u64;
    if le16(zip, eocd + 8) as u64 != entries {
        return Err("central directory spans multiple disks".into());
    }
    let cd_size = le32(zip, eocd + 12) as usize;
    let cd_offset = le32(zip, eocd + 16) as usize;
    if entries == 0xFFFF || cd_size == 0xFFFF_FFFF || cd_offset == 0xFFFF_FFFF {
        return Err("zip64 archives are not supported".into());
    }
    if entries > MAX_ZIP_ENTRIES {
        return Err(format!("too many entries ({entries})"));
    }
    let cd_end = cd_offset
        .checked_add(cd_size)
        .ok_or_else(|| "central directory offsets overflow".to_string())?;
    if cd_end > eocd {
        return Err("central directory overlaps the record trailer".into());
    }

    let mut pos = cd_offset;
    for _ in 0..entries {
        if pos + 46 > cd_end || le32(zip, pos) != 0x0201_4b50 {
            return Err("central directory entry is truncated".into());
        }
        let entry = CentralEntry {
            method: le16(zip, pos + 10),
            comp_size: le32(zip, pos + 20),
            uncomp_size: le32(zip, pos + 24),
            local_off: le32(zip, pos + 42),
            name: &[],
        };
        let name_len = le16(zip, pos + 28) as usize;
        let extra_len = le16(zip, pos + 30) as usize;
        let cmt_len = le16(zip, pos + 32) as usize;
        if entry.comp_size == 0xFFFF_FFFF
            || entry.uncomp_size == 0xFFFF_FFFF
            || entry.local_off == 0xFFFF_FFFF
        {
            return Err("zip64 entries are not supported".into());
        }
        let name_end = pos + 46 + name_len;
        if name_end > cd_end {
            return Err("member name extends past the central directory".into());
        }
        let entry = CentralEntry {
            name: &zip[pos + 46..name_end],
            ..entry
        };
        if unsafe_member_name(entry.name) {
            return Err(format!(
                "unsafe member name {:?}",
                String::from_utf8_lossy(entry.name)
            ));
        }
        // The member data itself must sit inside the archive.
        member_data(zip, entry.local_off, entry.comp_size)?;
        visit(&entry)?;
        pos = name_end
            .checked_add(extra_len)
            .and_then(|v| v.checked_add(cmt_len))
            .ok_or_else(|| "entry offsets overflow".to_string())?;
    }
    if pos != cd_end {
        return Err("central directory size does not match its entries".into());
    }
    Ok(())
}

/// Extracts the first member whose name ends in `suffix`
/// (ASCII case-insensitive) — the rank-list update loader's way into
/// the top-1m.csv.zip container without a zip crate. `max_out` caps
/// the inflated size; every bound the central walk enforces applies.
pub(crate) fn zip_member_by_suffix(
    zip: &[u8],
    suffix: &str,
    max_out: usize,
) -> Result<Vec<u8>, String> {
    let suffix = suffix.as_bytes();
    let mut hit: Option<(u32, u16, u32, u32)> = None;
    walk_central_dir(zip, |e| {
        if hit.is_none()
            && e.name.len() >= suffix.len()
            && e.name[e.name.len() - suffix.len()..].eq_ignore_ascii_case(suffix)
        {
            if e.uncomp_size as usize > max_out {
                return Err(format!(
                    "member {:?} expands past the size cap",
                    String::from_utf8_lossy(e.name)
                ));
            }
            hit = Some((e.local_off, e.method, e.comp_size, e.uncomp_size));
        }
        Ok(())
    })?;
    let (local_off, method, comp_size, uncomp_size) = hit.ok_or_else(|| {
        format!("no member matching *{}", String::from_utf8_lossy(suffix))
    })?;
    let data = member_data(zip, local_off, comp_size)?;
    let raw = match method {
        0 => data.to_vec(),
        8 => miniz_oxide::inflate::decompress_to_vec_with_limit(data, max_out)
            .map_err(|_| "member does not inflate".to_string())?,
        m => return Err(format!("member uses unsupported method {m}")),
    };
    if raw.len() != uncomp_size as usize {
        return Err("member size mismatch".into());
    }
    Ok(raw)
}
