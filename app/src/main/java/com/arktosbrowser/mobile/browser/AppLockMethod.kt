package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.preference.IntEnum

/**
 * SEC01 — browser-wide app-lock methods. Distinct from
 * [PasswordChoice], which governs the parental-control lock only.
 */
enum class AppLockMethod(override val value: Int) : IntEnum {
    NONE(0),
    PIN(1),
    PASSWORD(2),
    BIOMETRIC(3)
}
