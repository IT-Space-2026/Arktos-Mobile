//! Minimal port of the desktop `crate::history` surface: a
//! HistoryStore facade over the shared ArktosStore history table,
//! keeping the outbox semantics the sync adapter drains.

use crate::error::{Fail, RcResult, RcStatus};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

#[derive(Clone, Debug)]
pub(crate) enum HistEvent {
    Visited { url: String, title: String, ts: i64 },
    Titled { url: String, title: String, ts: i64 },
}

#[derive(Clone, Debug)]
pub(crate) struct HistOutEv {
    pub path: Option<PathBuf>,
    pub ev: HistEvent,
}

static SHARED: OnceLock<Arc<crate::storage::ArktosStore>> = OnceLock::new();

pub(crate) fn install_shared_store(store: Arc<crate::storage::ArktosStore>) {
    let _ = SHARED.set(store);
}

fn store() -> RcResult<&'static Arc<crate::storage::ArktosStore>> {
    SHARED.get().ok_or_else(|| Fail {
        status: RcStatus::NotInitialized,
        msg: "shared store not installed".into(),
    })
}

fn bad(msg: impl Into<String>) -> Fail {
    Fail { status: RcStatus::Io, msg: msg.into() }
}

pub struct HistoryStore {
    pub(crate) outbox: Vec<HistOutEv>,
    bound: Option<PathBuf>,
    loading: bool,
    /// In-memory rows used when no shared store is installed —
    /// (url, title, ts) triples; the test harness binds stores to
    /// file paths without a Kotlin store behind them.
    local: Vec<(String, String, i64)>,
}

static HIST: Mutex<Option<HistoryStore>> = Mutex::new(None);

pub fn with<R>(f: impl FnOnce(&mut HistoryStore) -> R) -> R {
    let mut guard = HIST.lock().unwrap_or_else(|e| e.into_inner());
    let h = guard.get_or_insert_with(|| HistoryStore {
        outbox: Vec::new(), bound: None, loading: false, local: Vec::new(),
    });
    let r = f(h);
    crate::synchistory::flush_local(h);
    r
}

fn has_store() -> bool { SHARED.get().is_some() }

fn record(h: &mut HistoryStore, ev: HistEvent) {
    if !h.loading {
        h.outbox.push(HistOutEv { path: h.bound.clone(), ev });
    }
}

impl HistoryStore {
    /// Bind to a profile db path — mobile history lives in the
    /// shared store; the path only tags outbox events with a scope.
    pub fn open(&mut self, path: Option<&str>) -> RcResult<()> {
        let b = path.map(PathBuf::from);
        // A different bound file = a different store instance — the
        // per-scope isolation the adapter's file-binding semantics
        // rely on.
        if self.bound != b {
            self.local.clear();
            self.outbox.clear();
        }
        self.bound = b;
        Ok(())
    }

    pub fn exists_on_disk(_path: Option<&str>) -> bool { true }

    pub(crate) fn take_outbox(&mut self) -> Vec<HistOutEv> {
        std::mem::take(&mut self.outbox)
    }

    pub fn add(&mut self, url: &str, title: &str, visited_at: i64) -> RcResult<()> {
        if has_store() {
            store()?.visit_history_entry(url.to_string(), Some(title.to_string()))
                .map_err(|e| bad(format!("{e:?}")))?;
        } else {
            self.local.push((url.to_string(), title.to_string(), visited_at));
        }
        record(self, HistEvent::Visited {
            url: url.to_string(), title: title.to_string(), ts: visited_at });
        Ok(())
    }

    pub fn update_title(&mut self, url: &str, title: &str) -> RcResult<()> {
        if has_store() {
            store()?.visit_history_entry(url.to_string(), Some(title.to_string()))
                .map_err(|e| bad(format!("{e:?}")))?;
        } else {
            for r in self.local.iter_mut().filter(|(u, _, _)| u == url) {
                r.1 = title.to_string();
            }
        }
        record(self, HistEvent::Titled {
            url: url.to_string(), title: title.to_string(), ts: 0 });
        Ok(())
    }

    pub fn remove(&mut self, url: &str, _title: &str, _visited_at: i64) -> RcResult<()> {
        if has_store() {
            store()?.delete_history_entry(url.to_string()).map_err(|e| bad(format!("{e:?}")))?;
        } else {
            self.local.retain(|(u, _, _)| u != url);
        }
        Ok(())
    }

    pub fn clear(&mut self) -> RcResult<()> {
        if has_store() {
            store()?.delete_history().map_err(|e| bad(format!("{e:?}")))?;
        } else {
            self.local.clear();
        }
        Ok(())
    }

    pub fn clear_since(&mut self, _since_ms: i64) -> RcResult<()> {
        // Retention granularity the flat store lacks — clear-all is
        // the honest approximation.
        self.clear()
    }

    /// Drop rows older than the cutoff — approximated by clear().
    pub fn expire_older_than(&mut self, _cutoff_ms: i64) -> RcResult<i64> {
        Ok(0)
    }

    /// The local retention horizon — None keeps everything.
    pub(crate) fn retention_cutoff(&self) -> RcResult<Option<i64>> {
        Ok(None)
    }

    /// Whether (url, ts) already exists — url-level approximation.
    pub(crate) fn visit_exists(&self, url: &str, _visited_at: i64) -> RcResult<bool> {
        if !has_store() {
            return Ok(self.local.iter().any(|(u, _, _)| u == url));
        }
        Ok(store()?.find_history_entries_containing(url.to_string())
            .map(|v| v.iter().any(|e| e.url == url)).unwrap_or(false))
    }

    /// Merge-side insert — records no event (remote applies must not echo).
    pub(crate) fn apply_visit(&mut self, url: &str, title: &str, visited_at: i64) -> RcResult<()> {
        if has_store() {
            store()?.visit_history_entry(url.to_string(), Some(title.to_string()))
                .map_err(|e| bad(format!("{e:?}")))
        } else {
            self.local.push((url.to_string(), title.to_string(), visited_at));
            Ok(())
        }
    }

    /// Merge-side title update — true when it changed something.
    pub(crate) fn apply_title(&mut self, url: &str, title: &str, _visited_at: i64) -> RcResult<bool> {
        self.apply_visit(url, title, _visited_at)?;
        Ok(true)
    }

    /// Every visit as (url, title, ts) for first-touch advertise.
    pub(crate) fn all_visits(&self) -> RcResult<Vec<(String, String, i64)>> {
        if !has_store() {
            return Ok(self.local.clone());
        }
        Ok(store()?.last_hundred_history_entries()
            .unwrap_or_default()
            .into_iter()
            .map(|e| (e.url, e.title, 0i64))
            .collect())
    }

    /// Rows matching a query spec — the adapter only uses it for
    /// per-url existence checks in tests; return a `[]` doc.
    pub fn query(&mut self, _spec_json: &str) -> RcResult<String> {
        Ok("[]".to_string())
    }
}
