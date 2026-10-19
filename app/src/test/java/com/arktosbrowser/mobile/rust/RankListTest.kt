package com.arktosbrowser.mobile.rust

import android.app.Application
import com.arktosbrowser.mobile.adblock.RankListLoader
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.uniffi.RankListSource
import com.arktosbrowser.mobile.uniffi.RankListSourceKind
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.sun.net.httpserver.HttpServer
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Host-JVM coverage for RANK01 — the merged Tranco+Majestic rank
 * table, the per-source update/staleness model, and the
 * [RankListLoader] fetch pipeline over a loopback fixture (the
 * desktop's http-to-loopback exception). Runs against the real
 * host-built `libarktos.so` via `jna.library.path`.
 */
class RankListTest {

    private fun tempDir(tag: String): File =
        java.nio.file.Files.createTempDirectory("arktos-rank-$tag").toFile()

    private fun trancoZip(csv: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("top-1m.csv"))
            zip.write(csv.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `seed ranks known domains without any downloads`() {
        val dir = tempDir("seed")
        ArktosCore.setRankListDataDir(dir.absolutePath)
        // The vendored top-100k seed answers with no drop files at all.
        assertEquals(1L, ArktosCore.siteRank("google.com"))
        assertEquals(1L, ArktosCore.siteRank("www.google.com"))
        assertTrue(ArktosCore.siteRank("facebook.com") > 0)
        assertEquals(0L, ArktosCore.siteRank("definitely-not-ranked.invalid"))
        assertTrue(ArktosCore.rankEntryCount() >= 100_000)
    }

    @Test
    fun `drop files merge best rank wins`() {
        val dir = tempDir("merge")
        File(dir, "tranco-list.dat").writeText("50,both-lists.example\n")
        File(dir, "majestic-million.csv").writeText(
            "GlobalRank,TldRank,Domain,TLD\n" +
                "12,4,both-lists.example,example\n" +
                "77,9,mj-only.example,example\n"
        )
        ArktosCore.setRankListDataDir(dir.absolutePath)
        // Best (lowest) rank across sources wins; either-source
        // membership counts; seed coverage survives.
        assertEquals(12L, ArktosCore.siteRank("both-lists.example"))
        assertEquals(77L, ArktosCore.siteRank("mj-only.example"))
        assertEquals(77L, ArktosCore.siteRank("deep.mj-only.example"))
        assertEquals(1L, ArktosCore.siteRank("google.com"))
        assertEquals(0L, ArktosCore.siteRank("unlisted-still.example"))
    }

    @Test
    fun `file entries validates drops and a corrupt file fails reload`() {
        val dir = tempDir("corrupt")
        ArktosCore.setRankListDataDir(dir.absolutePath)
        File(dir, "tranco-list.dat").writeText("33,kept.example\n")
        assertEquals(1L, ArktosCore.rankFileEntries("tranco-list.dat"))
        assertEquals(0L, ArktosCore.rankFileEntries("missing-file.csv"))
        // A junk drop file is detected by the per-file probe AND fails
        // the reload — the previous table stays active either way.
        File(dir, "majestic-million.csv").writeText("<html>404</html>\n")
        assertEquals(0L, ArktosCore.rankFileEntries("majestic-million.csv"))
        try {
            ArktosCore.reloadRankLists()
            fail("corrupt drop file should fail the reload")
        } catch (expected: Exception) {
            // RankListError.Corrupt
        }
        // The shell's sweep fixes it: drop the junk, reload succeeds.
        File(dir, "majestic-million.csv").delete()
        ArktosCore.reloadRankLists()
        assertEquals(33L, ArktosCore.siteRank("kept.example"))
    }

    @Test
    fun `update decision gates on consent and staleness`() {
        val dir = tempDir("stale")
        ArktosCore.setRankListDataDir(dir.absolutePath)
        val now = System.currentTimeMillis()
        // No consent → nothing to fetch, even with no copies at all.
        assertTrue(ArktosCore.rankUpdateIfStale(false, now).isEmpty())
        // Missing copies → stale; both default sources listed.
        val stale = ArktosCore.rankUpdateIfStale(true, now)
        assertEquals(2, stale.size)
        assertTrue(stale.all { it.url.startsWith("https://") })
        // Consent-free explicit refresh lists the same sources.
        assertEquals(2, ArktosCore.rankUpdateNow().size)
        // Fresh copies → nothing to fetch now, stale a week later.
        File(dir, "tranco-list.dat").writeText("1,a.example\n")
        File(dir, "majestic-million.csv").writeText("GlobalRank,TldRank,Domain,TLD\n1,1,a.example,a\n")
        assertTrue(ArktosCore.rankUpdateIfStale(true, now).isEmpty())
        assertEquals(2, ArktosCore.rankUpdateIfStale(true, now + 8L * 24 * 60 * 60 * 1000).size)
        // One missing copy marks the whole set stale.
        File(dir, "majestic-million.csv").delete()
        assertEquals(2, ArktosCore.rankUpdateIfStale(true, now).size)
    }

    @Test
    fun `per source stamps track the drop files`() {
        val dir = tempDir("stamps")
        ArktosCore.setRankListDataDir(dir.absolutePath)
        assertEquals(0L, ArktosCore.rankListLastUpdate(RankListSourceKind.TRANCO))
        File(dir, "tranco-list.dat").writeText("1,a.example\n")
        assertTrue(ArktosCore.rankListLastUpdate(RankListSourceKind.TRANCO) > 0)
        assertEquals(0L, ArktosCore.rankListLastUpdate(RankListSourceKind.MAJESTIC))
        File(dir, "tranco-list.dat").delete()
        assertEquals(0L, ArktosCore.rankListLastUpdate(RankListSourceKind.TRANCO))
    }

    @Test
    fun `fetch is https only with a loopback exception`() {
        val loader = loader(File("/nonexistent"))
        assertTrue(loader.fetchAllowed(HttpUrl.parse("https://tranco-list.eu/x.zip")!!))
        assertTrue(loader.fetchAllowed(HttpUrl.parse("http://127.0.0.1:8080/list")!!))
        assertTrue(loader.fetchAllowed(HttpUrl.parse("http://localhost:9/list")!!))
        assertTrue(loader.fetchAllowed(HttpUrl.parse("http://[::1]:9/list")!!))
        assertFalse(loader.fetchAllowed(HttpUrl.parse("http://example.com/list")!!))
        // Non-http(s) schemes can't even build an HttpUrl.
        assertEquals(null, HttpUrl.parse("ftp://127.0.0.1/list"))
    }

    @Test
    fun `loader fetches commits and merges over loopback`() {
        val dir = tempDir("fetch")
        ArktosCore.setRankListDataDir(File(dir, "site-rank").absolutePath)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tranco") { ex ->
            // The real update path: the downloader commits the zip
            // body verbatim, the engine extracts the .csv member.
            val body = trancoZip("33,zipped.example\n")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/majestic") { ex ->
            val body = (
                "GlobalRank,TldRank,Domain,TLD\n" +
                    "66,3,mj-fetched.example,example\n"
                ).toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "/majestic")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.start()
        try {
            val port = server.address.port
            val sources = listOf(
                RankListSource(
                    RankListSourceKind.TRANCO, "t",
                    "http://127.0.0.1:$port/tranco", "tranco-list.dat", 0uL
                ),
                RankListSource(
                    RankListSourceKind.MAJESTIC, "m",
                    "http://127.0.0.1:$port/redirect", "majestic-million.csv", 0uL
                )
            )
            assertTrue(loader(dir).performUpdate(sources))
            assertEquals(33L, ArktosCore.siteRank("zipped.example"))
            assertEquals(66L, ArktosCore.siteRank("mj-fetched.example"))
            // Committed files carry the update stamps.
            assertTrue(ArktosCore.rankListLastUpdate(RankListSourceKind.TRANCO) > 0)
            assertTrue(ArktosCore.rankListLastUpdate(RankListSourceKind.MAJESTIC) > 0)
            // A non-https remote source is refused.
            val refused = listOf(
                RankListSource(
                    RankListSourceKind.TRANCO, "t",
                    "http://never-fetch.example/list", "tranco-list.dat", 0uL
                )
            )
            assertFalse(loader(dir).performUpdate(refused))
        } finally {
            server.stop(0)
        }
    }

    // ---- PREFS01 — per-source enable + dedicated consent ----------

    @Test
    fun `per source enable flags gate the update list`() {
        val sources = ArktosCore.rankListSources()
        assertEquals(2, loader(File("/x")).enabledSources(sources).size)
        val trancoOff = loader(File("/x"), trancoEnabled = false)
        assertEquals(
            listOf(RankListSourceKind.MAJESTIC),
            trancoOff.enabledSources(sources).map { it.kind }
        )
        val majesticOff = loader(File("/x"), majesticEnabled = false)
        assertEquals(
            listOf(RankListSourceKind.TRANCO),
            majesticOff.enabledSources(sources).map { it.kind }
        )
        assertTrue(
            loader(File("/x"), trancoEnabled = false, majesticEnabled = false)
                .enabledSources(sources).isEmpty()
        )
    }

    @Test
    fun `disabling a source drops its contribution and stamp`() {
        val dir = tempDir("disable")
        val rankDir = File(dir, "site-rank").apply { mkdirs() }
        ArktosCore.setRankListDataDir(rankDir.absolutePath)
        File(rankDir, "majestic-million.csv").writeText(
            "GlobalRank,TldRank,Domain,TLD\n5,2,mj-gone.example,example\n"
        )
        ArktosCore.reloadRankLists()
        assertTrue(ArktosCore.siteRank("mj-gone.example") > 0)

        loader(dir, majesticEnabled = false)
            .setSourceEnabled(RankListSourceKind.MAJESTIC, false)

        assertFalse(File(rankDir, "majestic-million.csv").exists())
        assertEquals(0L, ArktosCore.rankListLastUpdate(RankListSourceKind.MAJESTIC))
        assertEquals(0L, ArktosCore.siteRank("mj-gone.example"))
    }

    @Test
    fun `consent off skips the automatic update`() {
        val dir = tempDir("consent")
        ArktosCore.setRankListDataDir(File(dir, "site-rank").absolutePath)
        val loader = loader(dir, consent = false)
        loader.ensureInitialized()
        // No consent → rankUpdateIfStale returns nothing → no fetch.
        assertEquals(0, File(dir, "site-rank").list()?.size ?: 0)
    }

    @Test
    fun `updateSource is a no-op for a disabled source`() {
        val dir = tempDir("src-off")
        ArktosCore.setRankListDataDir(File(dir, "site-rank").absolutePath)
        val loader = loader(dir, trancoEnabled = false)
        loader.updateSource(RankListSourceKind.TRANCO)
        assertFalse(File(dir, "site-rank/tranco-list.dat").exists())
    }

    private fun loader(
        filesDirPath: File,
        consent: Boolean = true,
        trancoEnabled: Boolean = true,
        majesticEnabled: Boolean = true
    ): RankListLoader {
        val application = mock<Application> { on { filesDir } doReturn filesDirPath }
        // userAgentChoice = 3 → MOBILE_USER_AGENT constant, avoiding
        // the WebSettings path that only exists on-device.
        val preferences = mock<UserPreferences> {
            on { adBlockEnabled } doReturn true
            on { remoteListsConsentEnabled } doReturn consent
            on { trancoListEnabled } doReturn trancoEnabled
            on { majesticListEnabled } doReturn majesticEnabled
            on { userAgentChoice } doReturn 3
        }
        val logger = object : Logger {
            override fun log(tag: String, message: String) {}
            override fun log(tag: String, message: String, throwable: Throwable) {}
        }
        return RankListLoader(
            preferences,
            application,
            logger,
            Single.just(OkHttpClient()),
            Schedulers.trampoline(),
            Schedulers.trampoline()
        )
    }
}
