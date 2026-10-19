package com.arktosbrowser.mobile.rust

// RCORE01 — seam between the Kotlin shell and the Rust core
// (`libarktos.so`, UniFFI-generated bindings under
// `com.arktosbrowser.mobile.uniffi`).
//
// Every Kotlin→Rust call should go through this object so the JNI/JNA
// boundary stays in one place. The generated bindings load the native
// library lazily on first use, so calling into them is safe without an
// explicit System.loadLibrary here.

import com.arktosbrowser.mobile.uniffi.corePing
import com.arktosbrowser.mobile.uniffi.coreVersion

object ArktosCore {

    /** Semantic version of the linked Rust core. */
    fun coreVersion(): String = coreVersion()

    /**
     * Cheap liveness check — verifies the native library loaded and the
     * FFI trampoline works. Always true when reachable.
     */
    fun ping(): Boolean = corePing()
}
