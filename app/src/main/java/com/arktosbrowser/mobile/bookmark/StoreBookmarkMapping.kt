package com.arktosbrowser.mobile.bookmark

import com.arktosbrowser.mobile.database.Bookmark
import com.arktosbrowser.mobile.database.asFolder
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.uniffi.StoreBookmark

/**
 * Shared `StoreBookmark` → [Bookmark.Entry] mapping for the Rust-side
 * importers (PORT01). Lives top-level so `BookmarkExporter` (Java) can
 * reach it without dealing with `UInt`'s mangled accessors.
 */
fun StoreBookmark.toBookmarkEntry(): Bookmark.Entry = Bookmark.Entry(
    url = url,
    title = title,
    position = position.toInt(),
    folder = folder.asFolder()
)

/**
 * The reverse mapping for the exporter (PORT02): `createdAt` isn't
 * written by the legacy format, so it stays 0.
 */
fun Bookmark.Entry.toStoreBookmark(): StoreBookmark = StoreBookmark(
    url = url,
    title = title,
    folder = folder.title,
    position = position.toUInt(),
    createdAt = 0uL
)

/**
 * The legacy JSON-lines export payload for [entries] — produced by
 * the Rust core. Top-level so `BookmarkExporter` (Java) reaches it as
 * `StoreBookmarkMappingKt.legacyExportJson`.
 */
fun legacyExportJson(entries: List<Bookmark.Entry>): String =
    ArktosCore.exportLegacyBookmarks(entries.map { it.toStoreBookmark() })
