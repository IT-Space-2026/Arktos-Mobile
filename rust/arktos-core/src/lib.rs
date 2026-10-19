//! Arktos Mobile Rust core (`libarktos.so`).
//!
//! Foundation crate for the incremental Rust port — the blocklist
//! engine, storage core, and suggestions/userscript/download logic
//! all land here behind UniFFI-generated Kotlin bindings.
//! `core_version`/`core_ping` exist to prove the JNI seam end-to-end
//! from Gradle build to Kotlin call.

mod blocklist;
mod bookmarks;
mod download;
mod misc;
mod netscape;
mod pages;
mod password;
mod ranklist;
mod readability;
mod storage;
mod subscriptions;
mod suggestions;
mod translate;
mod urlstrip;
mod urlutils;
mod userscript;
mod util;
mod zipwalk;

uniffi::setup_scaffolding!();

/// Semantic version of the Rust core, matching `Cargo.toml`.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

/// Cheap liveness check used by the Kotlin shell to verify the
/// native library loaded correctly. Always returns `true`.
#[uniffi::export]
pub fn core_ping() -> bool {
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_matches_manifest() {
        assert_eq!(core_version(), env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn ping_is_alive() {
        assert!(core_ping());
    }
}
