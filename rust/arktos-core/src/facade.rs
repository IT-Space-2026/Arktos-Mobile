//! Profile indirection — the desktop splits stores by profile
//! scope; the mobile is single-profile, so every scope maps onto
//! the one data root.

use std::path::{Path, PathBuf};

/// The profile an op's scope lands in — "default" when unset.
pub fn effective_profile(scope: &str) -> &str {
    if scope.is_empty() {
        "default"
    } else {
        scope
    }
}

/// The directory a scope's stores live in — matches the desktop's
/// profiles/<scope>/ layout so store bindings map scope->root the
/// same way.
pub fn profile_dir(data_root: &Path, scope: &str) -> PathBuf {
    data_root.join("profiles").join(scope)
}
