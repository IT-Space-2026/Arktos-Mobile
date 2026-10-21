// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.ssl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards upstream issue #296: `onReceivedSslError` fires once per failing
 * request, so a page with many failing subresources stacked one warning
 * dialog per request — the flicker-then-freeze loop. [SslWarningGate] keeps
 * it to a single prompt per page load and applies the chosen answer to the
 * remaining errors.
 *
 * Pure state machine, exercised directly — no Robolectric in this project.
 */
class SslWarningGateTest {

    private val gate = SslWarningGate()

    @Test
    fun `first ssl error shows the prompt`() {
        assertNull(gate.claimPrompt())
    }

    @Test
    fun `errors while the prompt is showing are cancelled`() {
        assertNull(gate.claimPrompt())
        assertEquals(SslWarningPreferences.Behavior.CANCEL, gate.claimPrompt())
        assertEquals(SslWarningPreferences.Behavior.CANCEL, gate.claimPrompt())
    }

    @Test
    fun `proceed decision covers the rest of the page load`() {
        assertNull(gate.claimPrompt())
        gate.onPromptDismissed(SslWarningPreferences.Behavior.PROCEED)
        assertEquals(SslWarningPreferences.Behavior.PROCEED, gate.claimPrompt())
        assertEquals(SslWarningPreferences.Behavior.PROCEED, gate.claimPrompt())
    }

    @Test
    fun `cancel decision covers the rest of the page load`() {
        assertNull(gate.claimPrompt())
        gate.onPromptDismissed(SslWarningPreferences.Behavior.CANCEL)
        assertEquals(SslWarningPreferences.Behavior.CANCEL, gate.claimPrompt())
    }

    @Test
    fun `a new page load warns again`() {
        assertNull(gate.claimPrompt())
        gate.onPromptDismissed(SslWarningPreferences.Behavior.CANCEL)
        gate.reset()
        assertNull(gate.claimPrompt())
    }

    @Test
    fun `prompt abandoned by a new page load does not wedge the gate`() {
        // Dialog cancelled by onPageStarted fires onPromptDismissed before
        // reset; verify the same end state when only reset runs.
        assertNull(gate.claimPrompt())
        gate.reset()
        assertNull(gate.claimPrompt())
    }
}
