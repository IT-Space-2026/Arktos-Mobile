// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.preference.IntEnum

/**
 * The available proxy choices.
 */
enum class ProxyChoice(override val value: Int) : IntEnum {
    NONE(0),
    ORBOT(1),
    I2P(2),
    MANUAL(3)
}
