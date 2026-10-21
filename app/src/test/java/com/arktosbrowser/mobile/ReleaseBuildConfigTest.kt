// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEC10 — release builds must shrink code (R8) and resources, and the
 * R8 rules must keep the JNA/UniFFI bridge to libarktos.so intact. JNA
 * resolves FFI symbols, `Structure` field order and `Callback` entry
 * points by name at runtime, so shrinking or obfuscating the generated
 * bindings would break the Rust core in release APKs only.
 */
class ReleaseBuildConfigTest {

    private fun moduleFile(path: String): File =
        listOf(path, "app/$path").map(::File).first { it.isFile }

    /** Returns the body of the first `name { ... }` block (braces stripped). */
    private fun blockBody(text: String, name: String): String {
        val start = text.indexOf("$name {")
        require(start >= 0) { "no '$name {' block" }
        var depth = 0
        for (i in start + name.length + 1 until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i)
                }
            }
        }
        error("unbalanced braces after '$name {'")
    }

    @Test
    fun `release buildType enables R8 and resource shrinking`() {
        val release = blockBody(moduleFile("build.gradle").readText(), "release")
        assertTrue(
            "release buildType must set minifyEnabled true",
            release.contains(Regex("minifyEnabled\\s+true"))
        )
        assertTrue(
            "release buildType must set shrinkResources true",
            release.contains(Regex("shrinkResources\\s+true"))
        )
    }

    @Test
    fun `proguard keeps the uniffi generated bindings`() {
        val proguard = moduleFile("proguard-project.txt").readText()
        assertTrue(
            "proguard must keep the generated UniFFI binding package",
            proguard.contains("-keep class com.arktosbrowser.mobile.uniffi.**")
        )
    }

    @Test
    fun `proguard keeps JNA classes and reflectively-read members`() {
        val proguard = moduleFile("proguard-project.txt").readText()
        assertTrue(
            "proguard must keep com.sun.jna classes",
            proguard.contains("-keep class com.sun.jna.**")
        )
        assertTrue(
            "proguard must keep public members of JNA subclasses " +
                "(Structure fields are read reflectively)",
            proguard.contains("extends com.sun.jna.**")
        )
    }
}
