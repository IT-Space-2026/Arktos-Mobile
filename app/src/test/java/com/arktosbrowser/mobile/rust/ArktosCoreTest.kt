package com.arktosbrowser.mobile.rust

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM contract for the Rust seam: `libarktos.so` is an Android
 * cdylib, so it cannot be loaded by local unit tests. These tests pin
 * the lazy-loading contract — touching the seam object is safe, calling
 * across JNI on the host fails fast — while real calls are exercised
 * on-device once the cdylib is packaged.
 *
 * The first binding access throws [UnsatisfiedLinkError]; once the
 * generated `UniffiLib` initializer has failed, further accesses throw
 * [NoClassDefFoundError]. Both are linkage failures — either proves the
 * call failed fast — so the assertions accept both to stay order-free.
 */
class ArktosCoreTest {

    @Test
    fun `seam object initializes without loading the native library`() {
        assertNotNull(ArktosCore)
    }

    @Test
    fun `jni call fails fast when libarktos is absent`() {
        assertLinkageFailure { ArktosCore.ping() }
    }

    @Test
    fun `verdict call fails fast when libarktos is absent`() {
        assertLinkageFailure {
            ArktosCore.requestVerdict("https://example.com", "", isMainFrame = true)
        }
    }

    @Test
    fun `openStore call fails fast when libarktos is absent`() {
        assertLinkageFailure { ArktosCore.openStore("/tmp/arktos-test.db") }
    }

    @Test
    fun `netscape import call fails fast when libarktos is absent`() {
        assertLinkageFailure { ArktosCore.importNetscapeBookmarks("<DL/>") }
    }

    private fun assertLinkageFailure(call: () -> Unit) {
        val error = runCatching(call).exceptionOrNull()
        assertTrue(
            "expected a linkage failure, got $error",
            error is UnsatisfiedLinkError || error is NoClassDefFoundError
        )
    }
}
