package com.arktosbrowser.mobile.database.javascript

/**
 * A userscript entry. Was `JavaScriptDatabase.JavaScriptEntry` — moved
 * to top level when the SQLiteOpenHelper database was replaced by the
 * Rust storage core (STORE01). Field order is unchanged because
 * `ArktosCookieWebClient` constructs it positionally.
 */
data class JavaScriptEntry(
    val name: String,
    val namespace: String?,
    val version: String?,
    val author: String?,
    val include: String?,
    val exclude: String?,
    val time: String?,
    val permissions: String?,
    val code: String,
    val requirements: String?,
    val runAt: String? = null,
    val noFrames: Boolean = false
)
