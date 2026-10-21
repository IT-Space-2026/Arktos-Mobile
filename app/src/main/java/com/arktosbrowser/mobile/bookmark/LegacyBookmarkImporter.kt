// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.bookmark

import com.arktosbrowser.mobile.constant.UTF8
import com.arktosbrowser.mobile.database.Bookmark
import com.arktosbrowser.mobile.rust.ArktosCore
import java.io.InputStream
import javax.inject.Inject

/**
 * A [BookmarkImporter] that imports bookmark files that were produced by
 * `BookmarkExporter` — the JSON-per-line legacy format.
 *
 * The parsing moved to the Rust core in PORT01 (strict mode: the first
 * malformed line fails the whole import, like upstream's propagating
 * `JSONException`) — this is a thin stream→string adapter keeping the
 * [BookmarkImporter] contract for the settings screen.
 */
class LegacyBookmarkImporter @Inject constructor() : BookmarkImporter {

    override fun importBookmarks(inputStream: InputStream): List<Bookmark.Entry> {
        val text = inputStream.bufferedReader(charset(UTF8)).readText()
        val entries = ArktosCore.importLegacyBookmarks(text)
            ?: throw IllegalArgumentException("Not a valid legacy bookmark file")
        return entries.map { it.toBookmarkEntry() }
    }

}
