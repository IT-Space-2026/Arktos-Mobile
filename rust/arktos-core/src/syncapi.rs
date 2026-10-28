//! MSYNC02 — the UniFFI surface over the ported sync core.
//! Functions take/return plain types + JSON strings so Kotlin can
//! drive the engine without sharing Rust value types.

use crate::error::Fail;
use std::path::Path;
use std::sync::Mutex;
use std::sync::OnceLock;

use crate::sync::SyncEngine;

fn engine(data_root: &str) -> &'static Mutex<SyncEngine> {
    static ENGINES: OnceLock<Mutex<std::collections::HashMap<String, std::sync::Arc<Mutex<SyncEngine>>>>> = OnceLock::new();
    // Single-engine-per-root is enough for the mobile flow — one
    // SyncEngine opened on the app's filesDir drives all sync state.
    // Leak a per-root engine for a stable reference.
    let m = ENGINES.get_or_init(|| Mutex::new(std::collections::HashMap::new()));
    let mut map = m.lock().unwrap();
    let arc = map.entry(data_root.to_string())
        .or_insert_with(|| {
            std::sync::Arc::new(Mutex::new(
                SyncEngine::open(data_root).expect("SyncEngine::open")
            ))
        })
        .clone();
    drop(map);
    // SAFETY: leak the Arc to give a stable &Mutex — engines live for
    // process lifetime anyway.
    unsafe { &*(std::sync::Arc::into_raw(arc) as *const Mutex<SyncEngine>) }
}

fn fail_json(e: &Fail) -> String {
    format!(r#"{{"error":"{}","status":{:?}}}"#, e.msg.replace('"', "'"), e.status)
}

/// Create an invite payload (the QR/manual-code text the peer scans).
#[uniffi::export]
pub fn sync_invite_begin(data_root: String, scope: String) -> String {
    match crate::syncpair::invite_begin(Path::new(&data_root), &scope) {
        Ok(v) => v.to_string(),
        Err(e) => fail_json(&e),
    }
}

/// Begin joining from an invite payload; returns the session id.
#[uniffi::export]
pub fn sync_join_begin(data_root: String, payload_text: String) -> String {
    match crate::syncpair::join_begin(Path::new(&data_root), &payload_text) {
        Ok(id) => format!(r#"{{"session":{id}}}"#),
        Err(e) => fail_json(&e),
    }
}

/// The fingerprint JSON for a 32-byte identity hash (hex or b64).
#[uniffi::export]
pub fn sync_fingerprint(hash_b64: String) -> String {
    match crate::mw::b64_decode(&hash_b64).and_then(|b| <[u8;32]>::try_from(b).ok()) {
        Some(h) => crate::syncpair::fingerprint_json(&h).to_string(),
        None => r#"{"error":"bad hash"}"#.to_string(),
    }
}

/// The paired-member list for the Synchronization screen.
#[uniffi::export]
pub fn sync_members(data_root: String) -> String {
    let eng = engine(&data_root).lock().unwrap();
    match eng.members_list() {
        Ok(members) => {
            let arr: Vec<serde_json::Value> = members.iter().map(|m| serde_json::json!({
                "id": m.device_id,
                "pubkey": crate::mw::b64_encode(&m.pubkey),
                "onion": m.onion_addr,
            })).collect();
            serde_json::Value::Array(arr).to_string()
        }
        Err(e) => fail_json(&e),
    }
}

/// Start the sync transport on a backend ("mem" works without Arti).
#[uniffi::export]
pub fn sync_transport_start(data_root: String, backend: String, name: String) -> bool {
    crate::synctransport::start(Path::new(&data_root), &backend, &name).is_ok()
}

/// Drive a pair session's handshake; returns the session's status JSON.
#[uniffi::export]
pub fn sync_pair_poll(data_root: String, pair_id: u64) -> String {
    match crate::syncpair::poll(Path::new(&data_root), pair_id) {
        Ok(v) => v.to_string(),
        Err(e) => fail_json(&e),
    }
}

/// Confirm or reject a fingerprint check for a pair session.
#[uniffi::export]
pub fn sync_pair_confirm(data_root: String, pair_id: u64, ok: bool) -> String {
    match crate::syncpair::confirm(Path::new(&data_root), pair_id, ok) {
        Ok(()) => r#"{"ok":true}"#.to_string(),
        Err(e) => fail_json(&e),
    }
}

/// Cancel a pair session.
#[uniffi::export]
pub fn sync_pair_cancel(data_root: String, pair_id: u64) -> String {
    match crate::syncpair::cancel(Path::new(&data_root), pair_id) {
        Ok(()) => r#"{"ok":true}"#.to_string(),
        Err(e) => fail_json(&e),
    }
}

/// List live pair sessions as a JSON array.
#[uniffi::export]
pub fn sync_pair_list(data_root: String) -> String {
    serde_json::Value::Array(crate::syncpair::list(Path::new(&data_root))).to_string()
}

/// Whether a transport is already running for this root.
#[uniffi::export]
pub fn sync_transport_running(data_root: String) -> bool {
    crate::synctransport::running(Path::new(&data_root)).is_some()
}
