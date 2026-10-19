package com.arktosbrowser.mobile.download

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives [DownloadHttpProbe] against a local fixture server: verifies headers
 * are recovered, redirects are followed to the real file URL, HEAD-rejecting
 * servers fall back to GET, and the browser UA/cookies reach the server —
 * the pieces that keep downloads from being saved as .bin or .html stubs.
 */
class DownloadHttpProbeTest {

    private lateinit var server: HttpServer

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `probe returns content headers and length`() {
        val body = ByteArray(4096) { it.toByte() }
        server.createContext("/file") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/zip")
            exchange.responseHeaders.add(
                "Content-Disposition", "attachment; filename=\"pack.zip\""
            )
            if (exchange.requestMethod == "HEAD") {
                // com.sun server strips an implied length on HEAD — set it explicitly
                exchange.responseHeaders.set("Content-Length", body.size.toString())
                exchange.sendResponseHeaders(200, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }

        val result = DownloadHttpProbe.probe("${baseUrl()}/file")

        assertNotNull(result)
        assertEquals(200, result!!.statusCode)
        assertEquals("application/zip", result.contentType)
        assertEquals("attachment; filename=\"pack.zip\"", result.contentDisposition)
        assertEquals(4096L, result.contentLength)
    }

    @Test
    fun `probe follows redirects to the final file url`() {
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "/real/app.apk")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/real/app.apk") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/vnd.android.package-archive")
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.set("Content-Length", "100")
                exchange.sendResponseHeaders(200, -1)
            } else {
                exchange.sendResponseHeaders(200, 100)
            }
            exchange.close()
        }

        val result = DownloadHttpProbe.probe("${baseUrl()}/start")

        assertNotNull(result)
        assertTrue(result!!.finalUrl.endsWith("/real/app.apk"))
        assertEquals("application/vnd.android.package-archive", result.contentType)
        assertEquals(100L, result.contentLength)
    }

    @Test
    fun `probe falls back to get when head is rejected`() {
        server.createContext("/nohead") { exchange ->
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(405, -1)
                exchange.responseBody.close()
            } else {
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, 5)
                exchange.responseBody.use { it.write("hello".toByteArray()) }
            }
        }

        val result = DownloadHttpProbe.probe("${baseUrl()}/nohead")

        assertNotNull(result)
        assertEquals(200, result!!.statusCode)
        assertEquals("text/plain", result.contentType)
    }

    @Test
    fun `browser user agent and cookies reach the server`() {
        val seenAgent = AtomicReference<String>()
        val seenCookie = AtomicReference<String>()
        server.createContext("/headers") { exchange ->
            seenAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            seenCookie.set(exchange.requestHeaders.getFirst("Cookie"))
            exchange.responseHeaders.add("Content-Type", "application/pdf")
            exchange.sendResponseHeaders(200, 10)
            exchange.responseBody.close()
        }

        DownloadHttpProbe.probe(
            "${baseUrl()}/headers",
            cookies = "session=abc",
            userAgent = "ArktosBrowser/Test"
        )

        assertEquals("ArktosBrowser/Test", seenAgent.get())
        assertEquals("session=abc", seenCookie.get())
    }

    @Test
    fun `chunked response reports unknown length`() {
        server.createContext("/chunked") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, 0) // chunked — no Content-Length
            exchange.responseBody.close()
        }

        val result = DownloadHttpProbe.probe("${baseUrl()}/chunked")

        assertNotNull(result)
        assertEquals(-1L, result!!.contentLength)
    }

    @Test
    fun `accept-ranges header is surfaced`() {
        server.createContext("/ranged") { exchange ->
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Type", "application/zip")
            exchange.sendResponseHeaders(200, 42)
            exchange.responseBody.close()
        }

        val result = DownloadHttpProbe.probe("${baseUrl()}/ranged")

        assertNotNull(result)
        assertTrue(result!!.acceptsRanges)
    }

    @Test
    fun `unreachable server yields null not a crash`() {
        // nothing listens on this port
        val result = DownloadHttpProbe.probe("http://127.0.0.1:1/nope")
        assertNull(result)
    }

    @Test
    fun `malformed url yields null not a crash`() {
        assertNull(DownloadHttpProbe.probe("not a url"))
    }
}
