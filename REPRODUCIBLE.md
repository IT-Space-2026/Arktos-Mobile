# Reproducible builds

Groundwork for F-Droid-style verified builds: this document pins the
exact toolchain and lists the determinism measures baked into the
build. Two machines running the same commit through this recipe should
produce byte-identical *unsigned* APK content (signature excluded —
see "Signing" below).

## Pinned toolchain

| Component | Pin | Where |
|-----------|-----|-------|
| JDK | Temurin 21 (`java -version` → `21.x`) | CI `setup-java`; document only — Gradle runs on the ambient JVM |
| Gradle | 9.6.0 `-bin` | `gradle/wrapper/gradle-wrapper.properties` (`distributionUrl` + `distributionSha256Sum`) |
| Android Gradle Plugin | 9.2.1 | `build.gradle` classpath |
| Kotlin | 2.4.0 | `build.gradle` `kotlinVersion` |
| build-tools | 37.0.0 | root `build.gradle` ext → `android.buildToolsVersion` |
| compileSdk | 37.2 | `app/build.gradle` `compileSdk` DSL |
| Android NDK | 28.2.13676358 | `app/build.gradle` `ndkVersion`; `cargo-ndk` resolves `ndk/<ndkVersion>` first |
| Rust | 1.99.0 + `aarch64/armv7/x86_64-linux-android` targets | `rust/rust-toolchain.toml` (rustup auto-installs) |
| cargo-ndk | 4.1.2 | installed via `cargo install cargo-ndk --version 4.1.2 --locked` |

## Dependency verification

- **Gradle**: `gradle/verification-metadata.xml` records a SHA-256 for
  every artifact (plugins, libraries, aars) resolved by the build.
  Once the file exists, *every* Gradle invocation verifies downloads
  against it — a tampered or re-published artifact fails the build.
  Regenerate after dependency changes:

  ```
  ./gradlew --write-verification-metadata sha256 assembleScMainDebug testScMainDebugUnitTest
  ```

- **Cargo**: `rust/Cargo.lock` is committed; all `cargo` invocations in
  the Gradle wiring pass `--locked`, so resolution cannot drift. All
  `arktos-core` dependencies use exact `=` version pins.

## Determinism measures

- No wall-clock input: versionCode/versionName are static literals,
  nothing in the build scripts reads `System.currentTimeMillis()`,
  dates, or `BUILD_TIMESTAMP`. Gradle archives normalize zip entry
  timestamps and ordering by default.
- Path independence: the Gradle cargo tasks export
  `RUSTFLAGS=--remap-path-prefix=$CARGO_HOME/{registry,git}=/cargo/…`,
  rewriting the machine-specific dependency source paths embedded in
  panic locations and debug info. Workspace sources reach rustc as
  workspace-relative paths already.
- `kotlin.incremental=false` and the release `opt-level=z` + `lto` +
  `codegen-units=1` + `strip` profile keep `libarktos.so` output a pure
  function of inputs.

## Recipe

```
# 1. Toolchain: Temurin JDK 21 on PATH (JAVA_HOME optional but recommended).
# 2. rustup + pinned toolchain: any cargo command under rust/ installs
#    1.99.0 + the Android targets automatically.
cargo install cargo-ndk --version 4.1.2 --locked
# 3. Android SDK + build-tools 37.0.0 + NDK 28.2.13676358
#    (sdkmanager "build-tools;37.0.0" "ndk;28.2.13676358"), sdk.dir in
#    local.properties or ANDROID_HOME set.
# 4. Build the unsigned release APK:
./gradlew assembleScMainRelease
#    Output: app/build/outputs/apk/scMain/release/app-scMain-release-unsigned.apk
```

To compare two builds, strip signatures first (`apksigner`/`apktool`
won't help here — use `zipdrip`, or compare the output of
`diffoscope`/`unzip -l` per entry) and hash the remaining entries.

## Signing (out of scope for bit-identity)

Release APKs are signed with a private key. F-Droid's model handles
this: either the repo builds with its own key, or the maintainer's
signed APK is verified by extracting the unsigned content and
re-signing. The recipe above yields the canonical unsigned artifact.

## Known residual nondeterminism

`--remap-path-prefix` normalizes every rustc-embedded dependency path
(verified: registry deps appear as `/cargo/registry/…` in libarktos.so).
What it cannot reach:

- The vendored OpenSSL build bakes its install prefix as *functional*
  `.rodata` strings (`ENGINESDIR`, `MODULESDIR`, include dirs under the
  cargo target dir) — these are string literals injected by the openssl
  build machinery, not path references any remap flag touches.
- Cargo build-script outputs capture absolute `OUT_DIR` paths, and C
  objects carry their own debug info in debug builds.

Consequence: byte-identical `libarktos.so` currently additionally
requires the **same checkout path and same `CARGO_TARGET_DIR`** on both
builders — satisfied by F-Droid's fixed build root, or by pointing
`CARGO_TARGET_DIR` at a shared absolute path per the recipe. Fully
normalizing the OpenSSL literals needs an openssl-src build-script
patch and is tracked as an open gap.

- `cargo-ndk` and `rustup` host binaries are version-pinned but not
  hash-pinned; supply-chain verification of the tool installers
  themselves is documented as a gap.
- The Gradle daemon / build cache state does not affect outputs but
  incremental caches make byte-diffing stale outputs misleading —
  build clean (`./gradlew clean`) before comparing hashes.
