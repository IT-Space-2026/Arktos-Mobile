package com.arktosbrowser.mobile.rust

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Host-JVM contract for the Rust seam: `libarktos.so` is an Android
 * cdylib, so it cannot be loaded by local unit tests. These tests pin
 * the lazy-loading contract — touching the seam object is safe, calling
 * across JNI on the host fails fast with UnsatisfiedLinkError — while
 * real calls are exercised on-device once the cdylib is packaged.
 */
class ArktosCoreTest {

    @Test
    fun `seam object initializes without loading the native library`() {
        assertNotNull(ArktosCore)
    }

    @Test(expected = UnsatisfiedLinkError::class)
    fun `jni call fails fast when libarktos is absent`() {
        ArktosCore.ping()
    }
}
