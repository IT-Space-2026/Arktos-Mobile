// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.preference.IntEnum

/**
 * Swap between the default homepage modes. Value 2 was the removed
 * INFORMATIVE news-feed type — stored 2s fall back to DEFAULT in
 * EnumPreference.
 */
enum class HomepageTypeChoice(override val value: Int) : IntEnum {
    DEFAULT(0),
    FOCUSED(1)
}
