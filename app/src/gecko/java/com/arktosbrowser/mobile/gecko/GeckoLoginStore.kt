// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.gecko

import android.app.Application
import com.arktosbrowser.mobile.passwords.LoginVault
import com.arktosbrowser.mobile.passwords.LoginVaults
import com.arktosbrowser.mobile.passwords.StoredLogin
import java.util.concurrent.Executors
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult

internal fun StoredLogin.toLoginEntry(): Autocomplete.LoginEntry =
    Autocomplete.LoginEntry.Builder()
        .guid(guid)
        .origin(origin)
        .formActionOrigin(formActionOrigin)
        .httpRealm(httpRealm)
        .username(username)
        .password(password)
        .build()

private fun Autocomplete.LoginEntry.toStoredLogin(): StoredLogin = StoredLogin(
    guid = guid,
    origin = origin,
    formActionOrigin = formActionOrigin,
    httpRealm = httpRealm,
    username = username,
    password = password
)

/**
 * AUTOF02 — the gecko flavor's password manager backend.
 *
 * GeckoView stores no logins itself: `GeckoView:Autocomplete:Fetch`
 * requests reach the app through `Autocomplete.StorageProxy`, which
 * errors out when no [Autocomplete.StorageDelegate] is attached — so
 * without this delegate, saved-password autofill can never happen,
 * and a confirmed `onLoginSave` prompt would be dropped on the floor.
 *
 * All work is serialized on a single daemon thread: gecko calls the
 * delegate on the UI thread, but `GeckoResult` may complete from
 * anywhere, and disk IO must never stall the compositor's callers.
 */
class GeckoLoginStore internal constructor(
    private val vault: LoginVault,
    private val dataRoot: String = ""
) : Autocomplete.StorageDelegate {

    constructor(application: Application) : this(
        LoginVaults.get(application),
        application.filesDir.absolutePath
    )

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GeckoLoginStore").apply { isDaemon = true }
    }

    override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>> =
        fetch { vault.forOrigin(domain) }

    override fun onLoginFetch(): GeckoResult<Array<Autocomplete.LoginEntry>> =
        fetch { vault.all() }

    private fun fetch(
        block: () -> List<StoredLogin>
    ): GeckoResult<Array<Autocomplete.LoginEntry>> {
        val result = GeckoResult<Array<Autocomplete.LoginEntry>>()
        io.execute {
            try {
                result.complete(
                    block().map { it.toLoginEntry() }.toTypedArray()
                )
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    override fun onLoginSave(login: Autocomplete.LoginEntry) {
        io.execute {
            val stored = login.toStoredLogin()
            vault.upsert(stored)
            // The sync half — a no-op while the vault is locked.
            if (dataRoot.isNotEmpty()) {
                com.arktosbrowser.mobile.sync.SyncBridge.password(
                    dataRoot, "set", stored.origin, stored.username, stored.password
                )
            }
        }
    }

    override fun onLoginUsed(login: Autocomplete.LoginEntry, usedFields: Int) {
        io.execute { vault.markUsed(login.toStoredLogin()) }
    }

    /**
     * Drop every stored login — called when the profile's stored-data
     * clear covers form data (matches the WebView's form-data wipe).
     */
    fun clear() {
        io.execute {
            vault.clear()
            if (dataRoot.isNotEmpty()) {
                com.arktosbrowser.mobile.sync.SyncBridge.password(dataRoot, "clear", "")
            }
        }
    }
}
