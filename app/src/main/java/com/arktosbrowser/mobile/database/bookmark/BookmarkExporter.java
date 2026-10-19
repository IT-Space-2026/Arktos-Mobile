package com.arktosbrowser.mobile.database.bookmark;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import com.arktosbrowser.mobile.R;
import com.arktosbrowser.mobile.bookmark.StoreBookmarkMappingKt;
import com.arktosbrowser.mobile.database.Bookmark;
import com.arktosbrowser.mobile.rust.ArktosCore;
import com.arktosbrowser.mobile.uniffi.StoreBookmark;
import com.arktosbrowser.mobile.utils.Preconditions;
import com.arktosbrowser.mobile.utils.Utils;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import io.reactivex.Completable;

public final class BookmarkExporter {

    private static final String TAG = "BookmarkExporter";

    private BookmarkExporter() {}

    /**
     * Retrieves all the default bookmarks stored
     * in the raw file within assets.
     *
     * @param context the context necessary to open assets.
     * @return a non null list of the bookmarks stored in assets.
     */
    @NonNull
    public static List<Bookmark.Entry> importBookmarksFromAssets(@NonNull Context context) {
        InputStream inputStream = null;
        try {
            inputStream = context.getResources().openRawResource(R.raw.default_bookmarks);
            //noinspection IOResourceOpenedButNotSafelyClosed
            final String text = new BufferedReader(new InputStreamReader(inputStream))
                    .lines().collect(java.util.stream.Collectors.joining("\n"));
            // PORT01 — lenient JSON-lines parse lives in the Rust core
            // (bad lines are skipped, like the per-line JSONException
            // catch did upstream).
            List<Bookmark.Entry> bookmarks = new ArrayList<>();
            for (StoreBookmark row : ArktosCore.INSTANCE.parseDefaultBookmarks(text)) {
                bookmarks.add(StoreBookmarkMappingKt.toBookmarkEntry(row));
            }
            return bookmarks;
        } catch (RuntimeException e) {
            Log.e(TAG, "Error reading the bookmarks file", e);
            return new ArrayList<>();
        } finally {
            Utils.close(inputStream);
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
    @NonNull
    public static Completable exportBookmarksToFile(@NonNull final List<Bookmark.Entry> bookmarkList,
                                                    @NonNull final OutputStream aStream) {
        return Completable.fromAction(() -> {
            Preconditions.checkNonNull(bookmarkList);
            BufferedWriter bookmarkWriter = null;
            try {
                //noinspection IOResourceOpenedButNotSafelyClosed
                bookmarkWriter = new BufferedWriter(new OutputStreamWriter(aStream));

                // PORT02 — the JSON-lines payload (field order and
                // org.json escaping) is produced by the Rust core.
                bookmarkWriter.write(StoreBookmarkMappingKt.legacyExportJson(bookmarkList));
            } finally {
                Utils.close(bookmarkWriter);
            }
        });
    }

    /**
     * A blocking call that creates a new export file with
     * the name "BookmarkExport.txt" and an appropriate
     * numerical appendage if a file already exists with
     * that name.
     *
     * @return a non null empty file that can be used
     * to export bookmarks to.
     */
    @WorkerThread
    @NonNull
    public static File createNewExportFile() {
        File bookmarksExport = new File(
                Environment.getExternalStorageDirectory(),
                "BookmarksExport.txt");
        int counter = 0;
        while (bookmarksExport.exists()) {
            counter++;
            bookmarksExport = new File(
                    Environment.getExternalStorageDirectory(),
                    "BookmarksExport-" + counter + ".txt");
        }

        return bookmarksExport;
    }

}