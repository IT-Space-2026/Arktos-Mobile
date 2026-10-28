// SPDX-License-Identifier: MPL-2.0
package com.arktosbrowser.mobile.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted "recently closed tabs" list for the History screen —
 * a small SharedPreferences-backed ring of {title, url, time}
 * recorded whenever a tab is deleted, mirroring Iceraven's
 * top-of-history section.
 */
object RecentlyClosedTabs {

    private const val PREFS = "recently_closed_tabs"
    private const val KEY = "tabs"
    private const val MAX = 10

    data class Closed(val title: String, val url: String, val time: Long)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(context: Context, title: String, url: String) {
        if (url.isBlank()) return
        val list = all(context).filter { it.url != url }.toMutableList()
        list.add(0, Closed(title, url, System.currentTimeMillis()))
        val arr = JSONArray()
        list.take(MAX).forEach { c ->
            arr.put(JSONObject().apply {
                put("t", c.title); put("u", c.url); put("m", c.time)
            })
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun all(context: Context): List<Closed> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let {
                Closed(it.optString("t"), it.optString("u"), it.optLong("m"))
            }
        }
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
