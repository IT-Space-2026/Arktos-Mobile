// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.uniffi.PasswordCheck

/**
 * FIXB07 — app-lock candidate check (upstream #302).
 *
 * The unlock dialog calls [check] per attempt; anything that isn't a
 * clean match — a wrong password, a malformed stored value, an FFI
 * failure — comes back [Result.Retry] so the prompt stays open with
 * an inline error instead of the app dying on a single mistyped
 * password.
 */
class AppLockGate(
    private val verify: (stored: String, candidate: String) -> PasswordCheck =
        ArktosCore::verifyPassword
) {

    sealed interface Result {
        /** Candidate matched; persist [upgradedHash] when non-null. */
        data class Unlocked(val upgradedHash: String?) : Result

        /** No match — keep the prompt open and flag the field. */
        object Retry : Result
    }

    fun check(stored: String, candidate: String): Result {
        val check = try {
            verify(stored, candidate)
        } catch (e: Exception) {
            return Result.Retry
        }
        return if (check.matched) {
            Result.Unlocked(check.upgradedHash)
        } else {
            Result.Retry
        }
    }
}
