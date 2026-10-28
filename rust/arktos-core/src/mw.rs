//! Minimal `crate::mw` shim — the desktop middleware helpers the
//! sync modules call. Base64 encode/decode only for now.
use base64::{engine::general_purpose::STANDARD, Engine};

pub fn b64_encode(bytes: &[u8]) -> String { STANDARD.encode(bytes) }
pub fn b64_decode(s: &str) -> Option<Vec<u8>> { STANDARD.decode(s.trim()).ok() }
