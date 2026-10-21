// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.preference.IntEnum

/**
 * The available proxy choices.
 */
enum class SiteBlockChoice(override val value: Int) : IntEnum {
    NONE(0),
    WHITELIST(1),
    BLACKLIST(2)
}
