//! MSYNC02 — the UniFFI surface over the ported sync core.
//! Functions take/return plain types + JSON strings so Kotlin can
//! drive the engine without sharing Rust value types.

use crate::error::{Fail, RcResult};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::sync::OnceLock;

use crate::sync::SyncEngine;

/// Open-or-get the per-root engine. Failures surface as a typed
/// error JSON to the caller — a sync-engine fault must never abort
/// the browser process (a panic here is an instant native crash).
fn try_engine(data_root: &str) -> RcResult<&'static Mutex<SyncEngine>> {
    static ENGINES: OnceLock<Mutex<std::collections::HashMap<String, std::sync::Arc<Mutex<SyncEngine>>>>> = OnceLock::new();
    // Single-engine-per-root is enough for the mobile flow — one
    // SyncEngine opened on the app's filesDir drives all sync state.
    // Leak a per-root engine for a stable reference.
    let m = ENGINES.get_or_init(|| Mutex::new(std::collections::HashMap::new()));
    let mut map = m.lock().unwrap_or_else(|e| e.into_inner());
    // The map holds the Arc for process lifetime — as_ptr borrows it
    // without consuming, so every caller gets a stable &Mutex.
    if let Some(e) = map.get(data_root) {
        let p = std::sync::Arc::as_ptr(e);
        drop(map);
        return Ok(unsafe { &*p });
    }
    let eng = SyncEngine::open(data_root)?;
    let arc = std::sync::Arc::new(Mutex::new(eng));
    let p = std::sync::Arc::as_ptr(&arc);
    map.insert(data_root.to_string(), arc);
    drop(map);
    Ok(unsafe { &*p })
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
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return fail_json(&e),
    };
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

// MSYNC05 — the store-sync surface: append/export/import/drain the
// op-log, plus a callback trait Kotlin implements per store so ops
// apply into ArktosStore/preferences rather than Rust reaching in.

/// Unapplied ops accepted for a store — a Kotlin-side adapter
/// drains these, writes them into its store, then marks each
/// applied. Pull-based so the mobile owns scheduling/batching.
#[uniffi::export]
pub fn sync_pending_apply(data_root: String, store_name: String) -> String {
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return fail_json(&e),
    };
    match eng.pending_apply(&store_name) {
        Ok(ops) => serde_json::Value::Array(ops.iter().map(|o| o.to_json()).collect()).to_string(),
        Err(e) => fail_json(&e),
    }
}

/// Mark an op applied — a Kotlin adapter calls this once the op
/// landed in its store, clearing it from pending_apply.
#[uniffi::export]
pub fn sync_mark_applied(data_root: String, op_id: String) -> String {
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return fail_json(&e),
    };
    match eng.mark_applied(&op_id) {
        Ok(()) => r#"{"ok":true}"#.to_string(),
        Err(e) => fail_json(&e),
    }
}

/// Append a local op to a synced store — the JSON body is the
/// caller's own change record.
#[uniffi::export]
pub fn sync_append(data_root: String, store_name: String, scope: String, body_json: String) -> String {
    let body: serde_json::Value = match serde_json::from_str(&body_json) {
        Ok(v) => v,
        Err(e) => return format!(r#"{{"error":"bad body: {e}"}}"#),
    };
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return fail_json(&e),
    };
    match eng.append(&store_name, &scope, &body) {
        Ok(op) => op.to_json().to_string(),
        Err(e) => fail_json(&e),
    }
}

/// Export ops newer than `since_seq` (optionally one store) as a
/// JSON array — the transport shipper tails this.
#[uniffi::export]
pub fn sync_export(data_root: String, store_name: Option<String>, since_seq: i64) -> String {
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return fail_json(&e),
    };
    match eng.export(store_name.as_deref(), since_seq) {
        Ok(ops) => serde_json::Value::Array(ops.iter().map(|o| o.to_json()).collect()).to_string(),
        Err(e) => fail_json(&e),
    }
}

/// Import peer ops — dedups via the op-log and applies them through
/// the registered store appliers.
#[uniffi::export]
pub fn sync_import(data_root: String, ops_json: String) -> String {
    let docs: Vec<serde_json::Value> = match serde_json::from_str(&ops_json) {
        Ok(v) => v,
        Err(e) => return format!(r#"{{"error":"bad ops: {e}"}}"#),
    };
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return fail_json(&e),
    };
    match eng.import(&docs) {
        Ok(r) => format!(r#"{{"accepted":{},"applied":{},"deferred":{},"duplicates":{},"rejected":{},"apply_errors":{}}}"#,
                r.accepted, r.applied, r.deferred, r.duplicates, r.rejected.len(), r.apply_errors.len()),
        Err(e) => fail_json(&e),
    }
}

/// Drain ops accepted for a store before its applier registered.
#[uniffi::export]
pub fn sync_drain_pending(data_root: String, store_name: String) -> String {
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return fail_json(&e),
    };
    match eng.drain_pending(&store_name) {
        Ok(r) => format!(r#"{{"accepted":{},"applied":{},"deferred":{},"duplicates":{},"rejected":{},"apply_errors":{}}}"#,
                r.accepted, r.applied, r.deferred, r.duplicates, r.rejected.len(), r.apply_errors.len()),
        Err(e) => fail_json(&e),
    }
}

/// Sync engine stats (op counts, members, lamport clock).
#[uniffi::export]
pub fn sync_stats(data_root: String) -> String {
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return fail_json(&e),
    };
    match eng.stats() {
        Ok(v) => v.to_string(),
        Err(e) => fail_json(&e),
    }
}

/// Add a paired member (device_id + onion + scope) to the engine.
#[uniffi::export]
pub fn sync_member_add(data_root: String, pubkey_b64: String, onion: String, scope: String) -> String {
    let pk = crate::mw::b64_decode(&pubkey_b64).and_then(|b| <[u8;32]>::try_from(b).ok());
    match pk {
        Some(pk) => {
            let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return fail_json(&e),
    };
            match eng.member_add(&pk, &onion, &scope) {
                Ok(id) => format!(r#"{{"device_id":"{id}"}}"#),
                Err(e) => fail_json(&e),
            }
        }
        None => r#"{"error":"bad pubkey"}"#.to_string(),
    }
}

/// Remove a paired member — stops ops from/to that device.
#[uniffi::export]
pub fn sync_member_remove(data_root: String, device_id: String) -> bool {
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(_) => return false,
    };
    eng.member_remove(&device_id).unwrap_or(false)
}

/// One-shot sync bring-up: installs the shared ArktosStore into the
/// store shims and registers every adapter's apply lane against
/// `data_root`'s engine.  Kotlin calls this once at app start on
/// the gecko flavor.
#[uniffi::export]
pub fn sync_install(data_root: String, store: Arc<crate::storage::ArktosStore>) -> String {
    crate::history::install_shared_store(store);
    crate::syncbookmarks::install(&data_root);
    crate::synchistory::install(&data_root);
    crate::syncsettings::install(&data_root);
    crate::synctabs::install(&data_root);
    r#"{"ok":true}"#.to_string()
}

/// Local bookmark write — routes through the real adapter's emit
/// path (bookmarks::with -> outbox -> flush_local -> signed ops),
/// matching the desktop's wire format exactly.
#[uniffi::export]
pub fn sync_bookmark_local(data_root: String, op: String, url: String, title: String, folder: String) -> String {
    let xbel = crate::facade::profile_dir(std::path::Path::new(&data_root), "default")
        .join("bookmarks.xbel");
    crate::bookmarks::with(|b| -> String {
        if let Err(e) = b.ensure_path(&xbel) { return json_err(&e); }
        let r = match op.as_str() {
            "add" => {
                let parent = if folder.is_empty() { b.root() } else {
                    b.child_folder_named(b.root(), &folder)
                        .or_else(|| {
                            b.create(b.root(), -1, &serde_json::json!({
                                "type": crate::bookmarks::TYPE_FOLDER,
                                "title": folder, "id": format!("folder:{folder}"),
                            })).ok()
                        })
                        .unwrap_or_else(|| b.root())
                };
                b.create(parent, -1, &serde_json::json!({
                    "type": crate::bookmarks::TYPE_BOOKMARK,
                    "url": url, "title": title,
                })).map(|_| ())
            }
            "remove" => match b.find_url(&url) {
                Some(h) => b.destroy(h),
                None => Ok(()),
            },
            _ => Ok(()),
        };
        match r {
            Ok(()) => { let _ = b.save_path(&xbel); r#"{"ok":true}"#.to_string() }
            Err(e) => json_err(&e),
        }
    })
}

/// Local history write — same route via history::with.
#[uniffi::export]
pub fn sync_history_local(data_root: String, op: String, url: String, title: String) -> String {
    let db = crate::facade::profile_dir(std::path::Path::new(&data_root), "default")
        .join("history.db");
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as i64;
    crate::history::with(|h| -> String {
        if let Err(e) = h.open(db.to_str()) { return json_err(&e); }
        let r = match op.as_str() {
            "visit" => h.add(&url, &title, ts),
            "remove" => h.remove(&url, &title, ts),
            _ => Ok(()),
        };
        match r {
            Ok(()) => r#"{"ok":true}"#.to_string(),
            Err(e) => json_err(&e),
        }
    })
}

fn json_err(e: &crate::error::Fail) -> String {
    serde_json::json!({"ok": false, "error": e.msg}).to_string()
}

/// Local open-tabs presence publish — the ephemeral per-device doc
/// the desktop's read-only peer view consumes.
#[uniffi::export]
pub fn sync_tabs_local(data_root: String, tabs_json: String) -> String {
    let windows: serde_json::Value = match serde_json::from_str(&tabs_json) {
        Ok(v) => v,
        Err(e) => return serde_json::json!({"ok": false, "error": format!("{e}")}).to_string(),
    };
    match crate::synctabs::publish(&data_root, "default", &windows) {
        Ok(_) => r#"{"ok":true}"#.to_string(),
        Err(e) => json_err(&e),
    }
}
