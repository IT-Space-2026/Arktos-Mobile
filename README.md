<p align="center"><img src="fastlane/metadata/android/en-US/images/icon.png" width="150"></p>
<h2 align="center"><b>Arktos Mobile</b></h2>
<h4 align="center">A secure, free and open source browser for Android — ships as <b>Arktos</b> (GeckoView) or <b>Arktos Light</b> (WebView).</h4>

<p align="center">
<a href="https://github.com/IT-Space-2026/Arktos-Mobile/releases" alt="GitHub Release (latest by date)"><img src="https://img.shields.io/github/v/release/IT-Space-2026/Arktos-Mobile.svg" ></a>
<a href="https://github.com/IT-Space-2026/Arktos-Mobile/actions" alt="Android CI"><img src="https://github.com/IT-Space-2026/Arktos-Mobile/workflows/Android%20CI/badge.svg?event=push"></a>
<a href="https://www.mozilla.org/en-US/MPL/2.0/" alt="MPL-2.0 License"><img src="https://img.shields.io/github/license/IT-Space-2026/Arktos-Mobile"></a>
<a href="https://github.com/IT-Space-2026/Arktos-Mobile/stargazers" alt="GitHub Stars"><img src="https://img.shields.io/github/stars/IT-Space-2026/Arktos-Mobile?style=social"></a>
<a href="https://github.com/IT-Space-2026/Arktos-Mobile/issues" alt="GitHub Issues"><img src="https://img.shields.io/github/issues-raw/IT-Space-2026/Arktos-Mobile"></a>
</p>
<hr>
<p align="center"><a href="https://github.com/IT-Space-2026/Arktos-Mobile"><b>Source Code</b></a> &bull; <a href="https://github.com/IT-Space-2026/Arktos-Mobile/issues"><b>Issue Tracker</b></a></p>

---

## 1.2.6 — Sync foundation

Settings gains a Synchronization section (device identity, sync status, paired-device list) — the shell the pairing/transport/store milestones build on.

## Support

Need help? Open an [issue](https://github.com/IT-Space-2026/Arktos-Mobile/issues) or start a [discussion](https://github.com/IT-Space-2026/Arktos-Mobile/discussions).

---

## Installation

- Prebuilt APKs for both variants are attached to each release on the
  [Releases](https://github.com/IT-Space-2026/Arktos-Mobile/releases)
  page. To build from source, import the project into Android Studio or
  build from the command line with Gradle:

 `./gradlew assembleScMainWebviewDebug` (Android WebView build) or
 `./gradlew assembleScMainGeckoDebug` (GeckoView build).
 `assembleDebug` builds every flavor combination.

- **Engine flavors** (`engine` dimension, on top of the `capabilities`
  channel dimension): `webview` ships the system WebView backend as
  **Arktos Light** (`com.arktosbrowser.mobile`); `gecko` ships the
  GeckoView backend as **Arktos**
  (`com.arktosbrowser.mobile.full`), so both can be installed side by
  side. The backend is selected at build time by the flavor-scoped
  `engine/EngineSelector.kt`; the shared UI shell talks only to the
  neutral `Page`/`Profile`/`Backend` seam in `engine/BrowserEngine.kt`.
  The gecko build covers the main browsing surface: navigation/policy,
  prompts, permissions, downloads, cookies/storage clearing, proxy
  (Orbot/I2P/manual), userscripts (via a built-in WebExtension) and
  the mappable settings (UA, font size, viewport mode, dark color
  scheme, image blocking, DNT + Sec-GPC). Settings with no gecko
  equivalent are hidden by engine capability gates rather than left as
  dead toggles; the remaining gaps (SSL bypass, favicons, printing,
  per-request cookie get/set) are tracked in `.devin/ENGINE.md`.

  Gecko tab/window handling follows the mozilla-components
  `EngineView` model: a single shared `GeckoView` (`ArktosGeckoView`)
  binds and unbinds sessions in place, keeps them alive across window
  detaches (tab swaps to generated pages, fullscreen reparents), and
  re-opens sessions whose content process was killed — restoring their
  `SessionState` on next select. Tabs restore across restarts with
  scroll and history where the session state unparcels, and a plain
  URL list catches corrupted parcels. Per-site permission decisions
  (geolocation, notifications, autoplay, camera/mic) persist per
  origin for regular tabs and stay memory-only in incognito.

- The build cross-compiles the Rust core (`rust/arktos-core` →
  `libarktos.so`, consumed through UniFFI-generated Kotlin bindings).
  Required on PATH: a Rust toolchain (`rustup`/`cargo`) with the Android
  targets (`aarch64-linux-android`, `armv7-linux-androideabi`,
  `x86_64-linux-android`), `cargo-ndk`, and an Android NDK under the
  configured SDK (`ndk/` side-by-side or `ndk-bundle`).

- Toolchain versions, dependency-verification setup and the unsigned
  release recipe for reproducible/F-Droid-style builds are documented
  in [REPRODUCIBLE.md](REPRODUCIBLE.md).

### Gecko build size

GeckoView's `libxul.so` is ~150MB uncompressed for a single ABI — a
universal gecko APK would exceed 500MB and is intentionally not
produced. The `gecko` release scope is **arm64-v8a only**
(`ndk.abiFilters` on the flavor); armeabi-v7a and x86 devices should
install the Lite flavor instead. The flavor also enables
`useLegacyPackaging`, which deflates ~178MB of stored native libs to
~76MB inside the APK.

Measured v1.0 unsigned release APKs: **Arktos ≈ 98MB** (arm64,
`com.arktosbrowser.mobile.full`) and **Arktos Light ≈ 19MB**
(universal, `com.arktosbrowser.mobile`). An **AAB**
(`bundleScMainGeckoRelease`) remains an option for store
distribution, but per-ABI APKs work fine for direct downloads at
this size.

APKs are published on the [Releases
page](https://github.com/IT-Space-2026/Arktos-Mobile/releases). The
repo carries fastlane store metadata
(`fastlane/metadata/android/en-US/`), so the package already meets the
listing prerequisites for F-Droid/IzzyOnDroid — inclusion in those
repos is a maintainer/packaging step, not a code change (upstream
SmartCookieWeb#314).

---

## Features

- Modern, clean UI
- Light build is ~19MB universal APK (~12MB per-device with ABI
  splits — the bundled `libarktos.so` Rust core dominates). The full
  build's size comes from GeckoView — see the size section above
- Tons of settings
- Blocks ads and trackers — unified in the Rust `BlocklistEngine`
  (`libarktos.so`): ad hosts, malware list, custom site block, and
  public→local-network request blocking all share one per-request verdict
- Multi-list ad-block subscriptions: the desktop preset catalog
  (EasyList, uBlock, AdGuard, regional lists, …) plus custom remote or
  local lists, each toggleable and auto-refreshed on a 7-day cadence
- Per-site JavaScript and cookie quick-toggles in the toolbar menu and
  on long-pressing a link — host-keyed overrides that persist per
  origin on top of the global switches
- Userscript support — Greasemonkey-style `.user.js` scripts install
  from a page prompt and inject at document-start via WebViewCompat,
  with `@include`/`@exclude`/`@run-at`/`@require`/`@connect` honoured
  and a GM bridge (`GM_xmlhttpRequest`, `GM_setValue`, …) persisted in
  the Rust store; `GM_xmlhttpRequest` is restricted to each script's
  declared `@connect` domains (same-origin only without them) and
  bridge calls are checked against the script's include rules; scripts
  can be removed under Extensions settings
- Desktop-parity site shield — a shield button ahead of the URL bar
  opens a per-site privacy panel (ad-block, JavaScript, cookies,
  SafeSearch), and the padlock tint reflects the site's verdict
- Proxy support — Orbot/Tor, I2P, or a manual HTTP proxy host:port
  (Settings → General → HTTP proxy); no special build needed
- Site-rank table (Tranco + Majestic Million) in the Rust core — a
  vendored top-100k seed merged best-rank-wins with consent-gated
  downloads, ready for "top-N site" trust policy
- List management screen under Privacy settings (desktop
  AdBlockDialog mirror): per-list toggles, counts and last-update
  stamps, per-row and refresh-all actions, plus a remote-list
  auto-download consent toggle (manual refresh always works)
- Bookmarks, history, downloads, the ad-block allow list and
  userscripts persist through a single Rust `ArktosStore` (SQLCipher-
  encrypted SQLite, keyed by an Android Keystore-wrapped random key)
  behind the existing repository interfaces;
  Netscape bookmark imports are parsed in Rust too
- Search suggestions (DuckDuckGo, Google, Startpage, Qwant), userscript
  metadata parsing, download filename/MIME resolution, legacy
  bookmark imports/exports and reading-mode article extraction
  (`dom_smoothie`) all run in the Rust core
- URL filtering and special-page detection, error/block page HTML,
  hosts-file parsing, intent-scheme checks, tab/reader color math and
  the app-lock + parental-control passwords (Argon2id with in-place
  upgrade from legacy plaintext) run in the Rust core too
- Built-in password manager on both engines — a "Save password?"
  prompt on login and autofill on revisit (single match fills
  directly, several accounts get a chooser), backed by an Android
  Keystore–wrapped AES-GCM vault that stays local to the device;
  gated by the Save-passwords privacy toggle and fully disabled in
  incognito. System password managers keep working alongside via the
  Android autofill integration
- Browser-wide app lock — PIN, password, or biometric unlock (system
  credential fallback on API 30+) with a configurable re-lock window
  (immediately, 1/5/10 minutes, or a custom minute count) that re-arms
  after the app sits in the background; a full-screen lock page covers
  open tabs so page content can't leak into recents thumbnails
- Privacy signals — `DNT: 1` and `Sec-GPC: 1` (Global Privacy Control)
  headers are sent on top-level navigations when enabled
- Panic trigger — an external intent wipes the browsing session; can be
  disabled under Privacy settings for users without a panic-button app
- Modernized codebase — no deprecated APIs remain: coroutines instead
  of AsyncTask, ActivityResultContracts instead of
  startActivityForResult/onRequestPermissionsResult, `overrideActivityTransition`,
  and FileProvider-backed camera uploads
- Password-encrypted backups — Settings → Import/Export can seal
  bookmarks + settings into an `ARKBAK` archive (PBKDF2 key
  derivation → AES-256-GCM) that restores only with the password;
  Android Auto Backup no longer carries the browsing database, so
  history/bookmarks/downloads never leave the device in plaintext
- In-app changelog — Settings → About → Changelog (or
  `arktos://changelog` in the URL bar) renders the repo `ChangeLog`
  styled like the other internal pages; after an update it opens once
  in a new tab

---

## Source structure

```
rust/arktos-core/     Rust browser-core library (libarktos.so) — UniFFI crate
  src/blocklist.rs      unified ad/malware/site-block/LAN request verdicts
  src/subscriptions.rs  multi-list ad-block subscriptions (desktop catalog)
  src/ranklist.rs       Tranco + Majestic Million site-rank tables
  src/storage.rs        ArktosStore: bookmarks/history/downloads/userscripts
                        (SQLCipher-encrypted SQLite via bundled rusqlite)
  src/download.rs       download filename/MIME resolution
  src/netscape.rs       Netscape bookmark-file import/export
  src/readability.rs    reading-mode extraction (dom_smoothie)
  src/suggestions.rs    search-suggestion client (DDG/Google/Startpage/Qwant)
  src/userscript.rs     Greasemonkey metadata parsing + @require resolution
  src/translate.rs      LibreTranslate-compatible request/response format
  src/password.rs       Argon2id app-lock / parental-control hashing
  src/urlutils.rs       URL filtering, special pages, intent-scheme checks
  src/pages.rs          error/block/changelog page HTML generation
  src/misc.rs           color math, hosts-file parsing, misc pure logic
app/src/main/java/com/arktosbrowser/mobile/
                      Android shell (Kotlin; no Java sources remain)
  view/                 WebView backend: ArktosCookieView/WebClient/ChromeClient
                        + UserScriptEngine (document-start injection, GM bridge)
  engine/               engine-neutral Backend/Profile/Page contract +
                        Engines registry + EngineSelector
app/src/gecko/java/     GeckoView backend (GeckoEngine/GeckoProfile/GeckoPage)
app/src/webview/java/   WebView flavor glue (EngineSelector impl)
  browser/              activity, tabs, presenter, drawer views
  settings/             settings fragments incl. Privacy + list management
  rust/ArktosCore.kt    thin Kotlin façade over UniFFI-generated bindings
  i2p/                  vendored I2P Android helper (bind/AIDL platform glue)
app/src/main/aidl/    I2P router-service AIDL contract (wire compatibility)
app/src/main/assets/  injected JS (dark mode, reflow, translate, blockers) +
                      generated pages (homepage, private, changelog, warning)
app/src/test/         JVM unit tests (Rust core exercised via JNA)
.devin/               task-loop machinery, ENGINE.md touchpoint doc,
                      check-engine-audit.sh (webview-boundary lint)
```

Architecture rule: portable logic lives in `rust/`; Kotlin owns WebView,
UI, lifecycle and platform IPC (including the I2P `bindService` glue).
Anything touching `android.webkit` must be documented in
`.devin/ENGINE.md` — enforced by `check-engine-audit.sh`.

---

## Third-party components

Android (Gradle) — highlights:

- AndroidX (appcompat, core-ktx, webkit, preference, recyclerview,
  constraintlayout, drawerlayout, cardview, palette) — Apache-2.0
- Jetpack Compose UI (view-source editor dialogs) — Apache-2.0
- Material Components — Apache-2.0
- Dagger 2 — Apache-2.0
- RxJava 3 / RxAndroid / RxKotlin — Apache-2.0
- OkHttp + Okio — Apache-2.0
- jsoup — MIT
- sora-editor (view-source editor) — LGPL-2.1
- mozilla.components concept-fetch — MPL-2.0
- GeckoView (`org.mozilla.geckoview`, gecko flavor only) — MPL-2.0
- permissionx, QuadFlask colorpicker, AppIntro — Apache-2.0
- JNA (host-side UniFFI tests) — Apache-2.0 / LGPL-2.1
- Vendored I2P Android helper — Apache-2.0 (upstream i2p.android.base)

Rust (Cargo) — `rust/arktos-core` pins exact versions:

- uniffi — MPL-2.0; rusqlite (bundled SQLCipher — BSD, vendored OpenSSL — Apache-2.0) — MIT
- dom_smoothie — MIT; html5gum — MIT
- serde_json — MIT/Apache-2.0; aho-corasick — MIT
- argon2 — MIT/Apache-2.0; miniz_oxide — MIT
- encoding_rs, mime_guess, rand_core — Apache-2.0/MIT

In-app copies: Settings → About lists the same set with links to each
license text. Bundled data lists: hosts.txt (AdGuard-derived, GPL),
Tranco + Majestic seeds (see `.devin/` notes for refresh policy).

---

## Contributing

Contributions are greatly appreciated. Feel free to open an issue or pull request.

---

## License

- **[MPL-2.0 license](https://www.mozilla.org/en-US/MPL/2.0/)**

Please read the license before forking.

---

## Online services

Arktos Mobile can optionally use online services to improve the browser. Information about what is stored is available [here](ONLINE.md). The translation endpoint is configurable in settings and the feature is off by default; search suggestions default to DuckDuckGo and can be fully disabled.

---

## Known limitations

Arktos Light is built on Android WebView, so a few reported issues are platform limits we can't fix in app code — including WebRTC's ability to reveal your real IP, per-site timezone spoofing, background media playback, and DNT/Global-Privacy-Control signals reaching only top-level navigations (Arktos on GeckoView covers every request). See [LIMITATIONS.md](LIMITATIONS.md) for details and workarounds.

---

## Acknowledgements

Arktos Mobile is a fork of [SmartCookieWeb](https://github.com/CookieJarApps/SmartCookieWeb), which is itself a fork of Lightning Browser. Changes since the fork include a full rebrand, a Rust core behind UniFFI, a dual-engine split (Arktos on GeckoView, Arktos Light on WebView), an app lock and encrypted backups, incognito hardening, and a modernization sweep that removed every deprecated API family. Dead upstream hosted services were removed; the online services that remain are optional and documented in [ONLINE.md](ONLINE.md).

Upstream feature highlights inherited from SmartCookieWeb:

- Parental controls (site blocking, settings password, forced SafeSearch)
- Malware blocker
- Bottom navbar
- Custom navbar colour
- 'Force Zoom' option
- 'Inject JavaScript' option
- 'Force HTTPS' option (HTTPS-only mode: warns before loading cleartext sites)
- 'Block mixed content' option (on by default; always on in incognito)
- 'Secure windows' option (blocks screenshots/recents thumbnails; always on in incognito)
- Tapjacking protection on sensitive dialogs (locks, credentials, proxy, download confirm)
- Download risk warnings (executable/scriptable files, name-vs-MIME disguises)
- 'Show punycode domains' option (on by default; shows xn-- form for internationalized domains to expose lookalike phishing domains)
- 'Referrer policy' option (default / same-origin only / never send on page navigations)
- 'Always incognito' option
- Incognito sessions run in a separate process with their own storage partition that is wiped when the session ends (and again on the next incognito start, so swipe-away can't carry cookies or logins forward); the private-tabs notification is hidden from the lock screen and the keyboard is barred from learning incognito-typed text
- Panic-button intent support — a panic app can wipe the session; toggleable in Privacy settings (also covers the incognito profile)
- Cookie dialog blocker
