// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.preference.IntEnum

/**
 * An enum representing what detail level should be displayed in the search box.
 */
enum class SearchBoxDisplayChoice(override val value: Int) : IntEnum {
    URL(0),
    DOMAIN(1),
    TITLE(2)
}
