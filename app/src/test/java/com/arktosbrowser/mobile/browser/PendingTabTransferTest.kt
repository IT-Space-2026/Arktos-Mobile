package com.arktosbrowser.mobile.browser

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * SEC09 — in-memory Settings → Browser handoff: requests are consumed
 * exactly once and live only in process memory, so the exported
 * activity's intent extras can no longer carry import/export payloads.
 */
class PendingTabTransferTest {

    @Test
    fun `drain returns the posted request exactly once`() {
        PendingTabTransfer.drain() // isolate from any earlier test state
        val urls = listOf("https://example.com", "https://example.org")
        PendingTabTransfer.post(PendingTabTransfer.Request.Import(urls))

        val first = PendingTabTransfer.drain()
        assertTrue(first is PendingTabTransfer.Request.Import)
        assertEquals(urls, (first as PendingTabTransfer.Request.Import).urls)
        assertNull(PendingTabTransfer.drain())
    }

    @Test
    fun `drain on empty returns null`() {
        PendingTabTransfer.drain()
        assertNull(PendingTabTransfer.drain())
    }

    @Test
    fun `post replaces an un-consumed request`() {
        PendingTabTransfer.drain()
        PendingTabTransfer.post(PendingTabTransfer.Request.Import(listOf("https://a.example")))
        val uri = mock<Uri>()
        PendingTabTransfer.post(PendingTabTransfer.Request.Export(uri))

        val drained = PendingTabTransfer.drain()
        assertTrue(drained is PendingTabTransfer.Request.Export)
        assertEquals(uri, (drained as PendingTabTransfer.Request.Export).uri)
        assertNull(PendingTabTransfer.drain())
    }
}
