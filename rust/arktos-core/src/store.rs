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
