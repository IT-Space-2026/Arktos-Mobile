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

    private fun delete(row: Row) {
        runCatching { getSystemService(DownloadManager::class.java)?.remove(row.id) }
        fileOf(row)?.delete()
        Completable.fromAction {
            injector.downloadsRepository().deleteDownload(row.uri).blockingGet()
        }.subscribeOn(Schedulers.io()).subscribe { }
        reload()
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
                    3 -> shareUrl(row); 4 -> rename(row); 5 -> delete(row)
                }
                true
            }
            show()
        }
    }

    private companion object {
        const val CLEAR_ITEM_ID = 1
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.dl_icon)
            val name: TextView = v.findViewById(R.id.dl_name)
            val subtitle: TextView = v.findViewById(R.id.dl_subtitle)
            val menu: ImageButton = v.findViewById(R.id.dl_menu)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(
            layoutInflater.inflate(R.layout.download_item, p, false)
        )

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = rows[pos]
            h.name.text = r.title.ifBlank {
                r.uri.substringBefore('?').substringAfterLast('/')
            }
            h.icon.setImageResource(
                if (r.status == DownloadManager.STATUS_SUCCESSFUL)
                    R.drawable.ic_file_download_black else android.R.drawable.stat_sys_download
            )
            // "103 MB • release-assets.github.com" — Iceraven-style
            // subtitle: size + source host.
            val size = (if (r.total > 0) r.total else r.bytes) / (1024f * 1024f)
            val host = runCatching { Uri.parse(r.uri).host.orEmpty() }.getOrDefault("")
            h.subtitle.text = "%.1f MB • %s".format(size, host)
            h.itemView.setOnClickListener {
                if (r.status == DownloadManager.STATUS_SUCCESSFUL) open(r) else retry(r)
            }
            h.menu.setOnClickListener { showRowMenu(it, r) }
            h.itemView.setOnLongClickListener { showRowMenu(it, r); true }
        }
    }
}
