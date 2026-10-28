//! Minimal port of the desktop `crate::store` surface the sync
//! modules call: atomic writes + permission tightening + a test
//! hook. Lives inside arktos-core so sync doesn't drag the
//! desktop's full store facade in.
use crate::error::{Fail, RcResult, RcStatus};
use std::fs;
use std::path::Path;

/// Write `bytes` atomically — temp file in the same dir, then rename.
pub fn atomic_write(path: &Path, bytes: &[u8]) -> RcResult<()> {
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, bytes).map_err(|e| Fail { status: RcStatus::Io, msg: format!("write tmp {e}") })?;
    fs::rename(&tmp, path).map_err(|e| Fail { status: RcStatus::Io, msg: format!("rename {e}") })
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

/// Sync tests gate on a writable data dir — stubbed true on mobile,
/// the harness never runs under a locked vault.
pub fn test_lock() -> bool { true }
