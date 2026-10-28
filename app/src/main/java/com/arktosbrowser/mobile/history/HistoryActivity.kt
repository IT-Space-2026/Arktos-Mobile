// SPDX-License-Identifier: MPL-2.0
// Copyright 2020 CookieJarApps and Arktos contributors

package com.arktosbrowser.mobile.history

import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.Filter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import com.arktosbrowser.mobile.AppTheme
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.database.HistoryEntry
import com.arktosbrowser.mobile.database.history.HistoryRepository
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.dialog.LightningDialogBuilder
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.utils.RecyclerItemClickListener
import com.arktosbrowser.mobile.utils.ThemeUtils
import java.text.DateFormat
import java.util.*
import javax.inject.Inject


class HistoryActivity : AppCompatActivity(), SearchView.OnQueryTextListener {
    @JvmField
    @Inject
    var mUserPreferences: UserPreferences? = null

    @JvmField
    @Inject
    var dialogBuilder: LightningDialogBuilder? = null

    lateinit var list: RecyclerView
    lateinit var arrayAdapter: CustomAdapter
    lateinit var historyList: List<HistoryEntry>


    @Inject internal lateinit var historyRepository: HistoryRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        injector.inject(this)

        val color: Int
        when (mUserPreferences!!.resolvedTheme(this)) {
            AppTheme.LIGHT -> setTheme(R.style.Theme_SettingsTheme)
            AppTheme.DARK -> setTheme(R.style.Theme_SettingsTheme_Dark)
            AppTheme.ARKTOS_LIGHT -> setTheme(R.style.Theme_SettingsTheme_Arktos)
            AppTheme.ARKTOS_DARK -> setTheme(R.style.Theme_SettingsTheme_Arktos_Dark)
            else -> setTheme(R.style.Theme_SettingsTheme_Black)
        }
        color = ThemeUtils.getColorBackground(this)
        window.setBackgroundDrawable(ColorDrawable(color))

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        if (supportActionBar != null) supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        // API-35 edge-to-edge slides the toolbar under the
        // transparent status bar — pad its parent like the other
        // screens; padding inside the toolbar clips the title.
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            val padTarget = (toolbar.parent as? android.view.View) ?: toolbar
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(padTarget) { v, insets ->
                val bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars()
                )
                v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
                insets
            }
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.history)
        ) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            )
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom)
            insets
        }

        list = findViewById(R.id.history)
        val linearLayoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        historyRepository
                .lastHundredVisitedHistoryEntries()
                .subscribe { list ->
                    historyList = list
                }

        arrayAdapter = CustomAdapter(this, historyList)
        list.layoutManager = linearLayoutManager
        list.adapter = arrayAdapter

        list.addOnItemTouchListener(
                RecyclerItemClickListener(this, list, object : RecyclerItemClickListener.OnItemClickListener {
                    override fun onItemClick(view: View?, position: Int) {
                        val entry = (list.adapter as CustomAdapter).getItem(position)
                            ?: return // header row
                        val i = Intent(ACTION_VIEW)
                        i.data = Uri.parse(entry.url)
                        i.setPackage(this@HistoryActivity.packageName)
                        startActivity(i, null)
                    }

                    override fun onLongItemClick(view: View?, position: Int) {
                        val entry = (list.adapter as CustomAdapter).getItem(position)
                            ?: return // header row
                        dialogBuilder!!.showLongPressedHistoryLinkDialog(this@HistoryActivity, entry.url)
                    }
                })
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            DELETE_ALL_ID -> { deleteAll(); return true }
            android.R.id.home -> { finish(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    /** Per-row ✕ — Iceraven's row-level delete. */
    fun deleteEntry(entry: HistoryEntry) {
        historyRepository.deleteHistoryEntry(entry.url)
            .subscribe { dataChanged() }
    }

    /** Iceraven's time-range delete dialog. */
    private fun deleteAll() {
        val ranges = arrayOf(
            getString(R.string.history_last_hour),
            getString(R.string.history_today_yesterday),
            getString(R.string.history_everything)
        )
        var chosen = 0
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_time_range_title)
            .setMessage(R.string.history_time_range_msg)
            .setSingleChoiceItems(ranges, 0) { _, which -> chosen = which }
            .setPositiveButton(R.string.downloads_delete) { _, _ -> deleteRange(chosen) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 0 = last hour, 1 = today + yesterday, 2 = everything. */
    private fun deleteRange(which: Int) {
        if (which == 2) {
            historyRepository.deleteHistory().subscribe { dataChanged() }
            return
        }
        val cutoff = System.currentTimeMillis() - when (which) {
            0 -> 3600_000L
            else -> 48 * 3600_000L
        }
        val doomed = historyList.filter { it.lastTimeVisited >= cutoff }
        var pending = doomed.size
        if (pending == 0) { dataChanged(); return }
        doomed.forEach { e ->
            historyRepository.deleteHistoryEntry(e.url).subscribe {
                if (--pending == 0) dataChanged()
            }
        }
    }

    fun dataChanged() {
        historyRepository
                .lastHundredVisitedHistoryEntries()
                .subscribe { list ->
                    historyList = list
                }
        arrayAdapter = CustomAdapter(this, historyList)
        list?.adapter = arrayAdapter
        arrayAdapter.notifyDataSetChanged()
    }

    /** Flattened adapter items — a date header per bucket + entries. */
    sealed interface Item {
        data class Header(val title: String) : Item
        data class Entry(val entry: HistoryEntry) : Item
        /** Iceraven's "Recently closed tabs / N tabs" section header. */
        data class RecentlyClosed(val count: Int) : Item
        data class ClosedEntry(val closed: RecentlyClosedTabs.Closed) : Item
    }

    class CustomAdapter(
            private val context: android.content.Context,
            private var dataSet: List<HistoryEntry>) :
            RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<Item> = grouped(dataSet)
        var filtered: List<HistoryEntry> = dataSet
        var oldList: MutableList<HistoryEntry> = dataSet.toMutableList()

        /** Iceraven-style per-day date headers, newest first. */
        private fun grouped(entries: List<HistoryEntry>): List<Item> {
            val fmt = java.text.SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
            val out = mutableListOf<Item>()
            // Iceraven's "Recently closed tabs" section on top.
            val closed = RecentlyClosedTabs.all(context)
            if (closed.isNotEmpty()) {
                out += Item.RecentlyClosed(closed.size)
                closed.forEach { out += Item.ClosedEntry(it) }
            }
            var current: String? = null
            for (e in entries) {
                val label = fmt.format(Date(e.lastTimeVisited))
                if (label != current) {
                    current = label
                    out += Item.Header(label)
                }
                out += Item.Entry(e)
            }
            return out
        }

        fun getFilter(): Filter? {
            return object : Filter() {
                override fun performFiltering(charSequence: CharSequence): FilterResults? {
                    val charString = charSequence.toString()
                    filtered = if (charString.isEmpty()) {
                        oldList
                    } else {
                        val filteredList: MutableList<HistoryEntry> = ArrayList()
                        for (row in oldList) {
                            if (row.title.lowercase(Locale.getDefault()).contains(charString.lowercase(Locale.getDefault()))) {
                                filteredList.add(row)
                            }
                            else if(row.url.lowercase(Locale.getDefault()).contains(charString.lowercase(Locale.getDefault()))){
                                filteredList.add(row)
                            }
                        }
                        filteredList
                    }
                    val filterResults = FilterResults()
                    filterResults.values = filtered
                    return filterResults
                }

                @Suppress("UNCHECKED_CAST")
                override fun publishResults(charSequence: CharSequence?, filterResults: FilterResults) {
                    dataSet = filterResults.values as MutableList<HistoryEntry>
                    items = grouped(dataSet)
                    notifyDataSetChanged()
                }
            }
        }

        /** Null for header rows — callers must skip clicks on them. */
        fun getItem(position: Int): HistoryEntry? = when (val i = items[position]) {
            is Item.Entry -> i.entry
            is Item.ClosedEntry -> HistoryEntry(i.closed.title, i.closed.url, i.closed.time)
            else -> null
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: TextView = view.findViewById(R.id.historyIcon)
            val textView: TextView = view.findViewById(R.id.historyTitle)
            val historyUrl: TextView = view.findViewById(R.id.historyUrl)
            val historyDate: TextView = view.findViewById(R.id.historyTime)
            val delete: android.widget.ImageButton = view.findViewById(R.id.historyDelete)
        }

        class HeaderVH(view: View) : RecyclerView.ViewHolder(view)

        override fun getItemViewType(position: Int) = when (items[position]) {
            is Item.Header -> 1
            is Item.RecentlyClosed -> 2
            else -> 0
        }

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int) =
            when (viewType) {
                1 -> HeaderVH(
                    TextView(context).apply {
                        val pad = (resources.displayMetrics.density * 14).toInt()
                        setPadding(pad, pad, pad, pad / 2)
                        textSize = 14f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                )
                2 -> HeaderVH(
                    TextView(context).apply {
                        val pad = (resources.displayMetrics.density * 14).toInt()
                        setPadding(pad, pad, pad, pad / 2)
                        textSize = 15f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                )
                else -> {
                    oldList = dataSet.toMutableList()
                    ViewHolder(LayoutInflater.from(viewGroup.context)
                        .inflate(R.layout.history_row, viewGroup, false))
                }
            }

        private fun bindTile(holder: ViewHolder, host: String) {
            holder.icon.text = host.take(1).uppercase()
            val hue = (host.hashCode() and 0x7FFFFFFF) % 360
            val color = android.graphics.Color.HSVToColor(
                floatArrayOf(hue.toFloat(), 0.55f, 0.55f)
            )
            holder.icon.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = holder.icon.resources.displayMetrics.density * 8
                setColor(color)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is HeaderVH) {
                (holder.itemView as TextView).text = when (item) {
                    is Item.Header -> item.title
                    is Item.RecentlyClosed -> context.getString(
                        R.string.history_recently_closed_tabs, item.count
                    )
                    else -> ""
                }
                return
            }
            holder as ViewHolder
            when (item) {
                is Item.Entry -> {
                    val entry = item.entry
                    bindRow(holder, entry.title, entry.url, entry.lastTimeVisited)
                    holder.delete.visibility = android.view.View.VISIBLE
                    holder.delete.setOnClickListener {
                        (context as? HistoryActivity)?.deleteEntry(entry)
                    }
                }
                is Item.ClosedEntry -> {
                    val c = item.closed
                    bindRow(holder, c.title, c.url, c.time)
                    // Reopenable rows — no delete like Iceraven's
                    // recently-closed section.
                    holder.delete.visibility = android.view.View.GONE
                }
                else -> Unit
            }
        }

        private fun bindRow(holder: ViewHolder, title: String, url: String, time: Long) {
            holder.textView.text = title
            val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(url)
            holder.historyUrl.text = url
            holder.historyDate.text = getTime(time)
            bindTile(holder, host)
        }

        override fun getItemCount() = items.size

        private fun getTime(s: Long): String? {
            try {
                return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s))
            } catch (e: Exception) {
                return e.toString()
            }
        }

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.history, menu)

        val searchItem: MenuItem = menu.findItem(R.id.action_search)
        val searchView: SearchView = searchItem.getActionView() as SearchView
        searchView.setOnQueryTextListener(this)

        // Iceraven's trash icon — clear all history.
        menu.add(0, DELETE_ALL_ID, 0, R.string.downloads_clear_all)
            .setIcon(android.R.drawable.ic_menu_delete)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

        return super.onCreateOptionsMenu(menu)
    }

    private companion object {
        const val DELETE_ALL_ID = 42
    }

    override fun onQueryTextChange(query: String?): Boolean {
        arrayAdapter.getFilter()?.filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }

}