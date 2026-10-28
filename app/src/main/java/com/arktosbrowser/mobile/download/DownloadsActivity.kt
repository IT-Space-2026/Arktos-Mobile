// SPDX-License-Identifier: MPL-2.0
package com.arktosbrowser.mobile.download

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.di.injector
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import java.net.URLConnection

/**
 * Native downloads screen — queries the system DownloadManager
 * directly, so it lists real downloads even when the app's store
 * read is unavailable. Rows show icon, full file name and a
 * size + source-domain subtitle (Iceraven-style); the row overflow
 * menu offers Open / Retry / Share file / Share URL / Rename /
 * Delete; the toolbar action clears the whole list.
 */
class DownloadsActivity : AppCompatActivity() {

    private data class Row(
        val id: Long, val title: String, val uri: String,
        val localUri: String?, val status: Int, val bytes: Long, val total: Long,
        val lastModified: Long
    )

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private var rows = mutableListOf<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download)
        val toolbar =
            findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.action_downloads)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }
        // Gear -> Iceraven-style Download Settings; Clear stays in the overflow.
        toolbar.menu.add(0, SETTINGS_ITEM_ID, 0, R.string.download_settings_title)
            .setIcon(android.R.drawable.ic_menu_preferences)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.menu.add(0, CLEAR_ITEM_ID, 1, R.string.downloads_clear_all)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                SETTINGS_ITEM_ID -> {
                    startActivity(
                        Intent(this, DownloadSettingsActivity::class.java)
                    ); true
                }
                CLEAR_ITEM_ID -> { clearAll(); true }
                else -> false
            }
        }

        emptyView = TextView(this).apply {
            text = getString(R.string.downloads_empty)
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        (findViewById<ViewGroup>(android.R.id.content) as? ViewGroup)?.addView(emptyView)
        recycler = findViewById(R.id.downloads)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.clipToPadding = false
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(recycler) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            )
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom)
            insets
        }
        reload()
    }

    private fun reload() {
        val dm = getSystemService(DownloadManager::class.java) ?: return
        val list = mutableListOf<Row>()
        runCatching {
            dm.query(DownloadManager.Query()).use { c ->
                if (c != null) {
                    while (c.moveToNext()) {
                        list += Row(
                            id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                            title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "",
                            uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) ?: "",
                            localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
                            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                            total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                            lastModified = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP))
                        )
                    }
                }
            }
        }
        rows = list.reversed().toMutableList() // newest first
        items = grouped()
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        recycler.adapter = Adapter()
    }

    private fun clearAll() {
        val dm = getSystemService(DownloadManager::class.java)
        rows.forEach { runCatching { dm?.remove(it.id) } }
        Completable.fromAction {
            injector.downloadsRepository().deleteAllDownloads().blockingAwait()
        }.subscribeOn(Schedulers.io()).subscribe { }
        reload()
    }

    private fun fileOf(row: Row): File? =
        row.localUri?.let { Uri.parse(it).path }?.let(::File)?.takeIf { it.exists() }

    private fun mimeOf(name: String) =
        URLConnection.guessContentTypeFromName(name) ?: "*/*"

    private fun open(row: Row) {
        val file = fileOf(row) ?: run { retry(row); return }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    private fun retry(row: Row) {
        val dm = getSystemService(DownloadManager::class.java) ?: return
        runCatching {
            val req = DownloadManager.Request(Uri.parse(row.uri))
                .setTitle(row.title)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, row.title
                )
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            mimeOf(row.title).takeIf { it != "*/*" }?.let(req::setMimeType)
            dm.enqueue(req)
            dm.remove(row.id)
            reload()
        }
    }

    private fun shareFile(row: Row) {
        val file = fileOf(row) ?: return
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeOf(file.name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, null
            )
        )
    }

    private fun shareUrl(row: Row) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, row.uri)
                }, null
            )
        )
    }

    private fun rename(row: Row) {
        val file = fileOf(row) ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(file.name)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.downloads_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != file.name) {
                    runCatching { file.renameTo(File(file.parent, name)) }
                }
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun delete(row: Row, removeFile: Boolean = true) {
        runCatching { getSystemService(DownloadManager::class.java)?.remove(row.id) }
        if (removeFile) fileOf(row)?.delete()
        Completable.fromAction {
            injector.downloadsRepository().deleteDownload(row.uri).blockingGet()
        }.subscribeOn(Schedulers.io()).subscribe { }
        reload()
    }

    /** Honors the Download-Settings delete policy before deleting. */
    private fun deleteWithPolicy(row: Row) {
        when (DownloadSettings.deletePolicy(this)) {
            DownloadSettings.POLICY_DEVICE -> delete(row, removeFile = true)
            DownloadSettings.POLICY_HISTORY -> delete(row, removeFile = false)
            else -> MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.download_settings_delete_confirm, row.title))
                .setMessage(R.string.download_settings_delete_confirm_msg)
                .setPositiveButton(R.string.download_settings_delete_device) { _, _ ->
                    delete(row, removeFile = true)
                }
                .setNegativeButton(R.string.download_settings_delete_history) { _, _ ->
                    delete(row, removeFile = false)
                }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showRowMenu(anchor: View, row: Row) {
        val ok = row.status == DownloadManager.STATUS_SUCCESSFUL
        PopupMenu(this, anchor).apply {
            if (ok) menu.add(0, 0, 0, R.string.downloads_open)
            menu.add(0, 1, 1, R.string.downloads_retry)
            if (ok) menu.add(0, 2, 2, R.string.downloads_share_file)
            menu.add(0, 3, 3, R.string.downloads_share_url)
            if (ok) menu.add(0, 4, 4, R.string.downloads_rename)
            menu.add(0, 5, 5, R.string.downloads_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> open(row); 1 -> retry(row); 2 -> shareFile(row)
                    3 -> shareUrl(row); 4 -> rename(row); 5 -> deleteWithPolicy(row)
                }
                true
            }
            show()
        }
    }

    private companion object {
        const val CLEAR_ITEM_ID = 1
        const val SETTINGS_ITEM_ID = 2
    }

    /** Flattened adapter items: a header per date bucket + rows. */
    private sealed interface Item {
        data class Header(val title: String) : Item
        data class Entry(val row: Row) : Item
    }

    /** Iceraven date buckets — rows are already newest-first. */
    private fun grouped(): List<Item> {
        val day = 24L * 3600 * 1000
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val buckets = listOf(
            getString(R.string.downloads_group_today) to todayStart,
            getString(R.string.downloads_group_yesterday) to todayStart - day,
            getString(R.string.downloads_group_last7) to todayStart - 7 * day,
            getString(R.string.downloads_group_last30) to todayStart - 30 * day
        )
        val out = mutableListOf<Item>()
        var current: String? = null
        for (r in rows) {
            val label = (buckets.firstOrNull { r.lastModified >= it.second }
                ?: (getString(R.string.downloads_group_older) to 0L)).first
            if (label != current) {
                current = label
                out += Item.Header(label)
            }
            out += Item.Entry(r)
        }
        return out
    }

    private var items: List<Item> = emptyList()

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.dl_icon)
            val name: TextView = v.findViewById(R.id.dl_name)
            val subtitle: TextView = v.findViewById(R.id.dl_subtitle)
            val menu: ImageButton = v.findViewById(R.id.dl_menu)
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v)

        override fun getItemViewType(pos: Int) =
            if (items[pos] is Item.Header) 1 else 0

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            if (t == 1) HeaderVH(
                TextView(this@DownloadsActivity).apply {
                    val pad = (resources.displayMetrics.density * 14).toInt()
                    setPadding(pad, pad, pad, pad / 2)
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            ) else VH(
                layoutInflater.inflate(R.layout.download_item, p, false)
            )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val item = items[pos]
            if (h is HeaderVH) {
                (h.itemView as TextView).text = (item as Item.Header).title
                return
            }
            val r = (item as Item.Entry).row
            h as VH
            h.name.text = r.title.ifBlank {
                r.uri.substringBefore('?').substringAfterLast('/')
            }
            h.icon.setImageResource(
                if (r.status == DownloadManager.STATUS_SUCCESSFUL)
                    R.drawable.ic_file_download_black else android.R.drawable.stat_sys_download
            )
            // "103 MB • release-assets.github.com • 09:38" —
            // Iceraven-style subtitle: size + source + time.
            val size = (if (r.total > 0) r.total else r.bytes) / (1024f * 1024f)
            val host = runCatching { Uri.parse(r.uri).host.orEmpty() }.getOrDefault("")
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(r.lastModified))
            h.subtitle.text = "%.1f MB • %s • %s".format(size, host, time)
            h.itemView.setOnClickListener {
                if (r.status == DownloadManager.STATUS_SUCCESSFUL) open(r) else retry(r)
            }
            h.menu.setOnClickListener { showRowMenu(it, r) }
            h.itemView.setOnLongClickListener { showRowMenu(it, r); true }
        }
    }
}
