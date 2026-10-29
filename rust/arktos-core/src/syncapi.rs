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

fn json_err(e: &Fail) -> String {
    serde_json::json!({"ok": false, "error": e.msg}).to_string()
}

/// Create an invite payload (the QR/manual-code text the peer scans).
#[uniffi::export]
pub fn sync_invite_begin(data_root: String, scope: String) -> String {
    match crate::syncpair::invite_begin(Path::new(&data_root), &scope) {
        Ok(v) => v.to_string(),
        Err(e) => json_err(&e),
    }
}

/// Begin joining from an invite payload; returns the session id.
#[uniffi::export]
pub fn sync_join_begin(data_root: String, payload_text: String) -> String {
    match crate::syncpair::join_begin(Path::new(&data_root), &payload_text) {
        Ok(id) => format!(r#"{{"session":{id}}}"#),
        Err(e) => json_err(&e),
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
        Err(e) => return json_err(&e),
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
        Err(e) => json_err(&e),
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
        Err(e) => json_err(&e),
    }
}

/// Confirm or reject a fingerprint check for a pair session.
#[uniffi::export]
pub fn sync_pair_confirm(data_root: String, pair_id: u64, ok: bool) -> String {
    match crate::syncpair::confirm(Path::new(&data_root), pair_id, ok) {
        Ok(()) => r#"{"ok":true}"#.to_string(),
        Err(e) => json_err(&e),
    }
}

/// Cancel a pair session.
#[uniffi::export]
pub fn sync_pair_cancel(data_root: String, pair_id: u64) -> String {
    match crate::syncpair::cancel(Path::new(&data_root), pair_id) {
        Ok(()) => r#"{"ok":true}"#.to_string(),
        Err(e) => json_err(&e),
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
        Err(e) => return json_err(&e),
    };
    match eng.pending_apply(&store_name) {
        Ok(ops) => serde_json::Value::Array(ops.iter().map(|o| o.to_json()).collect()).to_string(),
        Err(e) => json_err(&e),
    }
}

/// Mark an op applied — a Kotlin adapter calls this once the op
/// landed in its store, clearing it from pending_apply.
#[uniffi::export]
pub fn sync_mark_applied(data_root: String, op_id: String) -> String {
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return json_err(&e),
    };
    match eng.mark_applied(&op_id) {
        Ok(()) => r#"{"ok":true}"#.to_string(),
        Err(e) => json_err(&e),
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
        Err(e) => return json_err(&e),
    };
    match eng.append(&store_name, &scope, &body) {
        Ok(op) => op.to_json().to_string(),
        Err(e) => json_err(&e),
    }
}

/// Export ops newer than `since_seq` (optionally one store) as a
/// JSON array — the transport shipper tails this.
#[uniffi::export]
pub fn sync_export(data_root: String, store_name: Option<String>, since_seq: i64) -> String {
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return json_err(&e),
    };
    match eng.export(store_name.as_deref(), since_seq) {
        Ok(ops) => serde_json::Value::Array(ops.iter().map(|o| o.to_json()).collect()).to_string(),
        Err(e) => json_err(&e),
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
        Err(e) => return json_err(&e),
    };
    match eng.import(&docs) {
        Ok(r) => format!(r#"{{"accepted":{},"applied":{},"deferred":{},"duplicates":{},"rejected":{},"apply_errors":{}}}"#,
                r.accepted, r.applied, r.deferred, r.duplicates, r.rejected.len(), r.apply_errors.len()),
        Err(e) => json_err(&e),
    }
}

/// Drain ops accepted for a store before its applier registered.
#[uniffi::export]
pub fn sync_drain_pending(data_root: String, store_name: String) -> String {
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return json_err(&e),
    };
    match eng.drain_pending(&store_name) {
        Ok(r) => format!(r#"{{"accepted":{},"applied":{},"deferred":{},"duplicates":{},"rejected":{},"apply_errors":{}}}"#,
                r.accepted, r.applied, r.deferred, r.duplicates, r.rejected.len(), r.apply_errors.len()),
        Err(e) => json_err(&e),
    }
}

/// Sync engine stats (op counts, members, lamport clock).
#[uniffi::export]
pub fn sync_stats(data_root: String) -> String {
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return json_err(&e),
    };
    match eng.stats() {
        Ok(v) => v.to_string(),
        Err(e) => json_err(&e),
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
        Err(e) => return json_err(&e),
    };
            match eng.member_add(&pk, &onion, &scope) {
                Ok(id) => format!(r#"{{"device_id":"{id}"}}"#),
                Err(e) => json_err(&e),
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

/// Paired-device list for a scope — the syncmeta adapter's view.
#[uniffi::export]
pub fn sync_devices(data_root: String, scope: String) -> String {
    match crate::syncmeta::devices(&data_root, &scope) {
        Ok(v) => v.to_string(),
        Err(e) => json_err(&e),
    }
}

/// Revoke a paired device — drops membership, rotates the group
/// key so its buffered ops stop decrypting.
#[uniffi::export]
pub fn sync_device_revoke(data_root: String, scope: String, device_id: String) -> String {
    match crate::syncmeta::revoke(&data_root, &scope, &device_id) {
        Ok(v) => v.to_string(),
        Err(e) => json_err(&e),
    }
}

/// Opt the scope into encrypted password sync — the group's shared
/// secret gets re-wrapped under the passphrase.
#[uniffi::export]
pub fn sync_passwords_enable(data_root: String, scope: String, passphrase: String) -> String {
    match crate::syncpasswords::enable(&data_root, &scope, passphrase.as_bytes()) {
        Ok(v) => v.to_string(),
        Err(e) => json_err(&e),
    }
}

/// Lock the scope's vault — ciphertext stays in the log, nothing
/// decrypts locally until the next enable/unlock.
#[uniffi::export]
pub fn sync_passwords_lock(data_root: String, scope: String) -> String {
    match crate::syncpasswords::lock(&data_root, &scope) {
        Ok(v) => v.to_string(),
        Err(e) => json_err(&e),
    }
}

/// Vault state for the scope — enabled/locked, entry counts.
#[uniffi::export]
pub fn sync_passwords_status(data_root: String, scope: String) -> String {
    match crate::syncpasswords::status(&data_root, &scope) {
        Ok(v) => v.to_string(),
        Err(e) => json_err(&e),
    }
}

/// Shared write path for the login lanes: open the scope's
/// autofill vault, apply the caller's edit to its records, and
/// persist through `autofill_set` — the funnel that lets the
/// passwords adapter diff + seal + sign whatever moved.  Locked
/// or never-enabled scopes still persist locally; emit_after
/// returns at once and nothing leaks.
fn password_write(
    data_root: &str,
    scope: &str,
    edit: impl FnOnce(&mut Vec<crate::autofill::FormRecord>),
) -> String {
    let dir = Path::new(data_root).join("profiles").join(scope);
    let mut st = crate::store::lock();
    if let Err(e) = st.set_data_dir(&dir.to_string_lossy()) {
        return json_err(&e);
    }
    let mut forms = match st.autofill_data() {
        Ok(d) => d.forms,
        Err(e) => return json_err(&e),
    };
    edit(&mut forms);
    match st.autofill_set(forms) {
        Ok(()) => r#"{"ok":true}"#.to_string(),
        Err(e) => json_err(&e),
    }
}

/// Local login save routed through the real emit path — upsert by
/// url into the scope's autofill vault; the adapter diffs the
/// changed fields into sealed ops.  The element names match the
/// desktop record shape the applier materializes.
#[uniffi::export]
pub fn sync_password_local(
    data_root: String,
    scope: String,
    url: String,
    username: String,
    password: String,
) -> String {
    password_write(&data_root, &scope, |forms| {
        forms.retain(|f| !(f.has_password && f.url == url));
        forms.push(crate::autofill::FormRecord {
            url: url.clone(),
            name: username.clone(),
            has_password: true,
            elements: vec![
                ("username".to_string(), username.clone()),
                ("password".to_string(), password.clone()),
            ],
        });
    })
}

/// Local login delete — dropping the url's record makes the next
/// diff sign the tombstone the group converges on.
#[uniffi::export]
pub fn sync_password_remove(data_root: String, scope: String, url: String) -> String {
    password_write(&data_root, &scope, |forms| {
        forms.retain(|f| !(f.has_password && f.url == url));
    })
}

/// Local login wipe — every password record drops, tombstoning the
/// synced set in one pass (the stored-data clear's sync half).
#[uniffi::export]
pub fn sync_password_clear(data_root: String, scope: String) -> String {
    password_write(&data_root, &scope, |forms| {
        forms.retain(|f| !f.has_password);
    })
}

/// The decrypted group image — materialized password records the
/// applier landed in the scope's vault, so the app can merge them
/// into its own login store.
#[uniffi::export]
pub fn sync_passwords_list(data_root: String, scope: String) -> String {
    let dir = Path::new(&data_root).join("profiles").join(&scope);
    let mut st = crate::store::lock();
    if let Err(e) = st.set_data_dir(&dir.to_string_lossy()) {
        return json_err(&e);
    }
    match st.autofill_data() {
        Ok(d) => {
            let arr: Vec<serde_json::Value> = d
                .forms
                .iter()
                .filter(|f| f.has_password)
                .map(|f| {
                    serde_json::json!({
                        "url": f.url,
                        "name": f.name,
                        "elements": f.elements.iter()
                            .map(|(k, v)| (k.clone(), serde_json::json!(v)))
                            .collect::<serde_json::Map<String, serde_json::Value>>(),
                    })
                })
                .collect();
            serde_json::json!({"ok": true, "logins": arr}).to_string()
        }
        Err(e) => json_err(&e),
    }
}

/// Local settings write — persists through `RcSettings::set`,
/// whose emit hook signs the change onto the op-log.  `value_json`
/// is the typed wire form ({"bool":..} / {"int":..} / {"str":..} /
/// {"list":[..]}) so peer apps share one value encoding.
#[uniffi::export]
pub fn sync_setting_local(
    data_root: String,
    scope: String,
    key: String,
    value_json: String,
) -> String {
    let v: serde_json::Value = match serde_json::from_str(&value_json) {
        Ok(v) => v,
        Err(_) => {
            return r#"{"error":"malformed value_json"}"#.to_string();
        }
    };
    let Some(sv) = crate::settings::value_from_json(&v) else {
        return r#"{"error":"unsupported settings value"}"#.to_string();
    };
    let h = match crate::settings::RcSettings::open(&data_root, Some(&scope)) {
        Ok(h) => h,
        Err(e) => return json_err(&e),
    };
    match h.set(&key, sv) {
        Ok(()) => r#"{"ok":true}"#.to_string(),
        Err(e) => json_err(&e),
    }
}

/// Local settings delete — `RcSettings::remove` emits the `del`
/// op the group converges on.
#[uniffi::export]
pub fn sync_setting_remove(data_root: String, scope: String, key: String) -> String {
    let h = match crate::settings::RcSettings::open(&data_root, Some(&scope)) {
        Ok(h) => h,
        Err(e) => return json_err(&e),
    };
    match h.remove(&key) {
        Ok(()) => r#"{"ok":true}"#.to_string(),
        Err(e) => json_err(&e),
    }
}

/// The merged settings image — every key the adapters wrote or
/// applied, rendered in the typed wire form for the app-store
/// merge.
#[uniffi::export]
pub fn sync_settings_list(data_root: String, scope: String) -> String {
    let h = match crate::settings::RcSettings::open(&data_root, Some(&scope)) {
        Ok(h) => h,
        Err(e) => return json_err(&e),
    };
    let map: serde_json::Map<String, serde_json::Value> = h
        .entries()
        .into_iter()
        .map(|(k, v)| (k, crate::settings::value_to_json(&v)))
        .collect();
    serde_json::json!({"ok": true, "settings": map}).to_string()
}

/// The peer devices' tab presence docs — the read-only "tabs on my
/// other devices" feed.
#[uniffi::export]
pub fn sync_tab_peers(data_root: String, scope: String) -> String {
    match crate::synctabs::peers(&data_root, &scope) {
        Ok(v) => serde_json::json!({"ok": true, "peers": v}).to_string(),
        Err(e) => json_err(&e),
    }
}

/// Inbound dispatch: drain the running transport's event queue,
/// feed Frame payloads — `{"ops":[...]}` batches — into the
/// engine's import lane so the registered appliers run them, and
/// keep the live-link book current for the outbound pump.
#[uniffi::export]
pub fn sync_transport_drain(data_root: String) -> String {
    let root = Path::new(&data_root);
    let Some(t) = crate::synctransport::running(root) else {
        return r#"{"ok":true,"drained":0,"note":"no transport running"}"#.to_string();
    };
    let mut frames = 0usize;
    let mut imported = 0u32;
    for ev in t.poll() {
        crate::synctransport::note_event(root, &ev);
        // Pairing claims first — Incoming links and handshake
        // frames belong to live pair sessions, not the op-log.
        if crate::syncpair::claim_event(root, &t, &ev) {
            continue;
        }
        if let crate::synctransport::TransportEvent::Frame { payload, .. } = ev {
            frames += 1;
            if let Ok(eng) = try_engine(&data_root) {
                let eng = eng.lock().unwrap_or_else(|x| x.into_inner());
                // Wire shape is {"ops":[...]}; a bare op array is
                // accepted too so mem-loopback tests stay readable.
                let docs: Vec<serde_json::Value> =
                    match serde_json::from_slice::<serde_json::Value>(&payload) {
                        Ok(v) => v
                            .get("ops")
                            .and_then(|o| o.as_array())
                            .cloned()
                            .or_else(|| v.as_array().cloned())
                            .unwrap_or_default(),
                        Err(_) => Vec::new(),
                    };
                if !docs.is_empty() {
                    if let Ok(rep) = eng.import(&docs) {
                        imported += rep.applied;
                    }
                }
            }
        }
    }
    serde_json::json!({"ok": true, "frames": frames, "applied": imported}).to_string()
}

/// Outbound dispatch: export the op-log into one `{"ops":[...]}`
/// frame and fan it out to every live link — opening an outbound
/// link to each member endpoint that lacks one.  The export
/// cursor stays stateless (since_seq 0): content-addressed dedup
/// makes resends free, so a full snapshot per push is correct.
#[uniffi::export]
pub fn sync_transport_push(data_root: String) -> String {
    let root = Path::new(&data_root);
    let Some(t) = crate::synctransport::running(root) else {
        return r#"{"ok":true,"sent":0,"note":"no transport running"}"#.to_string();
    };
    let eng = match try_engine(&data_root) {
        Ok(e) => e.lock().unwrap_or_else(|x| x.into_inner()),
        Err(e) => return json_err(&e),
    };
    let our_id = eng.device_id();
    let nick = format!("member-{}", &our_id[..16.min(our_id.len())]);
    let ops = match eng.export(None, 0) {
        Ok(o) => o,
        Err(e) => return json_err(&e),
    };
    let docs: Vec<serde_json::Value> = ops.iter().map(|o| o.to_json()).collect();
    if docs.is_empty() {
        return r#"{"ok":true,"sent":0,"note":"oplog empty"}"#.to_string();
    }
    let frame = serde_json::json!({"ops": docs}).to_string().into_bytes();
    let members = eng.members_list().unwrap_or_default();
    drop(eng);
    // Open outbound links to member endpoints we have none for.
    for m in &members {
        if m.onion_addr.is_empty()
            || crate::synctransport::outbound_link(root, &m.onion_addr).is_some()
        {
            continue;
        }
        if let Ok(link) = t.connect(&m.onion_addr, &nick) {
            crate::synctransport::register_outbound(root, &m.onion_addr, link);
        }
    }
    let mut sent = 0usize;
    let mut failed = 0usize;
    for link in crate::synctransport::live_links(root) {
        match t.send(link, &frame) {
            Ok(()) => sent += 1,
            Err(_) => {
                failed += 1;
                crate::synctransport::drop_link(root, link);
            }
        }
    }
    serde_json::json!({"ok": true, "sent": sent, "failed": failed, "ops": docs.len()})
        .to_string()
}
