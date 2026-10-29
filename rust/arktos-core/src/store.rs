//! Minimal port of the desktop `crate::store` surface the sync
//! modules call: atomic writes + permission tightening + a test
//! hook. Lives inside arktos-core so sync doesn't drag the
//! desktop's full store facade in.
use crate::error::{fail, Fail, RcResult, RcStatus};
use std::fs;
use std::path::Path;

/// Write `bytes` atomically — temp file in the same dir, then rename.
pub fn atomic_write(path: &Path, bytes: &[u8]) -> RcResult<()> {
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, bytes).map_err(|e| Fail { status: RcStatus::Io, msg: format!("write tmp {e}") })?;
    fs::rename(&tmp, path).map_err(|e| Fail { status: RcStatus::Io, msg: format!("rename {e}") })?;
    tighten_perms(path);
    Ok(())
}

/// Tighten a file's permissions to owner-only (0600) on unix; no-op elsewhere.
pub fn tighten_perms(path: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = fs::metadata(path) {
            let _ = fs::set_permissions(path, {
                let mut p = meta.permissions();
                p.set_mode(0o600);
                p
            });
        }
    }
}

/// Sync tests serialize on a global lock — the desktop's real one
/// guards the data-dir swap; mobile's shim just serializes.
#[cfg(test)]
pub fn test_lock() -> std::sync::MutexGuard<'static, ()> {
    static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    LOCK.lock().unwrap_or_else(|e| e.into_inner())
}

// ── SYNC08 vault crypto (ported byte-compatible from desktop) ────
use aes_gcm::aead::{consts::U12, generic_array::GenericArray, Aead, KeyInit};
use aes_gcm::Aes256Gcm;
use argon2::{Algorithm, Argon2, Params, Version};
use zeroize::Zeroizing;

pub const KEY_SIZE: usize = 32;
pub const KDF_SALT_SIZE: usize = 16;
pub const KDF_MEMORY_KIB: u32 = 65536;
pub const KDF_PASSES: u32 = 3;
pub const KDF_LANES: u32 = 4;
pub type Key = Zeroizing<[u8; KEY_SIZE]>;

const NONCE_SIZE: usize = 12;
const TAG_SIZE: usize = 16;
const SEAL_MAGIC: &[u8; 6] = b"ARSEC1";
const HEADER_SIZE: usize = 6 + NONCE_SIZE + TAG_SIZE;

type Nonce96 = GenericArray<u8, U12>;

pub(crate) fn derive_key(pass: &[u8], m: u32, t: u32, p: u32, salt: &[u8]) -> RcResult<Key> {
    let params = Params::new(m, t, p, Some(KEY_SIZE)).map_err(|e| Fail {
        status: RcStatus::InvalidArgument,
        msg: format!("argon2 params: {e}"),
    })?;
    let mut out = [0u8; KEY_SIZE];
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(pass, salt, &mut out)
        .map_err(|e| Fail {
            status: RcStatus::Crypto,
            msg: format!("argon2: {e}"),
        })?;
    Ok(Zeroizing::new(out))
}

/// "ARSEC1" | nonce[12] | tag[16] | ciphertext under `key`.
pub fn seal_with_key(key: &[u8; KEY_SIZE], plain: &[u8]) -> RcResult<Vec<u8>> {
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|e| Fail {
        status: RcStatus::Crypto,
        msg: format!("aes-gcm init: {e}"),
    })?;
    let mut nonce = [0u8; NONCE_SIZE];
    getrandom::getrandom(&mut nonce).map_err(|e| Fail {
        status: RcStatus::Unavailable,
        msg: format!("getrandom: {e}"),
    })?;
    let sealed = cipher
        .encrypt(Nonce96::from_slice(&nonce), plain)
        .map_err(|_| Fail {
            status: RcStatus::Crypto,
            msg: "aes-gcm seal failed".into(),
        })?;
    let (ct, tag) = sealed.split_at(sealed.len() - TAG_SIZE);
    let mut out = Vec::with_capacity(HEADER_SIZE + ct.len());
    out.extend_from_slice(SEAL_MAGIC);
    out.extend_from_slice(&nonce);
    out.extend_from_slice(tag);
    out.extend_from_slice(ct);
    Ok(out)
}

/// Inverse of seal_with_key; `Crypto` on tag mismatch, `Corrupt` on shape.
pub fn open_with_key(key: &[u8; KEY_SIZE], blob: &[u8]) -> RcResult<Vec<u8>> {
    if blob.len() < HEADER_SIZE || &blob[..6] != SEAL_MAGIC {
        return fail(RcStatus::Corrupt, "not a sealed blob");
    }
    let nonce = Nonce96::from_slice(&blob[6..6 + NONCE_SIZE]);
    let tag = &blob[6 + NONCE_SIZE..HEADER_SIZE];
    let ct = &blob[HEADER_SIZE..];
    let mut ct_tag = Vec::with_capacity(ct.len() + TAG_SIZE);
    ct_tag.extend_from_slice(ct);
    ct_tag.extend_from_slice(tag);
    Aes256Gcm::new_from_slice(key)
        .map_err(|e| Fail {
            status: RcStatus::Crypto,
            msg: format!("aes-gcm init: {e}"),
        })?
        .decrypt(nonce, ct_tag.as_ref())
        .map_err(|_| Fail {
            status: RcStatus::Crypto,
            msg: "sealed blob failed authentication".into(),
        })
}
