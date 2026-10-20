package com.arktosbrowser.mobile.browser

import android.view.inputmethod.EditorInfo

/**
 * INCOG04 — decides the [EditorInfo.imeOptions] for the URL/search
 * bar.
 *
 * In an incognito window, typed URLs and queries must not enter the
 * keyboard's learned dictionary, so
 * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] is OR-ed onto the
 * options the layout already declares. Normal browsing keeps them
 * unchanged. Text fields inside web pages are engine-controlled and
 * out of scope — this only covers the toolbar field.
 */
object IncognitoImePolicy {

    /**
     * The [EditorInfo.imeOptions] value to apply on the search field:
     * [baseImeOptions] with `IME_FLAG_NO_PERSONALIZED_LEARNING` added
     * for incognito, untouched otherwise.
     */
    fun imeOptions(baseImeOptions: Int, isIncognito: Boolean): Int =
        if (isIncognito) {
            baseImeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        } else {
            baseImeOptions
        }

}
