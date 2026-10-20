package com.arktosbrowser.mobile.rust

import com.arktosbrowser.mobile.uniffi.RequestVerdict
import com.arktosbrowser.mobile.uniffi.StoreAdblockSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM coverage for the LIST01 subscription model — the preset
 * catalog, per-list merge/remove in the engine, and the
 * `adblock_subscription` store table. Runs against the real host-built
 * `libarktos.so` via `jna.library.path`.
 */
class SubscriptionsTest {

    @Test
    fun `preset catalog mirrors the desktop shape`() {
        val presets = ArktosCore.adBlockPresets()
        assertTrue(presets.size > 50)
        assertTrue(presets.all { it.url.startsWith("https://") })
        assertTrue(presets.all { it.name.isNotBlank() && it.category.isNotBlank() })
        // Desktop default subscriptions: uBlock filters + EasyList + EasyPrivacy.
        assertEquals(
            listOf("uBlock filters", "EasyList", "EasyPrivacy"),
            presets.filter { it.enabledByDefault }.map { it.name }
        )
    }

    @Test
    fun `subscription lists merge into one verdict set`() {
        ArktosCore.configureBlocklist(adsEnabled = true, malwareEnabled = false, lanBlockEnabled = false)
        val listId = "test-merge-${System.nanoTime()}"
        val domain = "merged-${System.nanoTime()}.example"
        try {
            assertEquals(1, ArktosCore.loadSubscriptionHosts(listId, "0.0.0.0 $domain\n"))
            assertEquals(
                RequestVerdict.BLOCK_ADS,
                ArktosCore.requestVerdict("https://$domain/x", "", isMainFrame = false)
            )
        } finally {
            ArktosCore.removeSubscriptionHosts(listId)
        }
        assertEquals(
            RequestVerdict.ALLOW,
            ArktosCore.requestVerdict("https://$domain/x", "", isMainFrame = false)
        )
    }

    @Test
    fun `abp anchors count as domains`() {
        // The shared engine may already hold other lists, so assert on
        // the per-list return count rather than a global verdict.
        val listId = "test-abp-${System.nanoTime()}"
        val count = ArktosCore.loadSubscriptionHosts(
            listId,
            "! Title: t\n" +
                "||ads-x.example^\n" +
                "@@||excepted-x.example^\n" +
                "page-x.example##.ad\n" +
                "0.0.0.0 hosts-x.example\n"
        )
        // ||ads-x.example^ + hosts-x.example — the @@ exception and
        // ## cosmetic rule are skipped.
        assertEquals(2, count)
        ArktosCore.removeSubscriptionHosts(listId)
    }

    @Test
    fun `store subscription rows round-trip`() {
        val dir = java.nio.file.Files.createTempDirectory("arktos-subs-test")
        val store = ArktosCore.openStore("$dir/test.db", TEST_STORE_KEY)
        try {
            assertTrue(store.allAdblockSubscriptions().isEmpty())

            val row = StoreAdblockSubscription(
                id = "https://a.example/list.txt",
                name = "A List",
                url = "https://a.example/list.txt",
                category = "Core & Privacy",
                enabled = true,
                lastUpdated = 0uL,
                hostCount = 0uL,
                custom = false
            )
            assertTrue(store.addAdblockSubscription(row))
            // Same id → INSERT OR IGNORE no-op.
            assertFalse(store.addAdblockSubscription(row.copy(enabled = false)))

            store.setAdblockSubscriptionEnabled(row.id, false)
            store.setAdblockSubscriptionState(row.id, 1234uL, 99uL)

            val all = store.allAdblockSubscriptions()
            assertEquals(1, all.size)
            assertFalse(all[0].enabled)
            assertEquals(1234uL, all[0].lastUpdated)
            assertEquals(99uL, all[0].hostCount)

            store.deleteAdblockSubscription(row.id)
            assertTrue(store.allAdblockSubscriptions().isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun `verdict still allows with no loaded lists`() {
        assertEquals(
            RequestVerdict.ALLOW,
            ArktosCore.requestVerdict("https://never-listed.example", "", isMainFrame = false)
        )
    }

    @Test
    fun `seam exposes subscription helpers`() {
        assertNotNull(ArktosCore.adBlockPresets())
    }
}
