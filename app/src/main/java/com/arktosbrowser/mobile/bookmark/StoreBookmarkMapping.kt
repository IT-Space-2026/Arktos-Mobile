package com.arktosbrowser.mobile.bookmark

import com.arktosbrowser.mobile.database.Bookmark
import com.arktosbrowser.mobile.database.asFolder
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
