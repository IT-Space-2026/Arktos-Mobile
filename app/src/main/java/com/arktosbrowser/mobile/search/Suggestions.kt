// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.search

enum class Suggestions(val index: Int) {
    GOOGLE(0),
    DUCK(1),
    NONE(5),
    STARTPAGE(6),
    QWANT(7);

    companion object {
        fun from(value: Int): Suggestions {
            return when (value) {
                0 -> GOOGLE
                1 -> DUCK
                5 -> NONE
                6 -> STARTPAGE
                7 -> QWANT
                else -> DUCK
            }
        }
    }
}