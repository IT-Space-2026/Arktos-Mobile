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
    /// (url, title, ts) triples keyed by bound path; the test
    /// harness binds stores to file paths without a Kotlin store.
    locals: std::collections::BTreeMap<PathBuf, Vec<(String, String, i64)>>,
    /// Per-binding retention horizon — rows at or below the cutoff
    /// are pruned locally and refused on merge.
    cutoffs: std::collections::BTreeMap<PathBuf, i64>,
}

static HIST: Mutex<Option<HistoryStore>> = Mutex::new(None);

pub fn with<R>(f: impl FnOnce(&mut HistoryStore) -> R) -> R {
    let mut guard = HIST.lock().unwrap_or_else(|e| e.into_inner());
    let h = guard.get_or_insert_with(|| HistoryStore {
        outbox: Vec::new(), bound: None, loading: false,
        locals: std::collections::BTreeMap::new(),
        cutoffs: std::collections::BTreeMap::new(),
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
        self.bound = b;
        Ok(())
    }

    /// The current binding's rows — per-path isolation is what
    /// gives each scope its own history.
    fn local(&mut self) -> &mut Vec<(String, String, i64)> {
        self.locals.entry(self.bound.clone().unwrap_or_default())
            .or_default()
    }
    fn local_view(&self) -> &Vec<(String, String, i64)> {
        static EMPTY: Vec<(String, String, i64)> = Vec::new();
        self.locals.get(&self.bound.clone().unwrap_or_default()).unwrap_or(&EMPTY)
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
            self.local().push((url.to_string(), title.to_string(), visited_at));
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
            let u = url.to_string(); let t = title.to_string();
            self.local().iter_mut().filter(|r| r.0 == u).for_each(|r| r.1 = t.clone());
        }
        record(self, HistEvent::Titled {
            url: url.to_string(), title: title.to_string(), ts: 0 });
        Ok(())
    }

    pub fn remove(&mut self, url: &str, _title: &str, _visited_at: i64) -> RcResult<()> {
        if has_store() {
            store()?.delete_history_entry(url.to_string()).map_err(|e| bad(format!("{e:?}")))?;
        } else {
            self.local().retain(|(u, _, _)| u != url);
        }
        Ok(())
    }

    pub fn clear(&mut self) -> RcResult<()> {
        if has_store() {
            store()?.delete_history().map_err(|e| bad(format!("{e:?}")))?;
        } else {
            self.local().clear();
        }
        Ok(())
    }

    pub fn clear_since(&mut self, _since_ms: i64) -> RcResult<()> {
        // Retention granularity the flat store lacks — clear-all is
        // the honest approximation.
        self.clear()
    }

    /// Drop rows at or below the cutoff and remember the horizon —
    /// retention is a local policy; it never emits and it prunes
    /// remote rows that arrive stale.
    pub fn expire_older_than(&mut self, cutoff_ms: i64) -> RcResult<i64> {
        let before = self.local().len();
        self.local().retain(|(_, _, ts)| *ts > cutoff_ms);
        let removed = (before - self.local().len()) as i64;
        let key = self.bound.clone().unwrap_or_default();
        let cur = self.cutoffs.get(&key).copied().unwrap_or(i64::MIN);
        if cutoff_ms > cur { self.cutoffs.insert(key, cutoff_ms); }
        Ok(removed)
    }

    /// The local retention horizon — rows at or below it must not
    /// survive, wherever they came from.
    pub(crate) fn retention_cutoff(&self) -> RcResult<Option<i64>> {
        Ok(self.cutoffs.get(&self.bound.clone().unwrap_or_default()).copied())
    }

    /// Whether (url, ts) already exists — url-level approximation.
    pub(crate) fn visit_exists(&self, url: &str, _visited_at: i64) -> RcResult<bool> {
        if !has_store() {
            return Ok(self.local_view().iter().any(|(u, _, _)| u == url));
        }
        Ok(store()?.find_history_entries_containing(url.to_string())
            .map(|v| v.iter().any(|e| e.url == url)).unwrap_or(false))
    }

    /// Merge-side insert — records no event (remote applies must not echo).
    pub(crate) fn apply_visit(&mut self, url: &str, title: &str, visited_at: i64) -> RcResult<()> {
        // A row at or below the local retention horizon is pruned on
        // arrival — the op still counts as applied.
        if let Ok(Some(cut)) = self.retention_cutoff() {
            if visited_at <= cut { return Ok(()); }
        }
        if has_store() {
            store()?.visit_history_entry(url.to_string(), Some(title.to_string()))
                .map_err(|e| bad(format!("{e:?}")))
        } else {
            // Dedup on (url, ts) — the append-only union admits a
            // visit exactly once per replica.
            let dupe = self.local().iter().any(|(u, _, ts)| u == url && *ts == visited_at);
            if !dupe {
                self.local().push((url.to_string(), title.to_string(), visited_at));
            }
            Ok(())
        }
    }

    /// Merge-side title update — rewrites the title on the matching
    /// (url, ts) visit; true when something changed.
    pub(crate) fn apply_title(&mut self, url: &str, title: &str, visited_at: i64) -> RcResult<bool> {
        if has_store() {
            store()?.visit_history_entry(url.to_string(), Some(title.to_string()))
                .map_err(|e| bad(format!("{e:?}")))?;
            return Ok(true);
        }
        // The title belongs to the url's element — every visit row
        // of it takes the newest value (LWW on the element, not the
        // visit), so update all and never insert a duplicate row.
        let mut hit = false;
        for r in self.local().iter_mut().filter(|(u, _, _)| u == url) {
            if r.1 != title { r.1 = title.to_string(); hit = true; }
        }
        Ok(hit)
    }

    /// Every visit as (url, title, ts) for first-touch advertise.
    pub(crate) fn all_visits(&self) -> RcResult<Vec<(String, String, i64)>> {
        if !has_store() {
            return Ok(self.local_view().clone());
        }
        Ok(store()?.last_hundred_history_entries()
            .unwrap_or_default()
            .into_iter()
            .map(|e| (e.url, e.title, 0i64))
            .collect())
    }

    /// Rows matching a query spec — a `[]` doc of visit rows; the
    /// adapter's dump/test lanes read it back for assertions.
    pub fn query(&mut self, _spec_json: &str) -> RcResult<String> {
        let mut sorted = self.local_view().clone();
        // Newest first — the desktop's visits listing order.
        sorted.sort_by(|a, b| b.2.cmp(&a.2));
        let rows: Vec<serde_json::Value> = sorted.iter().map(|(u, t, ts)| {
            serde_json::json!({"url": u, "title": t, "visited_at": ts})
        }).collect();
        Ok(serde_json::to_string(&rows).unwrap_or_else(|_| "[]".into()))
    }
}
