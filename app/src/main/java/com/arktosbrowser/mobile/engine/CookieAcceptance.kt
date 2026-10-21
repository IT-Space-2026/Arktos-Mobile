// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.engine

/**
 * ENG05 — the folded cookie policy shared by every backend.
 *
 * The two user inputs — "accept cookies" (profile switch) and "block
 * third-party cookies" (privacy pref) — fold into one of three
 * acceptances; each backend maps the result onto its own mechanism
 * (`CookieManager` + `setAcceptThirdPartyCookies` on WebView,
 * `ContentBlocking.cookieBehavior`(+PrivateMode) on GeckoView), which
 * keeps the fold itself identical and testable across flavors.
 */
enum class CookieAcceptance {
    NONE, FIRST_PARTY_ONLY, ALL
}

/** Folds the accept-cookies switch and the block-third-party pref. */
fun cookieAcceptance(acceptCookies: Boolean, blockThirdParty: Boolean): CookieAcceptance = when {
    !acceptCookies -> CookieAcceptance.NONE
    blockThirdParty -> CookieAcceptance.FIRST_PARTY_ONLY
    else -> CookieAcceptance.ALL
}
