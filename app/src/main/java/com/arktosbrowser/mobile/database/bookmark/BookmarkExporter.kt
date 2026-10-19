package com.arktosbrowser.mobile.database.bookmark

import android.content.Context
import android.util.Log
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.bookmark.toBookmarkEntry
import com.arktosbrowser.mobile.bookmark.legacyExportJson
import com.arktosbrowser.mobile.database.Bookmark
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.utils.Utils
import io.reactivex.Completable
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.ArrayList

object BookmarkExporter {

    private const val TAG = "BookmarkExporter"

    /**
     * Retrieves all the default bookmarks stored
     * in the raw file within assets.
     *
     * @param context the context necessary to open assets.
     * @return a non null list of the bookmarks stored in assets.
     */
    fun importBookmarksFromAssets(context: Context): List<Bookmark.Entry> {
        var inputStream: InputStream? = null
        try {
            inputStream = context.resources.openRawResource(R.raw.default_bookmarks)
            val text = BufferedReader(InputStreamReader(inputStream)).readText()
            // PORT01 — lenient JSON-lines parse lives in the Rust core
            // (bad lines are skipped, like the per-line JSONException
            // catch did upstream).
            val bookmarks = ArrayList<Bookmark.Entry>()
            for (row in ArktosCore.parseDefaultBookmarks(text)) {
                bookmarks.add(row.toBookmarkEntry())
            }
            return bookmarks
        } catch (e: RuntimeException) {
            Log.e(TAG, "Error reading the bookmarks file", e)
            return ArrayList()
        } finally {
            Utils.close(inputStream)
        }
    }

    /**
     * Exports the list of bookmarks to a file.
     *
     * @param bookmarkList the bookmarks to export.
     * @param aStream the stream to export to.
     * @return an observable that emits a completion
     * event when the export is complete, or an error
     * event if there is a problem.
     */
    fun exportBookmarksToFile(
        bookmarkList: List<Bookmark.Entry>,
        aStream: OutputStream
    ): Completable = Completable.fromAction {
        var bookmarkWriter: BufferedWriter? = null
        try {
            bookmarkWriter = BufferedWriter(OutputStreamWriter(aStream))

            // PORT02 — the JSON-lines payload (field order and
            // org.json escaping) is produced by the Rust core.
            bookmarkWriter.write(legacyExportJson(bookmarkList))
        } finally {
            Utils.close(bookmarkWriter)
        }
    }
}
