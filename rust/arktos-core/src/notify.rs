//! Minimal port of the desktop `crate::notify` surface: a no-op
//! event bus for now — the mobile has no listener wired yet, and
//! the sync modules only emit lifecycle events.

/// Announce a subsystem event; currently a no-op.
pub fn emit(_channel: &str) {}

/// Desktop profile-scoped emitter — mobile is single-profile, so
/// the event folds into the global no-op bus.
pub fn emit_event(_topic: &str, _extra: serde_json::Value) {}

/// Scope guard marking the active profile for emit_event calls —
/// a no-op token on mobile.
pub struct ProfileHint;

/// Mark the active profile for the call scope — returns a guard
/// the desktop drops to restore the previous hint.
pub fn profile_hint(_profile_id: &str) -> ProfileHint {
    ProfileHint
}
