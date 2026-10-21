// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * INCOG04 — the incognito URL/search bar must carry
 * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] so typed queries
 * can't enter the keyboard's learned dictionary; normal browsing
 * leaves the field's options as the layout declared them.
 */
class IncognitoImePolicyTest {

    @Test
    fun `incognito adds the no-personalized-learning flag`() {
        val base = EditorInfo.IME_ACTION_GO
        assertEquals(
            base or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            IncognitoImePolicy.imeOptions(base, isIncognito = true)
        )
    }

    @Test
    fun `incognito preserves every pre-existing option bit`() {
        val base = EditorInfo.IME_ACTION_GO or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        val result = IncognitoImePolicy.imeOptions(base, isIncognito = true)
        assertEquals(
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            result and base.inv()
        )
        assertEquals(base, result and base)
    }

    @Test
    fun `normal browsing leaves the options untouched`() {
        val base = EditorInfo.IME_ACTION_GO
        assertEquals(
            base,
            IncognitoImePolicy.imeOptions(base, isIncognito = false)
        )
    }

    @Test
    fun `an already-set flag survives`() {
        val base = EditorInfo.IME_ACTION_GO or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        assertEquals(
            base,
            IncognitoImePolicy.imeOptions(base, isIncognito = true)
        )
    }

}
