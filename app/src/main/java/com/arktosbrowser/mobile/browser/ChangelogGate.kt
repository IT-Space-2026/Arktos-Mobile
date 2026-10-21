// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

/**
 * CHLOG01 — the one-shot "what's new" gate: after a versionCode bump
 * the changelog opens once in a new tab, then
 * `UserPreferences.lastChangelogVersionCode` records the seen version
 * so it never shows again for that build.
 */
object ChangelogGate {

    /**
     * True when the stored [lastSeenVersionCode] differs from the
     * running [currentVersionCode] — i.e. this build has never shown
     * its changelog. A fresh install (lastSeen 0) counts too: the
     * changelog for the installed version is meaningful on first run.
     */
    fun shouldShow(lastSeenVersionCode: Int, currentVersionCode: Int): Boolean =
        currentVersionCode > 0 && lastSeenVersionCode != currentVersionCode

}
