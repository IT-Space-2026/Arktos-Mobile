// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.preference.IntEnum

/**
 * The available proxy choices.
 */
enum class DrawerSizeChoice(override val value: Int) : IntEnum {
    AUTO(0),
    ONE(1),
    TWO(2),
    THREE(3)
}
