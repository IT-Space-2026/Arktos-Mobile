package com.arktosbrowser.mobile.rust

import android.app.Application
import android.content.res.AssetManager
import com.arktosbrowser.mobile.adblock.BlocklistLoader
import com.arktosbrowser.mobile.browser.SiteBlockChoice
import com.arktosbrowser.mobile.database.adblock.AdBlockListState
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.uniffi.RequestVerdict
import com.arktosbrowser.mobile.uniffi.StoreAdblockSubscription
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import com.sun.net.httpserver.HttpServer
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM coverage for the PREFS01 additions to
 * [BlocklistLoader] — per-subscription refresh (the settings row's
 * update action) and the remote-list consent gate on automatic
 * fetches. Runs against the real host-built `libarktos.so` and a
 * real `ArktosStore` on a temp db; remote subscriptions are served
 * by a loopback fixture.
 */
class BlocklistLoaderTest {

    private fun tempDir(tag: String): File =
        java.nio.file.Files.createTempDirectory("arktos-block-$tag").toFile()

    @Test
    fun `per row refresh loads and stamps an enabled subscription`() {
        val dir = tempDir("refresh")
        val listFile = File(dir, "custom.txt")
            .apply { writeText("0.0.0.0 per-row-refresh.example\n") }
        val store = ArktosCore.openStore("${dir.absolutePath}/test.db", TEST_STORE_KEY)
        try {
            store.addAdblockSubscription(fileSub(listFile))
            val loader = loader(dir, store)

            loader.refreshSubscription("file://${listFile.absolutePath}")

            val row = store.allAdblockSubscriptions().single()
            assertTrue(row.lastUpdated > 0uL)
            assertTrue(row.hostCount > 0uL)
            ArktosCore.configureBlocklist(
                adsEnabled = true, malwareEnabled = false, lanBlockEnabled = false
            )
            assertEquals(
                RequestVerdict.BLOCK_ADS,
                ArktosCore.requestVerdict(
                    "https://per-row-refresh.example/x", "", isMainFrame = false
                )
            )
        } finally {
            store.close()
            ArktosCore.removeSubscriptionHosts("file://${listFile.absolutePath}")
        }
    }

    @Test
    fun `per row refresh skips a disabled subscription`() {
        val dir = tempDir("refresh-off")
        val listFile = File(dir, "custom.txt")
            .apply { writeText("0.0.0.0 disabled-row.example\n") }
        val store = ArktosCore.openStore("${dir.absolutePath}/test.db", TEST_STORE_KEY)
        try {
            store.addAdblockSubscription(fileSub(listFile, enabled = false))
            val loader = loader(dir, store)

            // Refreshing a disabled list must not merge rules.
            loader.refreshSubscription("file://${listFile.absolutePath}")

            ArktosCore.configureBlocklist(
                adsEnabled = true, malwareEnabled = false, lanBlockEnabled = false
            )
            assertEquals(
                RequestVerdict.ALLOW,
                ArktosCore.requestVerdict(
                    "https://disabled-row.example/x", "", isMainFrame = false
                )
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `consent off skips stale remote subscriptions but not local ones`() {
        val dir = tempDir("consent")
        val listFile = File(dir, "local.txt")
            .apply { writeText("0.0.0.0 local-still-loads.example\n") }
        val store = ArktosCore.openStore("${dir.absolutePath}/test.db", TEST_STORE_KEY)
        try {
            // A remote sub whose cache is missing AND a local file
            // sub — consent off must skip only the remote fetch.
            store.addAdblockSubscription(
                fileSub(listFile).copy(id = "https://never.example/list.txt",
                    url = "https://never.example/list.txt")
            )
            store.addAdblockSubscription(fileSub(listFile))
            val loader = loader(dir, store, consent = false)

            loader.refreshHosts(forceRefresh = false)

            val rows = store.allAdblockSubscriptions()
            val remote = rows.single { it.id == "https://never.example/list.txt" }
            val local = rows.single { it.id.startsWith("file://") }
            assertEquals(0uL, remote.lastUpdated)
            assertTrue(local.lastUpdated > 0uL)
            assertTrue(local.hostCount > 0uL)
        } finally {
            store.close()
            ArktosCore.removeSubscriptionHosts("file://${listFile.absolutePath}")
        }
    }

    @Test
    fun `refreshHosts fetches an enabled remote subscription over loopback`() {
        val dir = tempDir("remote")
        val store = ArktosCore.openStore("${dir.absolutePath}/test.db", TEST_STORE_KEY)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/list.txt") { ex ->
            val body = "0.0.0.0 loopback-fetched.example\n".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/list.txt"
            store.addAdblockSubscription(
                fileSub(File("/unused")).copy(id = url, url = url, custom = true)
            )
            val loader = loader(dir, store)
            // UrlHostsDataSource fetches via OkHttp's async callback —
            // wait for the loader's change notification.
            val latch = CountDownLatch(1)
            loader.onSubscriptionsChanged = { latch.countDown() }

            loader.refreshSubscription(url)
            assertTrue(latch.await(10, TimeUnit.SECONDS))

            val row = store.allAdblockSubscriptions().single { it.id == url }
            assertTrue(row.lastUpdated > 0uL)
            assertTrue(row.hostCount > 0uL)
            ArktosCore.configureBlocklist(
                adsEnabled = true, malwareEnabled = false, lanBlockEnabled = false
            )
            assertEquals(
                RequestVerdict.BLOCK_ADS,
                ArktosCore.requestVerdict(
                    "https://loopback-fetched.example/x", "", isMainFrame = false
                )
            )
        } finally {
            server.stop(0)
            store.close()
            ArktosCore.removeSubscriptionHosts(
                "http://127.0.0.1:${server.address.port}/list.txt"
            )
        }
    }

    private fun fileSub(file: File, enabled: Boolean = true) = StoreAdblockSubscription(
        id = "file://${file.absolutePath}",
        name = file.name,
        url = "file://${file.absolutePath}",
        category = "Custom",
        enabled = enabled,
        lastUpdated = 0uL,
        hostCount = 0uL,
        custom = true
    )

    private fun loader(
        filesDirPath: File,
        store: com.arktosbrowser.mobile.uniffi.ArktosStore,
        consent: Boolean = true
    ): BlocklistLoader {
        val application = mock<Application> { on { filesDir } doReturn filesDirPath }
        val preferences = mock<UserPreferences> {
            on { adBlockEnabled } doReturn true
            on { blockMalwareEnabled } doReturn false
            on { blockLanRequestsEnabled } doReturn false
            on { siteBlockNames } doReturn ""
            on { siteBlockChoice } doReturn SiteBlockChoice.NONE
            on { remoteListsConsentEnabled } doReturn consent
            on { userAgentChoice } doReturn 3
        }
        // Pre-seeded → the loader never touches the preset catalog.
        val listState = AdBlockListState(
            mock<android.content.SharedPreferences> {
                on { getBoolean("subscriptions_seeded", false) } doReturn true
            }
        )
        val logger = object : Logger {
            override fun log(tag: String, message: String) {}
            override fun log(tag: String, message: String, throwable: Throwable) {}
        }
        return BlocklistLoader(
            preferences,
            listState,
            store,
            mock<AssetManager>(),
            application,
            logger,
            Single.just(OkHttpClient()),
            Schedulers.trampoline(),
            Schedulers.trampoline()
        )
    }
}
