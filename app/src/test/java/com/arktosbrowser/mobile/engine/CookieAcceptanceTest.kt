// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ENG05 — both backends fold "accept cookies" + "block third-party
 * cookies" through [cookieAcceptance]; the tests pin the fold so the
 * WebView `CookieManager` mapping and the gecko
 * `ContentBlocking.cookieBehavior` mapping can never disagree.
 */
class CookieAcceptanceTest {

    @Test
    fun `cookies disabled wins over everything`() {
        assertEquals(CookieAcceptance.NONE, cookieAcceptance(false, false))
        assertEquals(CookieAcceptance.NONE, cookieAcceptance(false, true))
    }

    @Test
    fun `blocking third party narrows to first party`() {
        assertEquals(
            CookieAcceptance.FIRST_PARTY_ONLY,
            cookieAcceptance(true, true)
        )
    }

    @Test
    fun `accepting without a third party block accepts all`() {
        assertEquals(CookieAcceptance.ALL, cookieAcceptance(true, false))
    }
}
