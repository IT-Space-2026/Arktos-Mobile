package com.arktosbrowser.mobile.translate

import com.arktosbrowser.mobile.js.TranslatePage
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import com.sun.net.httpserver.HttpServer
import io.reactivex.rxjava3.schedulers.Schedulers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The bridge's request validation lives in Rust
 * (`translate_parse_request`) — these exercise it through the UniFFI
 * seam the way the page walker does. The Kotlin envelope (correlation
 * id, reply shape, endpoint round-trip) is covered by [handleMessage]
 * tests against a local fixture server.
 */
class TranslateBridgeTest {

    private lateinit var server: HttpServer
    private val hits = AtomicInteger()
    private val lastBody = AtomicReference<String>()
    private val lastPath = AtomicReference<String>()

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    private fun bridge(endpoint: String) = TranslateBridge(
        mock<UserPreferences> { on { translationEndpoint } doReturn endpoint },
        TranslatePage(),
        mock<Logger>(),
        Schedulers.trampoline(),
        Schedulers.trampoline()
    )

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun serveTranslations(status: Int = 200, body: String = """{"translatedText":["Szia","Világ"]}""") {
        server.createContext("/translate") { exchange ->
            hits.incrementAndGet()
            lastPath.set(exchange.requestURI.path)
            lastBody.set(exchange.requestBody.readBytes().decodeToString())
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (status == 200) bytes.size.toLong() else -1)
            if (status == 200) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    @Test
    fun `parseRequest accepts a valid walker request`() {
        val request = ArktosCore.translateParseRequest(
            """{"texts":["Hello","World"],"target":"hu"}"""
        )!!
        assertEquals(listOf("Hello", "World"), request.texts)
        assertEquals("hu", request.target)
    }

    @Test
    fun `parseRequest accepts region and script subtags`() {
        val request = ArktosCore.translateParseRequest(
            """{"texts":["x"],"target":"zh-Hant-TW"}"""
        )!!
        assertEquals("zh-Hant-TW", request.target)
    }

    @Test
    fun `parseRequest defaults missing target to en`() {
        val request = ArktosCore.translateParseRequest("""{"texts":["x"]}""")!!
        assertEquals("en", request.target)
    }

    @Test
    fun `parseRequest accepts the id envelope the walker wraps it in`() {
        val request = ArktosCore.translateParseRequest(
            """{"id":7,"texts":["x"],"target":"de"}"""
        )!!
        assertEquals("de", request.target)
    }

    @Test
    fun `parseRequest rejects malformed json`() {
        assertNull(ArktosCore.translateParseRequest("not json"))
        assertNull(ArktosCore.translateParseRequest("""{"texts":{}}"""))
        assertNull(ArktosCore.translateParseRequest("""{"other":["x"]}"""))
    }

    @Test
    fun `parseRequest rejects empty texts`() {
        assertNull(ArktosCore.translateParseRequest("""{"texts":[],"target":"hu"}"""))
    }

    @Test
    fun `parseRequest rejects non-language targets`() {
        // Page-controlled input must not reach the request builder as a
        // target — long garbage and punctuation are not BCP-47 tags.
        assertNull(
            ArktosCore.translateParseRequest(
                """{"texts":["x"],"target":"hu'; DROP TABLE--"}"""
            )
        )
        assertNull(
            ArktosCore.translateParseRequest(
                """{"texts":["x"],"target":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
            )
        )
    }

    @Test
    fun `handleMessage returns translated batch with matching id`() {
        serveTranslations()
        val reply = JSONObject(
            bridge(baseUrl()).handleMessage(
                """{"id":3,"texts":["Hello","World"],"target":"hu"}"""
            )
        )
        assertEquals(3, reply.getInt("id"))
        val translated = reply.getJSONArray("translated")
        assertEquals(listOf("Szia", "Világ"), (0 until translated.length()).map(translated::getString))
        // The fixture must see a LibreTranslate-shaped POST on /translate.
        assertEquals(1, hits.get())
        assertEquals("/translate", lastPath.get())
        val sent = JSONObject(lastBody.get())
        assertEquals("hu", sent.getString("target"))
        assertEquals(2, sent.getJSONArray("q").length())
    }

    @Test
    fun `handleMessage replies empty without network on rejected request`() {
        serveTranslations()
        val reply = JSONObject(
            bridge(baseUrl()).handleMessage(
                """{"id":4,"texts":[],"target":"hu"}"""
            )
        )
        assertEquals(4, reply.getInt("id"))
        assertEquals(0, reply.getJSONArray("translated").length())
        assertEquals(0, hits.get())
    }

    @Test
    fun `handleMessage replies empty on blank endpoint`() {
        val reply = JSONObject(
            bridge("").handleMessage("""{"id":5,"texts":["x"],"target":"hu"}""")
        )
        assertEquals(5, reply.getInt("id"))
        assertEquals(0, reply.getJSONArray("translated").length())
    }

    @Test
    fun `handleMessage replies empty on http failure`() {
        serveTranslations(status = 500)
        val reply = JSONObject(
            bridge(baseUrl()).handleMessage("""{"id":6,"texts":["x"],"target":"hu"}""")
        )
        assertEquals(6, reply.getInt("id"))
        assertEquals(0, reply.getJSONArray("translated").length())
        assertEquals(1, hits.get())
    }

    @Test
    fun `handleMessage still answers unparseable messages so the walker never stalls`() {
        val reply = JSONObject(bridge(baseUrl()).handleMessage("not json"))
        assertEquals(-1, reply.getInt("id"))
        assertEquals(0, reply.getJSONArray("translated").length())
    }

    @Test
    fun `buildReply escapes page text`() {
        val reply = JSONObject(
            bridge(baseUrl()).buildReply(9, listOf("say \"hi\"\nline2"))
        )
        assertEquals(9, reply.getInt("id"))
        assertEquals("say \"hi\"\nline2", reply.getJSONArray("translated").getString(0))
    }

    @Test
    fun `parseId falls back to -1 on missing or malformed id`() {
        val bridge = bridge(baseUrl())
        assertEquals(7, bridge.parseId("""{"id":7}"""))
        assertEquals(-1, bridge.parseId("""{"texts":[]}"""))
        assertEquals(-1, bridge.parseId("garbage"))
    }
}
