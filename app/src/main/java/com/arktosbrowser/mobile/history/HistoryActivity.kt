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

        list = findViewById(R.id.history)
        val linearLayoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        historyRepository
                .lastHundredVisitedHistoryEntries()
                .subscribe { list ->
                    historyList = list
                }

        arrayAdapter = CustomAdapter(historyList)
        list.layoutManager = linearLayoutManager
        list.adapter = arrayAdapter

        list.addOnItemTouchListener(
                RecyclerItemClickListener(this, list, object : RecyclerItemClickListener.OnItemClickListener {
                    override fun onItemClick(view: View?, position: Int) {
                        val i = Intent(ACTION_VIEW)
                        i.data = Uri.parse((list.adapter as CustomAdapter).getItem(position).url)
                        i.setPackage(this@HistoryActivity.packageName)
                        startActivity(i, null)
                    }

                    override fun onLongItemClick(view: View?, position: Int) {
                        dialogBuilder!!.showLongPressedHistoryLinkDialog(this@HistoryActivity, historyList[position].url)
                    }
                })
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            else -> finish()
        }
        return super.onOptionsItemSelected(item)
    }

    fun dataChanged() {
        historyRepository
                .lastHundredVisitedHistoryEntries()
                .subscribe { list ->
                    historyList = list
                }
        arrayAdapter = CustomAdapter(historyList)
        list?.adapter = arrayAdapter
        arrayAdapter.notifyDataSetChanged()
    }

    class CustomAdapter(private var dataSet: List<HistoryEntry>) :
            RecyclerView.Adapter<CustomAdapter.ViewHolder>() {

        lateinit var filtered: MutableList<HistoryEntry>
        lateinit var oldList: MutableList<HistoryEntry>

        fun getFilter(): Filter? {
            return object : Filter() {
                override fun performFiltering(charSequence: CharSequence): FilterResults? {
                    val charString = charSequence.toString()
                    if (charString.isEmpty()) {
                        filtered = oldList
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
                        filtered = filteredList
                    }
                    val filterResults = FilterResults()
                    filterResults.values = filtered
                    return filterResults
                }

                override fun publishResults(charSequence: CharSequence?, filterResults: FilterResults) {
                    dataSet = filterResults.values as MutableList<HistoryEntry>

                    notifyDataSetChanged()
                }
            }
        }

        fun getItem(position: Int) = dataSet[position]

        /**
         * Provide a reference to the type of views that you are using
         * (custom ViewHolder).
         */
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView
            val historyUrl: TextView
            val historyDate: TextView

            init {
                // Define click listener for the ViewHolder's View.
                textView = view.findViewById(R.id.historyTitle)
                historyUrl = view.findViewById(R.id.historyUrl)
                historyDate = view.findViewById(R.id.historyTime)
            }
        }

        // Create new views (invoked by the layout manager)
        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
            // Create a new view, which defines the UI of the list item
            val view = LayoutInflater.from(viewGroup.context)
                    .inflate(R.layout.history_row, viewGroup, false)
            oldList = dataSet.toMutableList()
            return ViewHolder(view)
        }

        // Replace the contents of a view (invoked by the layout manager)
        override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {

            // Get element from your dataset at this position and replace the
            // contents of the view with that element
            viewHolder.textView.text = dataSet[position].title
            viewHolder.historyUrl.text = dataSet[position].url
            viewHolder.historyDate.text = getDateTime(dataSet[position].lastTimeVisited)
        }

        // Return the size of your dataset (invoked by the layout manager)
        override fun getItemCount() = dataSet.size

        private fun getDateTime(s: Long): String? {
            try {
                val sdf = DateFormat.getDateTimeInstance()
                val netDate = Date(s)
                return sdf.format(netDate)
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

        return super.onCreateOptionsMenu(menu)
    }

    override fun onQueryTextChange(query: String?): Boolean {
        arrayAdapter.getFilter()?.filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }

}