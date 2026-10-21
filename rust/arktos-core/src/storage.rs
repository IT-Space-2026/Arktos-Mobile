// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
//! Rust storage core (STORE01).
//!
//! One `ArktosStore` object behind UniFFI, backed by a single bundled
//! SQLite database (`rusqlite`, `bundled-sqlcipher-vendored-openssl`
//! — SQLCipher full-database AES-256 page encryption with vendored
//! OpenSSL; Android's `libsqlite` is not part of the NDK stable ABI
//! anyway). It replaces the five parallel `SQLiteOpenHelper`
//! databases that used to live in
//! `database/{bookmark,history,downloads,allowlist,javascript}`.
//!
//! SEC15 — the database file is ciphertext at rest. The 256-bit page
//! key is generated once on the Kotlin side
//! (`database/DatabaseKeyProvider`) and persisted wrapped by an
//! Android Keystore AES/GCM key, so the file alone is useless to an
//! attacker who can read app-private storage but not the device
//! keystore. Pre-SEC15 plaintext databases are migrated in place on
//! first open via `sqlcipher_export`.
//!
//! The Kotlin repositories keep their RxJava signatures and delegate
//! here; query semantics (ordering, limits, alternate-slash URL
//! matching, folder-as-path-string) are ported 1:1. The bookmark
//! table additionally carries `created_at` so sort-by-date
//! (upstream SmartCookieWeb #156) is an ORDER BY away, and folders
//! stay slash-joined paths so "save into the open folder"
//! (upstream #308) needs no schema gymnastics.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::{params, Connection, OptionalExtension};

/// Flat error surface — message strings only, matching how the old
/// Kotlin layer let `SQLiteException`s propagate through RxJava.
#[derive(Debug, Clone, uniffi::Error)]
#[uniffi(flat_error)]
pub enum StorageError {
    Sqlite(String),
    /// SEC15 — the caller passed a store key that isn't 32 raw bytes.
    Key,
    Poisoned,
}

impl std::fmt::Display for StorageError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StorageError::Sqlite(msg) => write!(f, "sqlite error: {msg}"),
            StorageError::Key => write!(f, "store key must be 32 raw bytes"),
            StorageError::Poisoned => write!(f, "store lock poisoned"),
        }
    }
}

impl From<rusqlite::Error> for StorageError {
    fn from(e: rusqlite::Error) -> Self {
        StorageError::Sqlite(e.to_string())
    }
}

/// Bookmark row crossing the FFI. `folder` is the slash-joined path
/// ("" = root); `position` is the manual ordering inside a folder;
/// `created_at` is ms since epoch, assigned at insert when 0 is passed
/// (column exists for upstream #156 sort-by-date).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StoreBookmark {
    pub url: String,
    pub title: String,
    pub folder: String,
    pub position: u32,
    pub created_at: u64,
}

/// History row (`time_visited` ms since epoch).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StoreHistoryEntry {
    pub url: String,
    pub title: String,
    pub last_time_visited: u64,
}

/// Download entry (title = file name, content_size = display string).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StoreDownloadEntry {
    pub url: String,
    pub title: String,
    pub content_size: String,
}

/// Ad-block allow-list entry (domain + creation ms).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StoreAllowListEntry {
    pub domain: String,
    pub time_created: u64,
}

/// Userscript row. Mirrors the old `JavaScriptDatabase.JavaScriptEntry`;
/// only `name` and `code` are non-null upstream — kept identical here.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StoreJavaScriptEntry {
    pub name: String,
    pub namespace: Option<String>,
    pub version: Option<String>,
    pub author: Option<String>,
    pub include: Option<String>,
    pub exclude: Option<String>,
    pub time: Option<String>,
    pub permissions: Option<String>,
    pub code: String,
    pub requirements: Option<String>,
    /// `@run-at` verbatim (`document-start`/`document-end`/`document-idle`);
    /// `None` for rows written before the column existed.
    pub run_at: Option<String>,
    /// `@noframes` — true when the script must not run in subframes.
    pub no_frames: bool,
    /// Comma-joined `@connect` domains allowed for `GM_xmlhttpRequest`
    /// (SEC06). `None` for rows written before the column existed —
    /// treated as "no @connect" (same-origin only).
    pub connects: Option<String>,
}

/// Ad-block filter-list subscription row (LIST01). `id` is the fetch
/// URL for remote lists and a `builtin:`/`file:` pseudo-URL for local
/// ones; `last_updated` is ms since epoch (0 = never fetched);
/// `host_count` is the domain count from the last successful load;
/// `custom` marks user-added rows (shown in their own settings group).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StoreAdblockSubscription {
    pub id: String,
    pub name: String,
    pub url: String,
    pub category: String,
    pub enabled: bool,
    pub last_updated: u64,
    pub host_count: u64,
    pub custom: bool,
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

/// Same transform the Kotlin `BookmarkDatabase` used: `a.com/` and
/// `a.com` are the same bookmark, so lookups try both spellings.
fn alternate_slash_url(url: &str) -> String {
    match url.strip_suffix('/') {
        Some(stripped) => stripped.to_owned(),
        None => format!("{url}/"),
    }
}

/// The single storage core. Created once from Kotlin via
/// `ArktosStore.open(path, key)`; the `Mutex` serializes calls — the
/// Kotlin side already confines DB work to a single-threaded
/// scheduler, so contention is a non-issue.
#[derive(uniffi::Object)]
pub struct ArktosStore {
    conn: Mutex<Connection>,
}

impl ArktosStore {
    fn db(&self) -> Result<MutexGuard<'_, Connection>, StorageError> {
        self.conn.lock().map_err(|_| StorageError::Poisoned)
    }

    /// SEC15 — `key` is the raw 256-bit page key. Passing it as a
    /// `x'…'` blob literal makes SQLCipher use it verbatim instead of
    /// running the passphrase PBKDF.
    fn key_literal(key: &[u8]) -> String {
        use std::fmt::Write;
        let mut hex = String::with_capacity(key.len() * 2);
        for b in key {
            let _ = write!(hex, "{b:02x}");
        }
        format!("x'{hex}'")
    }

    /// Open `path` keyed and prove the key took by reading the schema
    /// — SQLCipher defers key application to the first statement.
    fn open_keyed(path: &str, key: &[u8]) -> Result<Connection, StorageError> {
        let conn = Connection::open(path)?;
        conn.execute_batch(&format!("PRAGMA key = \"{}\"", Self::key_literal(key)))?;
        conn.query_row("SELECT COUNT(*) FROM sqlite_master", [], |_| Ok(()))?;
        Self::init(&conn)?;
        Ok(conn)
    }

    /// SEC15 — re-encrypt a plaintext `path` under `key`. Exports into
    /// a sibling `*.enc` file via `sqlcipher_export`, verifies the copy
    /// opens keyed, and only then swaps it into place — a failed
    /// migration leaves the original untouched.
    fn migrate_to_encrypted(
        plain: Connection,
        path: &str,
        key: &[u8],
    ) -> Result<(), StorageError> {
        // Fold a hot WAL into the main image so the file the export
        // reads is complete.
        let _ = plain.execute_batch("PRAGMA wal_checkpoint(TRUNCATE)");
        let enc_path = format!("{path}.enc");
        let _ = std::fs::remove_file(&enc_path);
        let escaped = enc_path.replace('\'', "''");
        plain.execute_batch(&format!(
            "ATTACH DATABASE '{escaped}' AS enc KEY \"{}\";
             SELECT sqlcipher_export('enc');
             DETACH DATABASE enc;",
            Self::key_literal(key)
        ))?;
        drop(plain);
        {
            let check = Connection::open(&enc_path)?;
            check.execute_batch(&format!(
                "PRAGMA key = \"{}\"",
                Self::key_literal(key)
            ))?;
            check.query_row("SELECT COUNT(*) FROM sqlite_master", [], |_| {
                Ok(())
            })?;
        }
        for suffix in ["-wal", "-shm", "-journal"] {
            let _ = std::fs::remove_file(format!("{path}{suffix}"));
        }
        std::fs::remove_file(path).map_err(|e| {
            StorageError::Sqlite(format!("replace plaintext store: {e}"))
        })?;
        std::fs::rename(&enc_path, path).map_err(|e| {
            StorageError::Sqlite(format!("install encrypted store: {e}"))
        })?;
        Ok(())
    }

    fn init(conn: &Connection) -> Result<(), StorageError> {
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS bookmark(
                id INTEGER PRIMARY KEY,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                folder TEXT NOT NULL DEFAULT '',
                position INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS bookmark_url_idx ON bookmark(url);
            CREATE INDEX IF NOT EXISTS bookmark_folder_idx ON bookmark(folder);
            CREATE TABLE IF NOT EXISTS history(
                id INTEGER PRIMARY KEY,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                time_visited INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS history_url_idx ON history(url);
            CREATE TABLE IF NOT EXISTS download(
                id INTEGER PRIMARY KEY,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                size TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS allowlist(
                id INTEGER PRIMARY KEY,
                url TEXT NOT NULL,
                created INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS javascript(
                id INTEGER PRIMARY KEY,
                name TEXT,
                namespace TEXT,
                author TEXT,
                version TEXT,
                include TEXT,
                exclude TEXT,
                time TEXT,
                permissions TEXT,
                code TEXT,
                requirements TEXT,
                run_at TEXT,
                no_frames INTEGER,
                connects TEXT
            );
            CREATE TABLE IF NOT EXISTS gm_value(
                script TEXT NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                PRIMARY KEY(script, key)
            );
            CREATE TABLE IF NOT EXISTS adblock_subscription(
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                url TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 0,
                last_updated INTEGER NOT NULL DEFAULT 0,
                host_count INTEGER NOT NULL DEFAULT 0,
                custom INTEGER NOT NULL DEFAULT 0
            );
            PRAGMA user_version = 3;",
        )?;
        // Databases created before run_at existed: the CREATE above
        // no-ops, so add the column if it isn't there.
        for column in ["run_at TEXT", "no_frames INTEGER", "connects TEXT"] {
            let name = column.split(' ').next().unwrap_or_default();
            if conn
                .prepare(&format!("SELECT {name} FROM javascript LIMIT 0"))
                .is_err()
            {
                conn.execute_batch(&format!(
                    "ALTER TABLE javascript ADD COLUMN {column}"
                ))?;
            }
        }
        Ok(())
    }

    #[cfg(test)]
    pub(crate) fn in_memory() -> Self {
        let conn = Connection::open_in_memory().unwrap();
        Self::init(&conn).unwrap();
        Self {
            conn: Mutex::new(conn),
        }
    }
}

type StoreResult<T> = Result<T, StorageError>;

#[uniffi::export]
impl ArktosStore {
    /// Open (and if needed create) the encrypted storage database at
    /// `path`. `key` is the raw 256-bit SQLCipher key — the Kotlin
    /// `DatabaseKeyProvider` generates it once and keeps it wrapped by
    /// an Android Keystore key. A pre-SEC15 plaintext database at
    /// `path` is transparently migrated; anything the key can't open
    /// and that isn't plaintext surfaces as `StorageError` for the
    /// caller to quarantine.
    #[uniffi::constructor(name = "open")]
    pub fn open(path: String, key: Vec<u8>) -> Result<Arc<Self>, StorageError> {
        if key.len() != 32 {
            return Err(StorageError::Key);
        }
        let conn = match Self::open_keyed(&path, &key) {
            Ok(conn) => conn,
            Err(keyed_err) => {
                // Keyed open failed — the upgrade case is a plaintext
                // file, which a SQLCipher build still opens with no
                // key at all.
                let plain = Connection::open(&path)
                    .and_then(|c| {
                        c.query_row("SELECT COUNT(*) FROM sqlite_master", [], |_| {
                            Ok(())
                        })
                        .map(|_| c)
                    })
                    .map_err(|_| keyed_err)?;
                Self::migrate_to_encrypted(plain, &path, &key)?;
                Self::open_keyed(&path, &key)?
            }
        };
        Ok(Arc::new(Self {
            conn: Mutex::new(conn),
        }))
    }

    // ---- bookmarks -------------------------------------------------

    /// First bookmark matching `url` or its trailing-slash twin.
    pub fn find_bookmark_for_url(&self, url: String) -> StoreResult<Option<StoreBookmark>> {
        let conn = self.db()?;
        conn.query_row(
            "SELECT url, title, folder, position, created_at FROM bookmark
             WHERE url = ?1 OR url = ?2 LIMIT 1",
            params![url, alternate_slash_url(&url)],
            row_to_bookmark,
        )
        .optional()
        .map_err(Into::into)
    }

    pub fn is_bookmark(&self, url: String) -> StoreResult<bool> {
        let conn = self.db()?;
        conn.query_row(
            "SELECT 1 FROM bookmark WHERE url = ?1 OR url = ?2 LIMIT 1",
            params![url, alternate_slash_url(&url)],
            |_| Ok(()),
        )
        .optional()
        .map(|r| r.is_some())
        .map_err(Into::into)
    }

    /// Insert only when no row matches `url` (or the slash twin).
    /// `created_at == 0` is replaced with the current time.
    pub fn add_bookmark_if_not_exists(&self, entry: StoreBookmark) -> StoreResult<bool> {
        let conn = self.db()?;
        insert_bookmark(&conn, entry)
    }

    /// Batch insert, one transaction — replaces the old per-item
    /// `addBookmarkIfNotExists` loop nested in a Kotlin transaction.
    pub fn add_bookmark_list(&self, entries: Vec<StoreBookmark>) -> StoreResult<()> {
        let mut conn = self.db()?;
        let tx = conn.transaction()?;
        for entry in entries {
            insert_bookmark(&tx, entry)?;
        }
        tx.commit()?;
        Ok(())
    }

    pub fn delete_bookmark(&self, url: String) -> StoreResult<bool> {
        let conn = self.db()?;
        let n = conn.execute(
            "DELETE FROM bookmark WHERE url = ?1 OR url = ?2",
            params![url, alternate_slash_url(&url)],
        )?;
        Ok(n > 0)
    }

    /// Re-order a bookmark inside its folder (upstream keyed on url).
    pub fn move_bookmark(&self, url: String, new_position: u32) -> StoreResult<()> {
        let conn = self.db()?;
        conn.execute(
            "UPDATE bookmark SET position = ?2 WHERE url = ?1",
            params![url, new_position],
        )?;
        Ok(())
    }

    /// Move every bookmark in `old_name` to `new_name`.
    pub fn rename_folder(&self, old_name: String, new_name: String) -> StoreResult<()> {
        let conn = self.db()?;
        conn.execute(
            "UPDATE bookmark SET folder = ?2 WHERE folder = ?1",
            params![old_name, new_name],
        )?;
        Ok(())
    }

    /// Delete a folder — its bookmarks move to root ("").
    pub fn delete_folder(&self, name: String) -> StoreResult<()> {
        self.rename_folder(name, String::new())
    }

    pub fn delete_all_bookmarks(&self) -> StoreResult<()> {
        self.db()?.execute("DELETE FROM bookmark", [])?;
        Ok(())
    }

    /// Replace the row at `old_url` (or its slash twin) with the new
    /// entry's fields; `created_at` of the existing row is preserved.
    pub fn edit_bookmark(&self, old_url: String, new_entry: StoreBookmark) -> StoreResult<()> {
        let conn = self.db()?;
        let updated = update_bookmark(&conn, &old_url, &new_entry)?;
        if updated == 0 {
            update_bookmark(&conn, &alternate_slash_url(&old_url), &new_entry)?;
        }
        Ok(())
    }

    /// All bookmarks in insertion order (the old `ORDER BY id`).
    pub fn all_bookmarks_sorted(&self) -> StoreResult<Vec<StoreBookmark>> {
        let conn = self.db()?;
        query_bookmarks(
            &conn,
            "SELECT url, title, folder, position, created_at FROM bookmark ORDER BY id",
            [],
        )
    }

    /// Bookmarks in one folder (`None`/`""` = root), ordered by
    /// position then case-insensitive title then url — same ordering
    /// the drawer had.
    pub fn bookmarks_in_folder(
        &self,
        folder: Option<String>,
    ) -> StoreResult<Vec<StoreBookmark>> {
        let conn = self.db()?;
        query_bookmarks(
            &conn,
            "SELECT url, title, folder, position, created_at FROM bookmark
             WHERE folder = ?1
             ORDER BY position ASC, title COLLATE NOCASE ASC, url ASC",
            params![folder.unwrap_or_default()],
        )
    }

    /// Same rows as [Self::bookmarks_in_folder] but newest first —
    /// the #156 "sort by date added" order. Rows stamped 0 (data
    /// predating the column) fall back to id order at the tail.
    pub fn bookmarks_in_folder_by_date(
        &self,
        folder: Option<String>,
    ) -> StoreResult<Vec<StoreBookmark>> {
        let conn = self.db()?;
        query_bookmarks(
            &conn,
            "SELECT url, title, folder, position, created_at FROM bookmark
             WHERE folder = ?1
             ORDER BY created_at DESC, id DESC",
            params![folder.unwrap_or_default()],
        )
    }

    /// All non-root folder paths, sorted — the folder tree for
    /// #308-style "save into folder" pickers is derivable by splitting
    /// on '/'.
    pub fn folder_names(&self) -> StoreResult<Vec<String>> {
        let conn = self.db()?;
        let mut stmt = conn.prepare(
            "SELECT DISTINCT folder FROM bookmark WHERE folder != '' ORDER BY folder ASC",
        )?;
        let rows = stmt.query_map([], |r| r.get(0))?;
        rows.collect::<Result<Vec<String>, _>>().map_err(Into::into)
    }

    pub fn bookmark_count(&self) -> StoreResult<u64> {
        let conn = self.db()?;
        conn.query_row("SELECT COUNT(*) FROM bookmark", [], |r| r.get::<_, i64>(0))
            .map(|c| c as u64)
            .map_err(Into::into)
    }

    // ---- history ---------------------------------------------------

    pub fn delete_history(&self) -> StoreResult<()> {
        self.db()?.execute("DELETE FROM history", [])?;
        Ok(())
    }

    pub fn delete_history_entry(&self, url: String) -> StoreResult<()> {
        self.db()?
            .execute("DELETE FROM history WHERE url = ?1", params![url])?;
        Ok(())
    }

    /// Upsert: refresh `title`+timestamp when the URL exists,
    /// insert otherwise. `None` title stores "" (Kotlin parity).
    pub fn visit_history_entry(&self, url: String, title: Option<String>) -> StoreResult<()> {
        let conn = self.db()?;
        let now = now_millis() as i64;
        let title = title.unwrap_or_default();
        let updated = conn.execute(
            "UPDATE history SET title = ?2, time_visited = ?3 WHERE url = ?1",
            params![url, title, now],
        )?;
        if updated == 0 {
            conn.execute(
                "INSERT INTO history(url, title, time_visited) VALUES (?1, ?2, ?3)",
                params![url, title, now],
            )?;
        }
        Ok(())
    }

    /// First 5 entries whose title or url contains `query`,
    /// newest first.
    pub fn find_history_entries_containing(
        &self,
        query: String,
    ) -> StoreResult<Vec<StoreHistoryEntry>> {
        let conn = self.db()?;
        let search = format!("%{query}%");
        let mut stmt = conn.prepare(
            "SELECT url, title, time_visited FROM history
             WHERE title LIKE ?1 OR url LIKE ?1
             ORDER BY time_visited DESC LIMIT 5",
        )?;
        let rows = stmt.query_map(params![search], |r| {
            Ok(StoreHistoryEntry {
                url: r.get(0)?,
                title: r.get(1)?,
                last_time_visited: r.get::<_, i64>(2)? as u64,
            })
        })?;
        rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
    }

    /// Last 100 visited entries, newest first.
    pub fn last_hundred_history_entries(&self) -> StoreResult<Vec<StoreHistoryEntry>> {
        let conn = self.db()?;
        let mut stmt = conn.prepare(
            "SELECT url, title, time_visited FROM history
             ORDER BY time_visited DESC LIMIT 100",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(StoreHistoryEntry {
                url: r.get(0)?,
                title: r.get(1)?,
                last_time_visited: r.get::<_, i64>(2)? as u64,
            })
        })?;
        rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
    }

    // ---- downloads -------------------------------------------------

    pub fn is_download(&self, url: String) -> StoreResult<bool> {
        let conn = self.db()?;
        conn.query_row(
            "SELECT 1 FROM download WHERE url = ?1 LIMIT 1",
            params![url],
            |_| Ok(()),
        )
        .optional()
        .map(|r| r.is_some())
        .map_err(Into::into)
    }

    pub fn find_download_for_url(
        &self,
        url: String,
    ) -> StoreResult<Option<StoreDownloadEntry>> {
        let conn = self.db()?;
        conn.query_row(
            "SELECT url, title, size FROM download WHERE url = ?1 LIMIT 1",
            params![url],
            |r| {
                Ok(StoreDownloadEntry {
                    url: r.get(0)?,
                    title: r.get(1)?,
                    content_size: r.get(2)?,
                })
            },
        )
        .optional()
        .map_err(Into::into)
    }

    pub fn add_download_if_not_exists(&self, entry: StoreDownloadEntry) -> StoreResult<bool> {
        let conn = self.db()?;
        let exists = conn
            .query_row(
                "SELECT 1 FROM download WHERE url = ?1 LIMIT 1",
                params![entry.url],
                |_| Ok(()),
            )
            .optional()?
            .is_some();
        if exists {
            return Ok(false);
        }
        conn.execute(
            "INSERT INTO download(url, title, size) VALUES (?1, ?2, ?3)",
            params![entry.url, entry.title, entry.content_size],
        )?;
        Ok(true)
    }

    pub fn add_downloads_list(&self, entries: Vec<StoreDownloadEntry>) -> StoreResult<()> {
        let mut conn = self.db()?;
        let tx = conn.transaction()?;
        for entry in entries {
            let exists = tx
                .query_row(
                    "SELECT 1 FROM download WHERE url = ?1 LIMIT 1",
                    params![entry.url],
                    |_| Ok(()),
                )
                .optional()?
                .is_some();
            if !exists {
                tx.execute(
                    "INSERT INTO download(url, title, size) VALUES (?1, ?2, ?3)",
                    params![entry.url, entry.title, entry.content_size],
                )?;
            }
        }
        tx.commit()?;
        Ok(())
    }

    pub fn delete_download(&self, url: String) -> StoreResult<bool> {
        let conn = self.db()?;
        Ok(conn.execute("DELETE FROM download WHERE url = ?1", params![url])? > 0)
    }

    pub fn delete_all_downloads(&self) -> StoreResult<()> {
        self.db()?.execute("DELETE FROM download", [])?;
        Ok(())
    }

    /// All downloads, newest first (`ORDER BY id DESC`).
    pub fn all_downloads(&self) -> StoreResult<Vec<StoreDownloadEntry>> {
        let conn = self.db()?;
        let mut stmt = conn
            .prepare("SELECT url, title, size FROM download ORDER BY id DESC")?;
        let rows = stmt.query_map([], |r| {
            Ok(StoreDownloadEntry {
                url: r.get(0)?,
                title: r.get(1)?,
                content_size: r.get(2)?,
            })
        })?;
        rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
    }

    pub fn download_count(&self) -> StoreResult<u64> {
        let conn = self.db()?;
        conn.query_row("SELECT COUNT(*) FROM download", [], |r| r.get::<_, i64>(0))
            .map(|c| c as u64)
            .map_err(Into::into)
    }

    // ---- ad-block allow list ---------------------------------------

    /// All allow-list entries, newest first.
    pub fn all_allowlist_items(&self) -> StoreResult<Vec<StoreAllowListEntry>> {
        let conn = self.db()?;
        let mut stmt = conn
            .prepare("SELECT url, created FROM allowlist ORDER BY created DESC")?;
        let rows = stmt.query_map([], |r| {
            Ok(StoreAllowListEntry {
                domain: r.get(0)?,
                time_created: r.get::<_, i64>(1)? as u64,
            })
        })?;
        rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
    }

    pub fn allowlist_item_for_url(
        &self,
        url: String,
    ) -> StoreResult<Option<StoreAllowListEntry>> {
        let conn = self.db()?;
        conn.query_row(
            "SELECT url, created FROM allowlist WHERE url = ?1
             ORDER BY created DESC LIMIT 1",
            params![url],
            |r| {
                Ok(StoreAllowListEntry {
                    domain: r.get(0)?,
                    time_created: r.get::<_, i64>(1)? as u64,
                })
            },
        )
        .optional()
        .map_err(Into::into)
    }

    /// Plain insert — the old repo did not dedupe, kept identical.
    pub fn add_allowlist_item(&self, domain: String, time_created: u64) -> StoreResult<()> {
        self.db()?.execute(
            "INSERT INTO allowlist(url, created) VALUES (?1, ?2)",
            params![domain, time_created as i64],
        )?;
        Ok(())
    }

    pub fn remove_allowlist_item(&self, domain: String) -> StoreResult<()> {
        self.db()?
            .execute("DELETE FROM allowlist WHERE url = ?1", params![domain])?;
        Ok(())
    }

    pub fn clear_allowlist(&self) -> StoreResult<()> {
        self.db()?.execute("DELETE FROM allowlist", [])?;
        Ok(())
    }

    // ---- userscripts ------------------------------------------------

    pub fn delete_all_javascript(&self) -> StoreResult<()> {
        self.db()?.execute("DELETE FROM javascript", [])?;
        Ok(())
    }

    pub fn delete_javascript_entry(&self, name: String) -> StoreResult<()> {
        self.db()?
            .execute("DELETE FROM javascript WHERE name = ?1", params![name])?;
        Ok(())
    }

    /// First 5 entries whose `name` contains `query`, newest first.
    pub fn find_javascript_entries_containing(
        &self,
        query: String,
    ) -> StoreResult<Vec<StoreJavaScriptEntry>> {
        let conn = self.db()?;
        let search = format!("%{query}%");
        let mut stmt = conn.prepare(
            "SELECT name, namespace, author, version, include, exclude, time,
                    permissions, code, requirements, run_at, no_frames, connects
             FROM javascript WHERE name LIKE ?1
             ORDER BY id DESC LIMIT 5",
        )?;
        let rows = stmt.query_map(params![search], row_to_javascript)?;
        rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
    }

    /// Last 100 entries, newest first.
    pub fn last_hundred_javascript_entries(
        &self,
    ) -> StoreResult<Vec<StoreJavaScriptEntry>> {
        let conn = self.db()?;
        let mut stmt = conn.prepare(
            "SELECT name, namespace, author, version, include, exclude, time,
                    permissions, code, requirements, run_at, no_frames, connects
             FROM javascript ORDER BY id DESC LIMIT 100",
        )?;
        let rows = stmt.query_map([], row_to_javascript)?;
        rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
    }

    /// Insert only when no entry with `entry.name` exists.
    pub fn add_javascript_if_not_exists(
        &self,
        entry: StoreJavaScriptEntry,
    ) -> StoreResult<bool> {
        let conn = self.db()?;
        let exists = conn
            .query_row(
                "SELECT 1 FROM javascript WHERE name = ?1 LIMIT 1",
                params![entry.name],
                |_| Ok(()),
            )
            .optional()?
            .is_some();
        if exists {
            return Ok(false);
        }
        conn.execute(
            "INSERT INTO javascript(name, namespace, author, version, include,
                                    exclude, time, permissions, code, requirements,
                                    run_at, no_frames, connects)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
            params![
                entry.name,
                entry.namespace,
                entry.author,
                entry.version,
                entry.include,
                entry.exclude,
                entry.time,
                entry.permissions,
                entry.code,
                entry.requirements,
                entry.run_at,
                entry.no_frames,
                entry.connects,
            ],
        )?;
        Ok(true)
    }

    // ---- GM_* script storage ----
    // Values are stored as JSON text — the JS side keeps a snapshot
    // mirror for synchronous `GM_getValue` and posts writes here.

    pub fn gm_set_value(
        &self,
        script: String,
        key: String,
        value: String,
    ) -> StoreResult<()> {
        self.db()?.execute(
            "INSERT OR REPLACE INTO gm_value(script, key, value)
             VALUES (?1, ?2, ?3)",
            params![script, key, value],
        )?;
        Ok(())
    }

    pub fn gm_delete_value(&self, script: String, key: String) -> StoreResult<()> {
        self.db()?.execute(
            "DELETE FROM gm_value WHERE script = ?1 AND key = ?2",
            params![script, key],
        )?;
        Ok(())
    }

    /// Every stored key for a script — loaded once at inject time so
    /// `GM_getValue` can answer synchronously from the snapshot.
    pub fn gm_values_for_script(
        &self,
        script: String,
    ) -> StoreResult<HashMap<String, String>> {
        let conn = self.db()?;
        let mut stmt = conn
            .prepare("SELECT key, value FROM gm_value WHERE script = ?1")?;
        let rows = stmt.query_map(params![script], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
        })?;
        rows.collect::<Result<HashMap<_, _>, _>>().map_err(Into::into)
    }

    /// All keys for a script, sorted for a stable `GM_listValues`.
    pub fn gm_keys_for_script(
        &self,
        script: String,
    ) -> StoreResult<Vec<String>> {
        let conn = self.db()?;
        let mut stmt = conn.prepare(
            "SELECT key FROM gm_value WHERE script = ?1 ORDER BY key",
        )?;
        let rows = stmt.query_map(params![script], |r| r.get::<_, String>(0))?;
        rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
    }

    /// Remove a script's value store — called when it is uninstalled.
    pub fn gm_delete_script_values(&self, script: String) -> StoreResult<()> {
        self.db()?.execute(
            "DELETE FROM gm_value WHERE script = ?1",
            params![script],
        )?;
        Ok(())
    }

    pub fn javascript_count(&self) -> StoreResult<u64> {
        let conn = self.db()?;
        conn.query_row("SELECT COUNT(*) FROM javascript", [], |r| r.get::<_, i64>(0))
            .map(|c| c as u64)
            .map_err(Into::into)
    }

    // ---- ad-block subscriptions (LIST01) ---------------------------

    /// All subscriptions in insertion (seed) order — the settings UI
    /// re-groups them by category.
    pub fn all_adblock_subscriptions(&self) -> StoreResult<Vec<StoreAdblockSubscription>> {
        let conn = self.db()?;
        let mut stmt = conn.prepare(
            "SELECT id, name, url, category, enabled, last_updated, host_count, custom
             FROM adblock_subscription ORDER BY rowid",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(StoreAdblockSubscription {
                id: r.get(0)?,
                name: r.get(1)?,
                url: r.get(2)?,
                category: r.get(3)?,
                enabled: r.get::<_, i64>(4)? != 0,
                last_updated: r.get::<_, i64>(5)? as u64,
                host_count: r.get::<_, i64>(6)? as u64,
                custom: r.get::<_, i64>(7)? != 0,
            })
        })?;
        rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
    }

    /// Insert only when `entry.id` is new — returns true when a row
    /// was added. Used both for catalog seeding and custom lists.
    pub fn add_adblock_subscription(
        &self,
        entry: StoreAdblockSubscription,
    ) -> StoreResult<bool> {
        let conn = self.db()?;
        let n = conn.execute(
            "INSERT OR IGNORE INTO adblock_subscription
                (id, name, url, category, enabled, last_updated, host_count, custom)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![
                entry.id,
                entry.name,
                entry.url,
                entry.category,
                entry.enabled as i64,
                entry.last_updated as i64,
                entry.host_count as i64,
                entry.custom as i64,
            ],
        )?;
        Ok(n > 0)
    }

    pub fn set_adblock_subscription_enabled(
        &self,
        id: String,
        enabled: bool,
    ) -> StoreResult<()> {
        self.db()?.execute(
            "UPDATE adblock_subscription SET enabled = ?2 WHERE id = ?1",
            params![id, enabled as i64],
        )?;
        Ok(())
    }

    /// Stamp a list after a load: `last_updated` ms epoch and the
    /// extracted domain count for the settings row.
    pub fn set_adblock_subscription_state(
        &self,
        id: String,
        last_updated: u64,
        host_count: u64,
    ) -> StoreResult<()> {
        self.db()?.execute(
            "UPDATE adblock_subscription SET last_updated = ?2, host_count = ?3
             WHERE id = ?1",
            params![id, last_updated as i64, host_count as i64],
        )?;
        Ok(())
    }

    /// Update a custom row's display name/URL (preset rows keep their
    /// catalog identity).
    pub fn update_adblock_subscription(
        &self,
        id: String,
        name: String,
        url: String,
    ) -> StoreResult<()> {
        self.db()?.execute(
            "UPDATE adblock_subscription SET name = ?2, url = ?3 WHERE id = ?1",
            params![id, name, url],
        )?;
        Ok(())
    }

    pub fn delete_adblock_subscription(&self, id: String) -> StoreResult<()> {
        self.db()?.execute(
            "DELETE FROM adblock_subscription WHERE id = ?1",
            params![id],
        )?;
        Ok(())
    }
}

fn row_to_bookmark(r: &rusqlite::Row<'_>) -> rusqlite::Result<StoreBookmark> {
    Ok(StoreBookmark {
        url: r.get(0)?,
        title: r.get(1)?,
        folder: r.get::<_, Option<String>>(2)?.unwrap_or_default(),
        position: r.get::<_, u32>(3)?,
        created_at: r.get::<_, i64>(4)? as u64,
    })
}

fn row_to_javascript(
    r: &rusqlite::Row<'_>,
) -> rusqlite::Result<StoreJavaScriptEntry> {
    Ok(StoreJavaScriptEntry {
        name: r.get(0)?,
        namespace: r.get(1)?,
        author: r.get(2)?,
        version: r.get(3)?,
        include: r.get(4)?,
        exclude: r.get(5)?,
        time: r.get(6)?,
        permissions: r.get(7)?,
        code: r.get::<_, Option<String>>(8)?.unwrap_or_default(),
        requirements: r.get(9)?,
        run_at: r.get(10)?,
        no_frames: r.get::<_, Option<i64>>(11)?.unwrap_or(0) != 0,
        connects: r.get(12)?,
    })
}

fn insert_bookmark(conn: &Connection, entry: StoreBookmark) -> StoreResult<bool> {
    let exists = conn
        .query_row(
            "SELECT 1 FROM bookmark WHERE url = ?1 OR url = ?2 LIMIT 1",
            params![entry.url, alternate_slash_url(&entry.url)],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
    if exists {
        return Ok(false);
    }
    let created = if entry.created_at == 0 {
        now_millis()
    } else {
        entry.created_at
    };
    conn.execute(
        "INSERT INTO bookmark(url, title, folder, position, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![entry.url, entry.title, entry.folder, entry.position, created as i64],
    )?;
    Ok(true)
}

fn update_bookmark(
    conn: &Connection,
    url: &str,
    entry: &StoreBookmark,
) -> Result<usize, rusqlite::Error> {
    conn.execute(
        "UPDATE bookmark SET url = ?2, title = ?3, folder = ?4, position = ?5
         WHERE url = ?1",
        params![url, entry.url, entry.title, entry.folder, entry.position],
    )
}

fn query_bookmarks(
    conn: &Connection,
    sql: &str,
    p: impl rusqlite::Params,
) -> StoreResult<Vec<StoreBookmark>> {
    let mut stmt = conn.prepare(sql)?;
    let rows = stmt.query_map(p, row_to_bookmark)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store() -> ArktosStore {
        ArktosStore::in_memory()
    }

    /// Unique temp path — no `tempfile` dep in this crate.
    fn temp_db(tag: &str) -> String {
        let path = std::env::temp_dir().join(format!(
            "arktos-store-{tag}-{}-{}.db",
            std::process::id(),
            now_millis()
        ));
        path.to_string_lossy().into_owned()
    }

    fn key(byte: u8) -> Vec<u8> {
        vec![byte; 32]
    }

    fn is_plain_sqlite(path: &str) -> bool {
        std::fs::read(path)
            .map(|b| b.starts_with(b"SQLite format 3"))
            .unwrap_or(false)
    }

    #[test]
    fn encrypted_roundtrip_ciphertext_on_disk() {
        let path = temp_db("enc");
        {
            let s = ArktosStore::open(path.clone(), key(0xA1)).unwrap();
            s.add_bookmark_if_not_exists(bookmark("https://a.com", "A", ""))
                .unwrap();
        }
        // The file is ciphertext — no SQLite magic header.
        assert!(!is_plain_sqlite(&path));
        let reopened = ArktosStore::open(path.clone(), key(0xA1)).unwrap();
        assert!(reopened.is_bookmark("https://a.com".into()).unwrap());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn wrong_key_rejected() {
        let path = temp_db("wrongkey");
        {
            let _ = ArktosStore::open(path.clone(), key(0xA1)).unwrap();
        }
        // A different key fails — and the file is ciphertext so the
        // plaintext-upgrade path can't claim it either.
        assert!(ArktosStore::open(path.clone(), key(0xB2)).is_err());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn short_key_rejected() {
        let path = temp_db("shortkey");
        let err = ArktosStore::open(path.clone(), vec![0u8; 16]).err();
        assert!(matches!(err, Some(StorageError::Key)));
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn plaintext_store_migrates() {
        let path = temp_db("migrate");
        // A pre-SEC15 database: plain rusqlite write, no key.
        {
            let plain = Connection::open(&path).unwrap();
            ArktosStore::init(&plain).unwrap();
            plain
                .execute(
                    "INSERT INTO bookmark(url, title, folder, position, created_at)
                     VALUES ('https://keep.me', 'Keep', '', 0, 1)",
                    [],
                )
                .unwrap();
        }
        assert!(is_plain_sqlite(&path));

        let s = ArktosStore::open(path.clone(), key(0xC3)).unwrap();
        // Rows survived the sqlcipher_export migration…
        let found = s.find_bookmark_for_url("https://keep.me".into()).unwrap();
        assert_eq!(found.unwrap().title, "Keep");
        drop(s);
        // …and the file is ciphertext afterwards.
        assert!(!is_plain_sqlite(&path));
        // Reopening under the same key still reads it.
        let reopened = ArktosStore::open(path.clone(), key(0xC3)).unwrap();
        assert!(reopened.is_bookmark("https://keep.me".into()).unwrap());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn corrupt_store_errors() {
        let path = temp_db("corrupt");
        std::fs::write(&path, b"definitely not a database").unwrap();
        assert!(ArktosStore::open(path.clone(), key(0xA1)).is_err());
        let _ = std::fs::remove_file(&path);
    }

    fn bookmark(url: &str, title: &str, folder: &str) -> StoreBookmark {
        StoreBookmark {
            url: url.into(),
            title: title.into(),
            folder: folder.into(),
            position: 0,
            created_at: 0,
        }
    }

    // ---- bookmarks ----

    #[test]
    fn bookmark_add_find_is_delete() {
        let s = store();
        assert!(!s.is_bookmark("https://a.com".into()).unwrap());
        assert!(s
            .add_bookmark_if_not_exists(bookmark("https://a.com", "A", ""))
            .unwrap());
        assert!(s.is_bookmark("https://a.com".into()).unwrap());
        let found = s.find_bookmark_for_url("https://a.com".into()).unwrap().unwrap();
        assert_eq!(found.title, "A");
        assert!(found.created_at > 0);
        // duplicate is rejected
        assert!(!s
            .add_bookmark_if_not_exists(bookmark("https://a.com", "A2", ""))
            .unwrap());
        assert!(s.delete_bookmark("https://a.com".into()).unwrap());
        assert!(!s.is_bookmark("https://a.com".into()).unwrap());
        assert!(!s.delete_bookmark("https://a.com".into()).unwrap());
    }

    #[test]
    fn bookmark_alternate_slash_matching() {
        let s = store();
        s.add_bookmark_if_not_exists(bookmark("https://a.com/", "A", ""))
            .unwrap();
        // no-slash lookup finds the slashed row and vice versa
        assert!(s.is_bookmark("https://a.com".into()).unwrap());
        assert!(s
            .find_bookmark_for_url("https://a.com".into())
            .unwrap()
            .is_some());
        assert!(s.delete_bookmark("https://a.com".into()).unwrap());
        assert!(!s.is_bookmark("https://a.com/".into()).unwrap());
    }

    #[test]
    fn bookmark_folder_queries() {
        let s = store();
        s.add_bookmark_list(vec![
            bookmark("https://b.com", "b", "Dev"),
            bookmark("https://a.com", "A", "Dev"),
            bookmark("https://c.com", "C", "Dev/Rust"),
            bookmark("https://r.com", "R", ""),
        ])
        .unwrap();

        assert_eq!(s.bookmark_count().unwrap(), 4);
        assert_eq!(
            s.folder_names().unwrap(),
            vec!["Dev".to_string(), "Dev/Rust".to_string()]
        );

        let dev = s.bookmarks_in_folder(Some("Dev".into())).unwrap();
        // position ties → case-insensitive title order
        assert_eq!(
            dev.iter().map(|b| b.url.as_str()).collect::<Vec<_>>(),
            vec!["https://a.com", "https://b.com"]
        );

        // #156: by-date order puts the newest insert first — a.com was
        // added after b.com (same-second stamps resolve by id DESC).
        let dev_by_date = s.bookmarks_in_folder_by_date(Some("Dev".into())).unwrap();
        assert_eq!(
            dev_by_date.iter().map(|b| b.url.as_str()).collect::<Vec<_>>(),
            vec!["https://a.com", "https://b.com"]
        );

        let root = s.bookmarks_in_folder(None).unwrap();
        assert_eq!(root.len(), 1);
        assert_eq!(root[0].url, "https://r.com");

        s.rename_folder("Dev".into(), "Work".into()).unwrap();
        assert_eq!(
            s.folder_names().unwrap(),
            vec!["Dev/Rust".to_string(), "Work".to_string()]
        );

        s.delete_folder("Work".into()).unwrap();
        // bookmarks move to root
        assert_eq!(s.bookmarks_in_folder(None).unwrap().len(), 3);
    }

    #[test]
    fn bookmark_move_and_edit() {
        let s = store();
        s.add_bookmark_if_not_exists(bookmark("https://a.com", "A", "F"))
            .unwrap();
        s.move_bookmark("https://a.com".into(), 7).unwrap();
        let moved = s.find_bookmark_for_url("https://a.com".into()).unwrap().unwrap();
        assert_eq!(moved.position, 7);

        let created = moved.created_at;
        s.edit_bookmark(
            "https://a.com".into(),
            StoreBookmark {
                url: "https://b.com".into(),
                title: "B".into(),
                folder: "G".into(),
                position: 3,
                created_at: 0,
            },
        )
        .unwrap();
        assert!(s.find_bookmark_for_url("https://a.com".into()).unwrap().is_none());
        let edited = s.find_bookmark_for_url("https://b.com".into()).unwrap().unwrap();
        assert_eq!(edited.title, "B");
        assert_eq!(edited.folder, "G");
        assert_eq!(edited.position, 3);
        assert_eq!(edited.created_at, created);
    }

    #[test]
    fn bookmark_list_insert_is_transactional_and_dedupes() {
        let s = store();
        s.add_bookmark_if_not_exists(bookmark("https://x.com", "X", ""))
            .unwrap();
        s.add_bookmark_list(vec![
            bookmark("https://x.com", "X-dupe", ""), // skipped
            bookmark("https://y.com", "Y", ""),
        ])
        .unwrap();
        assert_eq!(s.bookmark_count().unwrap(), 2);
        assert_eq!(
            s.find_bookmark_for_url("https://x.com".into())
                .unwrap()
                .unwrap()
                .title,
            "X"
        );
        s.delete_all_bookmarks().unwrap();
        assert_eq!(s.bookmark_count().unwrap(), 0);
    }

    // ---- history ----

    #[test]
    fn history_visit_upserts() {
        let s = store();
        s.visit_history_entry("https://a.com".into(), Some("A".into()))
            .unwrap();
        s.visit_history_entry("https://a.com".into(), Some("A2".into()))
            .unwrap();
        let all = s.last_hundred_history_entries().unwrap();
        assert_eq!(all.len(), 1);
        assert_eq!(all[0].title, "A2");
        assert!(all[0].last_time_visited > 0);
    }

    #[test]
    fn history_search_and_delete() {
        let s = store();
        for i in 0..8 {
            s.visit_history_entry(format!("https://site{i}.com"), Some(format!("Site {i}")))
                .unwrap();
        }
        s.visit_history_entry("https://other.org".into(), None).unwrap();

        // "site" matches 8 → limited to 5
        assert_eq!(s.find_history_entries_containing("site".into()).unwrap().len(), 5);
        // title-only match works too
        assert_eq!(s.find_history_entries_containing("Site 7".into()).unwrap()[0].url, "https://site7.com");

        s.delete_history_entry("https://other.org".into()).unwrap();
        assert_eq!(s.find_history_entries_containing("other".into()).unwrap().len(), 0);

        s.delete_history().unwrap();
        assert_eq!(s.last_hundred_history_entries().unwrap().len(), 0);
    }

    // ---- downloads ----

    #[test]
    fn downloads_roundtrip() {
        let s = store();
        let e = StoreDownloadEntry {
            url: "https://f.com/file.zip".into(),
            title: "file.zip".into(),
            content_size: "1.2 MB".into(),
        };
        assert!(s.add_download_if_not_exists(e.clone()).unwrap());
        assert!(!s.add_download_if_not_exists(e.clone()).unwrap());
        assert!(s.is_download("https://f.com/file.zip".into()).unwrap());
        assert_eq!(
            s.find_download_for_url("https://f.com/file.zip".into())
                .unwrap()
                .unwrap()
                .content_size,
            "1.2 MB"
        );
        assert_eq!(s.download_count().unwrap(), 1);
        assert!(s.delete_download("https://f.com/file.zip".into()).unwrap());
        assert!(!s.delete_download("https://f.com/file.zip".into()).unwrap());
    }

    #[test]
    fn downloads_list_newest_first() {
        let s = store();
        let mk = |u: &str| StoreDownloadEntry {
            url: u.into(),
            title: u.into(),
            content_size: "".into(),
        };
        s.add_downloads_list(vec![mk("a"), mk("b"), mk("a"), mk("c")])
            .unwrap();
        let urls: Vec<String> = s.all_downloads().unwrap().into_iter().map(|d| d.url).collect();
        assert_eq!(urls, vec!["c", "b", "a"]);
        s.delete_all_downloads().unwrap();
        assert_eq!(s.download_count().unwrap(), 0);
    }

    // ---- allow list ----

    #[test]
    fn allowlist_roundtrip() {
        let s = store();
        s.add_allowlist_item("a.com".into(), 1).unwrap();
        s.add_allowlist_item("b.com".into(), 2).unwrap();
        // newest first
        assert_eq!(
            s.all_allowlist_items().unwrap()[0].domain,
            "b.com"
        );
        assert_eq!(
            s.allowlist_item_for_url("a.com".into())
                .unwrap()
                .unwrap()
                .time_created,
            1
        );
        s.remove_allowlist_item("a.com".into()).unwrap();
        assert!(s.allowlist_item_for_url("a.com".into()).unwrap().is_none());
        s.clear_allowlist().unwrap();
        assert!(s.all_allowlist_items().unwrap().is_empty());
    }

    // ---- javascript ----

    #[test]
    fn javascript_roundtrip() {
        let s = store();
        let entry = StoreJavaScriptEntry {
            name: "script".into(),
            namespace: None,
            version: Some("1.0".into()),
            author: Some("me".into()),
            include: Some("*".into()),
            exclude: None,
            time: None,
            permissions: None,
            code: "console.log(1)".into(),
            requirements: Some("lib.js".into()),
            run_at: Some("document-end".into()),
            no_frames: false,
            connects: Some("api.example.com,*".into()),
        };
        assert!(s.add_javascript_if_not_exists(entry.clone()).unwrap());
        assert!(!s.add_javascript_if_not_exists(entry.clone()).unwrap());
        assert_eq!(s.javascript_count().unwrap(), 1);

        let found = s.find_javascript_entries_containing("scri".into()).unwrap();
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].author.as_deref(), Some("me"));
        assert_eq!(found[0].code, "console.log(1)");
        assert_eq!(found[0].exclude, None);
        assert_eq!(found[0].connects.as_deref(), Some("api.example.com,*"));

        s.delete_javascript_entry("script".into()).unwrap();
        assert_eq!(s.javascript_count().unwrap(), 0);
        s.add_javascript_if_not_exists(entry).unwrap();
        s.delete_all_javascript().unwrap();
        assert_eq!(s.javascript_count().unwrap(), 0);
    }

    #[test]
    fn gm_value_roundtrip() {
        let s = ArktosStore::in_memory();
        s.gm_set_value("script".into(), "k1".into(), "\"v1\"".into())
            .unwrap();
        s.gm_set_value("script".into(), "k2".into(), "42".into())
            .unwrap();
        s.gm_set_value("other".into(), "k1".into(), "\"x\"".into())
            .unwrap();

        let vals = s.gm_values_for_script("script".into()).unwrap();
        assert_eq!(vals.len(), 2);
        assert_eq!(vals["k1"], "\"v1\"");
        assert_eq!(s.gm_keys_for_script("script".into()).unwrap(),
                   vec!["k1".to_string(), "k2".to_string()]);

        // Overwrite keeps a single row.
        s.gm_set_value("script".into(), "k1".into(), "\"v2\"".into())
            .unwrap();
        assert_eq!(s.gm_values_for_script("script".into()).unwrap()["k1"],
                   "\"v2\"");

        s.gm_delete_value("script".into(), "k2".into()).unwrap();
        assert_eq!(s.gm_keys_for_script("script".into()).unwrap().len(), 1);

        // Scoping: the other script's row is untouched by deletions on
        // this script.
        s.gm_delete_script_values("script".into()).unwrap();
        assert!(s.gm_values_for_script("script".into()).unwrap().is_empty());
        assert_eq!(s.gm_values_for_script("other".into()).unwrap().len(), 1);
    }

    // ---- ad-block subscriptions (LIST01) ----

    fn sub(id: &str, enabled: bool, custom: bool) -> StoreAdblockSubscription {
        StoreAdblockSubscription {
            id: id.into(),
            name: format!("name-{id}"),
            url: id.into(),
            category: "Core & Privacy".into(),
            enabled,
            last_updated: 0,
            host_count: 0,
            custom,
        }
    }

    #[test]
    fn subscriptions_seed_toggle_and_stamp() {
        let s = store();
        assert!(s.all_adblock_subscriptions().unwrap().is_empty());

        assert!(s.add_adblock_subscription(sub("https://a.example/list.txt", true, false)).unwrap());
        assert!(s.add_adblock_subscription(sub("https://b.example/list.txt", false, true)).unwrap());
        // INSERT OR IGNORE — a second add of the same id is a no-op.
        assert!(!s.add_adblock_subscription(sub("https://a.example/list.txt", false, false)).unwrap());

        let all = s.all_adblock_subscriptions().unwrap();
        assert_eq!(all.len(), 2);
        assert!(all[0].enabled && !all[0].custom);
        assert!(!all[1].enabled && all[1].custom);

        s.set_adblock_subscription_enabled("https://b.example/list.txt".into(), true).unwrap();
        s.set_adblock_subscription_state("https://b.example/list.txt".into(), 1234, 42).unwrap();

        let all = s.all_adblock_subscriptions().unwrap();
        assert!(all[1].enabled);
        assert_eq!(all[1].last_updated, 1234);
        assert_eq!(all[1].host_count, 42);
        // Untouched row keeps its values.
        assert!(all[0].enabled);
        assert_eq!(all[0].last_updated, 0);

        s.update_adblock_subscription("https://b.example/list.txt".into(), "renamed".into(), "file:///x".into()).unwrap();
        let all = s.all_adblock_subscriptions().unwrap();
        assert_eq!(all[1].name, "renamed");
        assert_eq!(all[1].url, "file:///x");

        s.delete_adblock_subscription("https://a.example/list.txt".into()).unwrap();
        let all = s.all_adblock_subscriptions().unwrap();
        assert_eq!(all.len(), 1);
        assert_eq!(all[0].id, "https://b.example/list.txt");
    }
}
