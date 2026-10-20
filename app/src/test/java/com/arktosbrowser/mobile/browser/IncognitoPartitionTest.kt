package com.arktosbrowser.mobile.browser

import android.content.Context
import com.arktosbrowser.mobile.engine.Backend
import com.arktosbrowser.mobile.engine.Engines
import com.arktosbrowser.mobile.engine.Profile
import com.arktosbrowser.mobile.engine.StorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.File

/**
 * INCOG01 — the incognito partition wipe: correct directories, the
 * main `app_webview` partition never touched, and the session-end
 * clear reaching the engine profile.
 */
class IncognitoPartitionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `incognito process name matches manifest suffix`() {
        assertTrue(
            IncognitoPartition.isIncognitoProcess(
                "com.arktosbrowser.mobile", "com.arktosbrowser.mobile:incognito"
            )
        )
        assertFalse(
            IncognitoPartition.isIncognitoProcess(
                "com.arktosbrowser.mobile", "com.arktosbrowser.mobile"
            )
        )
        assertFalse(
            IncognitoPartition.isIncognitoProcess(
                "com.arktosbrowser.mobile", "com.arktosbrowser.mobile.beta:incognito"
            )
        )
    }

    @Test
    fun `data dir name is the suffixed sibling, not a subdirectory`() {
        assertEquals("app_webview_incognito", IncognitoPartition.webViewDataDirName())
    }

    @Test
    fun `partition dirs never include the default app_webview dir`() {
        val dataDir = tempFolder.root
        val dirs = IncognitoPartition.partitionDirs(dataDir, "com.arktosbrowser.mobile")

        assertTrue(
            dirs.any { it.name == "app_webview_incognito" && it.parentFile == dataDir }
        )
        // OEM builds that force the suffix to the full process name.
        assertTrue(dirs.any { it.name == "app_webview_com.arktosbrowser.mobile:incognito" })
        // The spec's claimed nested layout — covered defensively.
        assertTrue(
            dirs.any {
                it.name == "incognito"
                    && it.parentFile?.name == IncognitoPartition.WEB_VIEW_DEFAULT_DATA_DIR
            }
        )
        assertFalse(dirs.any { it.name == IncognitoPartition.WEB_VIEW_DEFAULT_DATA_DIR })
    }

    @Test
    fun `wipeDirectories removes incognito dirs and spares the main partition`() {
        val dataDir = tempFolder.root
        val incognitoDir = File(dataDir, "app_webview_incognito")
        File(incognitoDir, "Cookies").apply { parentFile?.mkdirs(); writeText("session") }
        val mainDir = File(dataDir, "app_webview")
        File(mainDir, "Cookies").apply { parentFile?.mkdirs(); writeText("keep me") }

        assertTrue(
            IncognitoPartition.wipeDirectories(dataDir, "com.arktosbrowser.mobile")
        )

        assertFalse(incognitoDir.exists())
        // The main profile's partition is untouched — dataDir is app-wide.
        assertTrue(File(mainDir, "Cookies").exists())

        // Idempotent: nothing left to delete still reports success.
        assertTrue(
            IncognitoPartition.wipeDirectories(dataDir, "com.arktosbrowser.mobile")
        )
    }

    /**
     * On the plain JVM `Build.VERSION.SDK_INT` is 0, so this exercises
     * the pre-28 shared-partition branch: only the storage-API clear
     * runs (a full wipe there would hit the main profile), while the
     * directory sweep still applies.
     */
    @Test
    fun `wipeSession on shared partition clears web storage and dirs`() {
        val profile = mock<Profile>()
        val backend = mock<Backend> {
            on { defaultProfile } doReturn profile
        }
        Engines.install(backend)

        val dataDir = tempFolder.root
        File(dataDir, "app_webview_incognito").mkdirs()
        val context = mock<Context> {
            on { this.dataDir } doReturn dataDir
            on { packageName } doReturn "com.arktosbrowser.mobile"
        }

        IncognitoPartition.wipeSession(context)

        verify(profile).clear(
            setOf(StorageArea.LocalStorage, StorageArea.IndexedDb)
        )
        assertFalse(File(dataDir, "app_webview_incognito").exists())
    }
}
