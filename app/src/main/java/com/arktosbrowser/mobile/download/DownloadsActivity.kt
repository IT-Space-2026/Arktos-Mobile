// SPDX-License-Identifier: MPL-2.0
package com.arktosbrowser.mobile.download

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.di.injector
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import java.net.URLConnection

/**
 * Native downloads screen — queries the system DownloadManager
 * directly, so it lists real downloads even when the app's store
 * read is unavailable. Tap a row to open the file (installer for
 * APKs); Retry re-enqueues the source URL; the toolbar action clears
 * the whole list.
 */
class DownloadsActivity : AppCompatActivity() {

    private data class Row(
        val id: Long, val title: String, val uri: String,
        val localUri: String?, val status: Int, val bytes: Long, val total: Long
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
        toolbar.menu.add(0, CLEAR_ITEM_ID, 0, R.string.downloads_clear_all)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == CLEAR_ITEM_ID) { clearAll(); true } else false
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
                            total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        )
                    }
                }
            }
        }
        rows = list.reversed().toMutableList() // newest first
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

    private fun open(row: Row) {
        val local = row.localUri ?: return
        val file = File(Uri.parse(local).path ?: return)
        if (!file.exists()) {
            // File gone (or a retry moved it) — fall back to retry.
            retry(row); return
        }
        val mime = URLConnection.guessContentTypeFromName(file.name) ?: "*/*"
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.fileprovider", file
        )
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
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
            URLConnection.guessContentTypeFromName(row.title)?.let(req::setMimeType)
            dm.enqueue(req)
            dm.remove(row.id)
            reload()
        }
    }

    private companion object {
        const val CLEAR_ITEM_ID = 1
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.dl_icon)
            val progress: ProgressBar = v.findViewById(R.id.dl_progress)
            val name: TextView = v.findViewById(R.id.dl_name)
            val speed: TextView = v.findViewById(R.id.dl_speed)
            val download: TextView = v.findViewById(R.id.dl_download)
            val status: TextView = v.findViewById(R.id.dl_status)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(
            layoutInflater.inflate(R.layout.download_item, p, false)
        )

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = rows[pos]
            h.name.text = r.title.ifBlank { r.uri.substringAfterLast('/') }
            h.icon.setImageResource(
                if (r.status == DownloadManager.STATUS_SUCCESSFUL)
                    R.drawable.ic_file_download_black else android.R.drawable.stat_sys_download
            )
            h.speed.text = r.uri.substringAfterLast('.').take(4).uppercase()
            h.download.text = if (r.total > 0)
                "${r.bytes / 1024} / ${r.total / 1024} KB" else "${r.bytes / 1024} KB"
            h.status.text = when (r.status) {
                DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.downloads_open)
                DownloadManager.STATUS_RUNNING -> getString(R.string.download_pending)
                else -> getString(R.string.download_pending)
            }
            h.progress.visibility =
                if (r.status == DownloadManager.STATUS_RUNNING) View.VISIBLE else View.GONE
            if (r.total > 0) h.progress.progress = (r.bytes * 100 / r.total).toInt()
            h.itemView.setOnClickListener {
                if (r.status == DownloadManager.STATUS_SUCCESSFUL) open(r) else retry(r)
            }
            h.itemView.setOnLongClickListener { retry(r); true }
        }
    }
}
