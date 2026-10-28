//! Minimal port of the desktop `crate::settings` surface: an
//! RcSettings facade backed by a settings.json under the data
//! root — the per-key LWW map the sync adapter advertises/applies.

use crate::error::{Fail, RcResult, RcStatus};
use serde_json::{json, Value as JValue};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

#[derive(Clone, Debug, PartialEq)]
pub enum Value {
    Str(String),
    Bool(bool),
    Int(i64),
    Dbl(f64),
    Bytes(Vec<u8>),
    List(Vec<String>),
}

#[derive(Clone, Debug, PartialEq)]
pub enum Scope {
    Global,
    Profile(String),
}

struct State {
    file: PathBuf,
    scope: Scope,
    values: BTreeMap<String, Value>,
}

pub struct RcSettings {
    state: Mutex<State>,
    root: String,
}

fn io_fail<T>(msg: impl Into<String>) -> RcResult<T> {
    Err(Fail { status: RcStatus::Io, msg: msg.into() })
}

fn load(file: &Path) -> BTreeMap<String, Value> {
    let Ok(raw) = std::fs::read_to_string(file) else { return BTreeMap::new() };
    let Ok(doc) = serde_json::from_str::<JValue>(&raw) else { return BTreeMap::new() };
    let mut out = BTreeMap::new();
    if let Some(obj) = doc.get("values").and_then(|v| v.as_object()) {
        for (k, v) in obj {
            if let Some(x) = value_from_json(v) { out.insert(k.clone(), x); }
        }
    }
    out
}

fn save(file: &Path, values: &BTreeMap<String, Value>) -> RcResult<()> {
    let obj: serde_json::Map<String, JValue> = values.iter()
        .map(|(k, v)| (k.clone(), value_to_json(v))).collect();
    crate::store::atomic_write(file,
        serde_json::to_string_pretty(&json!({"values": obj})).unwrap_or_default().as_bytes())
}

impl RcSettings {
    /// Open the single-profile settings store — profile_id is kept
    /// on the handle for the adapter's scope bookkeeping only.
    pub fn open(data_root: &str, profile_id: Option<&str>) -> RcResult<RcSettings> {
        let file = crate::facade::profile_dir(
                Path::new(data_root),
                profile_id.unwrap_or("default"),
            ).join("settings.json");
        if let Some(d) = file.parent() { let _ = std::fs::create_dir_all(d); }
        Ok(RcSettings {
            state: Mutex::new(State {
                values: load(&file), file,
                scope: profile_id.map(|p| Scope::Profile(p.to_string()))
                    .unwrap_or(Scope::Global),
            }),
            root: data_root.to_string(),
        })
    }

    pub fn contains(&self, key: &str) -> RcResult<bool> {
        Ok(self.state.lock().unwrap_or_else(|e| e.into_inner()).values.contains_key(key))
    }

    pub fn get(&self, key: &str) -> RcResult<Value> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
            .values.get(key).cloned()
            .ok_or_else(|| Fail { status: RcStatus::InvalidArgument, msg: "no such key".into() })
    }

    /// Local mutation — persists, then offers the change to the
    /// sync adapter's emit lane.
    pub fn set(&self, key: &str, value: Value) -> RcResult<()> {
        let (file, vals) = {
            let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
            st.values.insert(key.to_string(), value.clone());
            (st.file.clone(), st.values.clone())
        };
        save(&file, &vals)?;
        crate::syncsettings::emit_local(&file, key, &[], Some(&value));
        Ok(())
    }

    /// Merge-side write — persists without offering (no echo).
    pub(crate) fn set_applied(&self, key: &str, value: Value) -> RcResult<bool> {
        let (file, vals) = {
            let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
            st.values.insert(key.to_string(), value);
            (st.file.clone(), st.values.clone())
        };
        save(&file, &vals)?;
        Ok(true)
    }

    pub fn remove(&self, key: &str) -> RcResult<()> {
        let (file, vals) = {
            let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
            st.values.remove(key);
            (st.file.clone(), st.values.clone())
        };
        save(&file, &vals)
    }

    pub(crate) fn remove_applied(&self, key: &str) -> RcResult<bool> {
        self.remove(key).map(|_| true)
    }

    /// Every key under an optional prefix — advertise enumerates it.
    pub fn list(&self, prefix: Option<&str>) -> RcResult<Vec<String>> {
        let st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        Ok(st.values.keys()
            .filter(|k| prefix.map(|p| k.starts_with(p)).unwrap_or(true))
            .cloned().collect())
    }

    pub fn sync(&self) -> RcResult<()> {
        let st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        save(&st.file, &st.values)
    }

    pub fn profile_id(&self) -> Option<String> {
        match &self.state.lock().unwrap_or_else(|e| e.into_inner()).scope {
            Scope::Profile(p) => Some(p.clone()),
            Scope::Global => None,
        }
    }

    pub fn scope_dir(&self) -> PathBuf {
        PathBuf::from(&self.root)
    }
}

/// Keys that must never round-trip — device-local and secret-ish
/// names, same intent as the desktop's check_key.
pub(crate) fn check_key(key: &str) -> RcResult<String> {
    let k = key.trim();
    if k.is_empty() || k.len() > 200 || k.bytes().any(|b| b < 0x20) {
        return Err(Fail { status: RcStatus::InvalidArgument,
            msg: format!("bad settings key {key:?}") });
    }
    Ok(k.to_string())
}

/// The wire form of a Value — typed single-key object.
pub(crate) fn value_to_json(v: &Value) -> serde_json::Value {
    match v {
        Value::Str(s) => json!({"str": s}),
        Value::Bool(b) => json!({"bool": b}),
        Value::Int(i) => json!({"int": i}),
        Value::Dbl(d) => json!({"dbl": d}),
        Value::Bytes(b) => json!({"bytes": crate::mw::b64_encode(b)}),
        Value::List(l) => json!({"list": l}),
    }
}

/// Parse a typed value doc — anything else is dropped, not guessed.
pub(crate) fn value_from_json(v: &serde_json::Value) -> Option<Value> {
    if let Some(s) = v.get("str").and_then(|x| x.as_str()) { return Some(Value::Str(s.to_string())); }
    if let Some(b) = v.get("bool").and_then(|x| x.as_bool()) { return Some(Value::Bool(b)); }
    if let Some(i) = v.get("int").and_then(|x| x.as_i64()) { return Some(Value::Int(i)); }
    if let Some(d) = v.get("dbl").and_then(|x| x.as_f64()) { return Some(Value::Dbl(d)); }
    if let Some(b) = v.get("bytes").and_then(|x| x.as_str()) {
        return crate::mw::b64_decode(b).map(Value::Bytes);
    }
    if let Some(l) = v.get("list").and_then(|x| x.as_array()) {
        return Some(Value::List(l.iter().filter_map(|x| x.as_str().map(String::from)).collect()));
    }
    None
}
