package com.arktosbrowser.mobile.bookmark

import com.arktosbrowser.mobile.constant.UTF8
import com.arktosbrowser.mobile.database.Bookmark
import com.arktosbrowser.mobile.rust.ArktosCore
import java.io.InputStream
import javax.inject.Inject

/**
 * An importer that supports the Netscape Bookmark File Format.
 *
 * See https://msdn.microsoft.com/en-us/ie/aa753582(v=vs.94)
 *
 * The parsing moved to the Rust core in STORE01 — this is a thin
 * stream→string adapter over `parseNetscapeBookmarks`, keeping the
 * [BookmarkImporter] contract for the settings screen.
 */
class NetscapeBookmarkFormatImporter @Inject constructor() : BookmarkImporter {

    override fun importBookmarks(inputStream: InputStream): List<Bookmark.Entry> {
        val html = inputStream.bufferedReader(charset(UTF8)).readText()
        return ArktosCore.importNetscapeBookmarks(html).map { it.toBookmarkEntry() }
    }
}
