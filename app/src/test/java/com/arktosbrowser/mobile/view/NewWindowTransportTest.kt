package com.arktosbrowser.mobile.view

import android.os.Message
import android.webkit.WebView
import org.mockito.kotlin.mock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards upstream issue #306: tapping a picture link wrapped in a
 * `target="_blank"` anchor delivers a new-window transport [Message] to
 * `onCreateWindow`. When the payload is missing or isn't a
 * [WebView.WebViewTransport] the request must be declined so the WebView
 * falls back to loading the link in the current window — an unchecked cast of
 * `resultMsg.obj` previously crashed the app.
 */
class NewWindowTransportTest {

    @Test
    fun `null message is not deliverable`() {
        assertFalse(NewWindowTransport.isDeliverable(null))
    }

    @Test
    fun `message without payload is not deliverable`() {
        val message = mock<Message>()

        assertFalse(NewWindowTransport.isDeliverable(message))
    }

    @Test
    fun `message with non-transport payload is not deliverable`() {
        val message = mock<Message>()
        message.obj = "https://thesafety.us/check-ip"

        assertFalse(NewWindowTransport.isDeliverable(message))
    }

    @Test
    fun `message with WebViewTransport payload is deliverable`() {
        val message = mock<Message>()
        message.obj = mock<WebView.WebViewTransport>()

        assertTrue(NewWindowTransport.isDeliverable(message))
    }

    @Test
    fun `other message fields do not affect deliverability`() {
        val message = mock<Message>()
        message.what = 1
        message.arg1 = 7
        message.obj = mock<WebView.WebViewTransport>()

        assertTrue(NewWindowTransport.isDeliverable(message))
    }

}
