package com.arktosbrowser.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEC16 — reproducible/verified build groundwork. These tests pin the
 * contract that makes two builds of the same commit comparable:
 * exact toolchain versions, verified dependency resolution, and no
 * wall-clock inputs injected by the build scripts themselves.
 */
class ReproducibleBuildTest {

    /**
     * Resolves a file relative to the repo root whether the test runs
     * from :app or the root. The root is identified by settings.gradle,
     * so "build.gradle" can never resolve to the module's own file.
     */
    private fun repoFile(path: String): File {
        var dir = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle").isFile) {
            dir = dir.parentFile
        }
        val file = File(dir ?: File(""), path)
        check(file.isFile) { "repo file not found: $path" }
        return file
    }

    @Test
    fun `gradle wrapper pins the distribution and its checksum`() {
        val props = repoFile("gradle/wrapper/gradle-wrapper.properties").readText()
        assertTrue(
            "distributionUrl must pin an exact Gradle version",
            Regex("distributionUrl=.*gradle-\\d+\\.\\d+(\\.\\d+)?-bin\\.zip")
                .containsMatchIn(props)
        )
        assertTrue(
            "distributionSha256Sum must verify the downloaded distribution",
            Regex("distributionSha256Sum=[0-9a-f]{64}").containsMatchIn(props)
        )
    }

    @Test
    fun `rust toolchain file pins an exact rustc and android targets`() {
        val toolchain = repoFile("rust/rust-toolchain.toml").readText()
        assertTrue(
            "toolchain channel must be an exact version, not a moving tag",
            Regex("channel\\s*=\\s*\"\\d+\\.\\d+\\.\\d+\"").containsMatchIn(toolchain)
        )
        for (target in listOf(
            "aarch64-linux-android",
            "armv7-linux-androideabi",
            "x86_64-linux-android",
        )) {
            assertTrue("rust-toolchain.toml must list target $target", target in toolchain)
        }
    }

    @Test
    fun `android toolchain versions are pinned`() {
        val app = repoFile("app/build.gradle").readText()
        assertTrue(
            "ndkVersion must pin an exact side-by-side NDK",
            Regex("ndkVersion\\s+'\\d+\\.\\d+\\.\\d+'").containsMatchIn(app)
        )
        assertTrue(
            "buildToolsVersion must be wired into the android block",
            app.contains("buildToolsVersion project.buildToolsVersion")
        )
        val root = repoFile("build.gradle").readText()
        assertTrue(
            "root buildToolsVersion must be an exact version",
            Regex("buildToolsVersion\\s*=\\s*'\\d+\\.\\d+\\.\\d+'").containsMatchIn(root)
        )
    }

    @Test
    fun `cargo invocations run with --locked`() {
        val app = repoFile("app/build.gradle").readText()
        assertTrue(
            "host lib build must pass --locked",
            app.contains("'cargo', 'build', '--locked'")
        )
        assertTrue(
            "uniffi-bindgen run must pass --locked",
            app.contains("'cargo', 'run', '--quiet', '--locked'")
        )
        assertTrue(
            "cargo-ndk build must pass --locked",
            app.contains("'build', '--locked'")
        )
    }

    @Test
    fun `dependency verification metadata is generated and enforced`() {
        val meta = repoFile("gradle/verification-metadata.xml").readText()
        assertTrue(
            "verification-metadata.xml must enable checksum verification",
            meta.contains("<verify-metadata>true</verify-metadata>")
        )
        assertTrue(
            "verification-metadata.xml must record sha256 checksums",
            meta.contains("<sha256")
        )
    }

    @Test
    fun `build scripts inject no wall-clock nondeterminism`() {
        val clock = Regex(
            "currentTimeMillis|new Date\\(|Instant\\.now|BUILD_TIMESTAMP|System\\.getenv\\('SOURCE_DATE_EPOCH'\\)"
        )
        for (file in listOf("build.gradle", "app/build.gradle", "gradle.properties")) {
            val text = repoFile(file).readText()
            assertTrue("$file must not embed wall-clock values", !clock.containsMatchIn(text))
        }
    }

    @Test
    fun `rust dependencies are exact-pinned and locked`() {
        assertTrue(
            "rust/Cargo.lock must be committed for --locked builds",
            repoFile("rust/Cargo.lock").isFile
        )
        val manifest = repoFile("rust/arktos-core/Cargo.toml").readText()
        val depsSection = manifest.substringAfter("[dependencies]").substringBefore("\n[")
        val depLines = depsSection.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        assertTrue("expected dependency entries in Cargo.toml", depLines.isNotEmpty())
        for (line in depLines) {
            assertTrue(
                "dependency must use an exact = pin: $line",
                Regex("=\\s*(\\{\\s*version\\s*=\\s*)?\"=\\d").containsMatchIn(line)
            )
        }
    }
}
