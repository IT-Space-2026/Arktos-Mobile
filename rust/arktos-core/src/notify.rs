//! Minimal port of the desktop `crate::notify` surface: a no-op
//! event bus for now — the mobile has no listener wired yet, and
//! the sync modules only emit lifecycle events.
/// Announce a subsystem event; currently a no-op.
pub fn emit(_channel: &str) {}
