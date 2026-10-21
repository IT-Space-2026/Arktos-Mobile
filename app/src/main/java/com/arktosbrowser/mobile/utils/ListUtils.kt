// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.utils

fun stringContainsItemFromList(inputStr: String, items: Array<String>): Boolean {
    for (i in items.indices) {
        if (inputStr.contains(items[i])) {
            return true
        }
    }
    return false
}