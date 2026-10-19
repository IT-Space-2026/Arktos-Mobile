//! PORT02 — app-lock / parental-control password hashing.
//!
//! The Kotlin shell used to store both passwords (`passwordText`,
//! `passwordTextLock`) as plaintext SharedPreferences strings. Stored
//! values now carry a versioned `arktos:v1:` marker wrapping an
//! Argon2id PHC string; [`verify_password`] transparently accepts a
//! legacy plaintext value and hands back a replacement hash so the
//! caller upgrades the stored row on the next successful check.

use argon2::password_hash::SaltString;
use argon2::{Argon2, PasswordHash, PasswordHasher, PasswordVerifier};
use rand_core::OsRng;

/// Stored-value marker: `arktos:v1:` + PHC-format argon2id string.
/// Anything without the prefix is a pre-PORT02 legacy plaintext
/// password.
const STORED_PREFIX: &str = "arktos:v1:";

/// Result of a [`verify_password`] call.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PasswordCheck {
    /// The candidate matched the stored password.
    pub matched: bool,
    /// When the stored value was legacy plaintext and the candidate
    /// matched, the Argon2id replacement the caller should persist —
    /// the versioned migration.
    pub upgraded_hash: Option<String>,
}

/// Hash a password for storage — `arktos:v1:` + PHC argon2id string
/// (default m=19456, t=2, p=1 parameters, fresh random salt).
#[uniffi::export]
pub fn hash_password(password: String) -> String {
    let salt = SaltString::generate(&mut OsRng);
    let hash = Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|h| h.to_string())
        .unwrap_or_default();
    format!("{STORED_PREFIX}{hash}")
}

/// Check [candidate] against a stored value. Argon2id values verify
/// in constant time; a value without the `arktos:v1:` prefix is the
/// legacy plaintext pref — an exact match yields `matched` plus the
/// upgraded hash to persist.
#[uniffi::export]
pub fn verify_password(stored: String, candidate: String) -> PasswordCheck {
    match stored.strip_prefix(STORED_PREFIX) {
        Some(phc) => {
            let matched = PasswordHash::new(phc)
                .ok()
                .is_some_and(|parsed| {
                    Argon2::default()
                        .verify_password(candidate.as_bytes(), &parsed)
                        .is_ok()
                });
            PasswordCheck {
                matched,
                upgraded_hash: None,
            }
        }
        None => PasswordCheck {
            matched: stored == candidate,
            upgraded_hash: (stored == candidate).then(|| hash_password(candidate)),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let stored = hash_password("s3cret".into());
        assert!(stored.starts_with(STORED_PREFIX));
        let check = verify_password(stored, "s3cret".into());
        assert!(check.matched);
        assert!(check.upgraded_hash.is_none());
    }

    #[test]
    fn wrong_candidate_rejected() {
        let stored = hash_password("s3cret".into());
        assert!(!verify_password(stored, "nope".into()).matched);
    }

    #[test]
    fn malformed_hash_rejected() {
        let check = verify_password("arktos:v1:not-a-phc".into(), "x".into());
        assert!(!check.matched);
        assert!(check.upgraded_hash.is_none());
    }

    #[test]
    fn legacy_plaintext_migrates() {
        let check = verify_password("hunter2".into(), "hunter2".into());
        assert!(check.matched);
        let upgraded = check.upgraded_hash.expect("legacy match must upgrade");
        assert!(upgraded.starts_with(STORED_PREFIX));
        // The upgraded value verifies and stops migrating.
        let recheck = verify_password(upgraded, "hunter2".into());
        assert!(recheck.matched);
        assert!(recheck.upgraded_hash.is_none());
        // And a wrong candidate against the plaintext store fails.
        assert!(!verify_password("hunter2".into(), "wrong".into()).matched);
    }

    #[test]
    fn salts_differ() {
        assert_ne!(hash_password("pw".into()), hash_password("pw".into()));
    }
}
