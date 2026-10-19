package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.preference.IntEnum

/**
 * The available proxy choices.
 */
enum class PasswordChoice(override val value: Int) : IntEnum {
    NONE(0),
    CUSTOM(1)
}
