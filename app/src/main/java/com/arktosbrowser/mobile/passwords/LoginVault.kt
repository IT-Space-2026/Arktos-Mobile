// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.passwords

import android.content.Context
import com.arktosbrowser.mobile.database.AndroidKeystoreDbKeyWrapper
import com.arktosbrowser.mobile.database.DbKeyWrapper
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One persisted login — the vault's own record shape so the storage
 * layer stays free of engine types (`Autocomplete.LoginEntry`,
 * WebView JS payloads live at the edges).
 */
data class StoredLogin(
    val guid: String?,
    val origin: String,
    val formActionOrigin: String?,
    val httpRealm: String?,
    val username: String,
    val password: String,
    val lastUsed: Long = 0L
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(FIELD_GUID, guid)
        put(FIELD_ORIGIN, origin)
        put(FIELD_FORM_ACTION_ORIGIN, formActionOrigin)
        put(FIELD_HTTP_REALM, httpRealm)
        put(FIELD_USERNAME, username)
        put(FIELD_PASSWORD, password)
        put(FIELD_LAST_USED, lastUsed)
    }

    companion object {
        private const val FIELD_GUID = "guid"
        private const val FIELD_ORIGIN = "origin"
        private const val FIELD_FORM_ACTION_ORIGIN = "formActionOrigin"
        private const val FIELD_HTTP_REALM = "httpRealm"
        private const val FIELD_USERNAME = "username"
        private const val FIELD_PASSWORD = "password"
        private const val FIELD_LAST_USED = "lastUsed"

        fun fromJson(json: JSONObject): StoredLogin = StoredLogin(
            guid = json.optString(FIELD_GUID).takeIf { it.isNotEmpty() },
            origin = json.getString(FIELD_ORIGIN),
            formActionOrigin =
                json.optString(FIELD_FORM_ACTION_ORIGIN).takeIf { it.isNotEmpty() },
            httpRealm = json.optString(FIELD_HTTP_REALM).takeIf { it.isNotEmpty() },
            username = json.getString(FIELD_USERNAME),
            password = json.getString(FIELD_PASSWORD),
            lastUsed = json.optLong(FIELD_LAST_USED, 0L)
        )
    }
}

/**
 * The encrypted JSON-file store behind both engines' password
 * managers ([GeckoLoginStore]'s storage delegate and the WebView
 * flavor's JS login bridge).
 *
 * The whole entry list rides in one blob wrapped by the same
 * Android-Keystore AES/GCM envelope `DatabaseKeyProvider` uses, so
 * the file is unreadable off-device. `load` quarantines an
 * undecryptable file (renames it `.broken`) instead of crashing;
 * every mutation persists atomically via tmp+rename.
 *
 * All methods are internally synchronized — callers may invoke them
 * from any thread, but owners serialize calls on one executor anyway.
 */
class LoginVault(
    private val loginFile: File,
    private val wrapper: DbKeyWrapper
) {

    private val lock = Any()
    private val entries = mutableListOf<StoredLogin>()
    private var loaded = false

    fun all(): List<StoredLogin> = synchronized(lock) {
        ensureLoaded()
        entries.toList()
    }

    /**
     * Logins whose saved origin matches [origin] exactly — the same
     * matching `nsILoginManager.findLogins` applies, so credentials
     * can never leak into a sibling host's form.
     */
    fun forOrigin(origin: String): List<StoredLogin> =
        all().filter { it.origin == origin }

    /**
     * Insert or replace: an entry with the same guid, else the same
     * (origin, username) pair, is updated in place.
     */
    fun upsert(login: StoredLogin) = synchronized(lock) {
        ensureLoaded()
        val idx = entries.indexOfFirst {
            (login.guid != null && it.guid == login.guid) ||
                (it.origin == login.origin && it.username == login.username)
        }
        if (idx >= 0) {
            entries[idx] = login.copy(
                guid = login.guid ?: entries[idx].guid,
                lastUsed = maxOf(login.lastUsed, entries[idx].lastUsed)
            )
        } else {
            entries.add(login)
        }
        persist()
    }

    /** Touch `lastUsed` for the entry matching the used login. */
    fun markUsed(login: StoredLogin) = synchronized(lock) {
        ensureLoaded()
        val idx = entries.indexOfFirst {
            (login.guid != null && it.guid == login.guid) ||
                (it.origin == login.origin && it.username == login.username)
        }
        if (idx >= 0) {
            entries[idx] = entries[idx].copy(lastUsed = System.currentTimeMillis())
            persist()
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        loaded = true
        persist()
    }

    private fun ensureLoaded() {
        if (!loaded) {
            entries.clear()
            entries += read()
            loaded = true
        }
    }

    private fun read(): List<StoredLogin> {
        val blob = try {
            if (!loginFile.isFile) return emptyList()
            loginFile.readBytes()
        } catch (e: IOException) {
            return emptyList()
        }
        val plain = wrapper.unwrap(blob)
        if (plain == null) {
            // Keystore key rolled over or the blob was tampered with —
            // keep a quarantine copy rather than destroying the data.
            loginFile.renameTo(
                File(loginFile.parentFile, "${loginFile.name}.broken")
            )
            return emptyList()
        }
        return try {
            val array = JSONArray(String(plain, Charsets.UTF_8))
            (0 until array.length()).map { StoredLogin.fromJson(array.getJSONObject(it)) }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun persist() {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        val wrapped = wrapper.wrap(array.toString().toByteArray(Charsets.UTF_8))
        val tmp = File(loginFile.parentFile, "${loginFile.name}.tmp")
        try {
            loginFile.parentFile?.mkdirs()
            tmp.writeBytes(wrapped)
            if (!tmp.renameTo(loginFile)) {
                tmp.delete()
                loginFile.writeBytes(wrapped)
            }
        } catch (e: IOException) {
            tmp.delete()
        }
    }
}

/**
 * The one [LoginVault] per process — shared by the engine storage
 * delegate, the WebView JS bridge, and profile `clear` calls so a
 * data wipe is visible to every consumer immediately.
 */
object LoginVaults {

    @Volatile
    private var instance: LoginVault? = null

    fun get(context: Context): LoginVault =
        instance ?: synchronized(this) {
            instance ?: LoginVault(
                File(context.applicationContext.filesDir, FILE_NAME),
                AndroidKeystoreDbKeyWrapper(KEYSTORE_ALIAS)
            ).also { instance = it }
        }

    private const val FILE_NAME = "logins.db"
    private const val KEYSTORE_ALIAS = "arktos_logins_key"
}
