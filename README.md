<p align="center"><img src="fastlane/metadata/android/en-US/images/icon.png" width="150"></p>
<h2 align="center"><b>Arktos Mobile</b></h2>
<h4 align="center">A secure, free and open source WebView-based web browser for Android.</h4>

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

## Support

Need help? Open an [issue](https://github.com/IT-Space-2026/Arktos-Mobile/issues) or start a [discussion](https://github.com/IT-Space-2026/Arktos-Mobile/discussions).

---

## Installation

- To get started with this project, import it into Android Studio or build from the command line with Gradle:

 `gradlew assembleDebug` or `./gradlew assembleDebug`

- The build cross-compiles the Rust core (`rust/arktos-core` →
  `libarktos.so`, consumed through UniFFI-generated Kotlin bindings).
  Required on PATH: a Rust toolchain (`rustup`/`cargo`) with the Android
  targets (`aarch64-linux-android`, `armv7-linux-androideabi`,
  `x86_64-linux-android`), `cargo-ndk`, and an Android NDK under the
  configured SDK (`ndk/` side-by-side or `ndk-bundle`).

Signed APKs are published on the [Releases
page](https://github.com/IT-Space-2026/Arktos-Mobile/releases). The
repo carries fastlane store metadata
(`fastlane/metadata/android/en-US/`), so the package already meets the
listing prerequisites for F-Droid/IzzyOnDroid — inclusion in those
repos is a maintainer/packaging step, not a code change (upstream
SmartCookieWeb#314).

---

## Features

- Modern, clean UI
- < 5MB download
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
  from a page prompt, persist per-site `@include`/`@require` metadata,
  and can be removed under Extensions settings
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
  userscripts persist through a single Rust `ArktosStore` (SQLite via
  bundled rusqlite) behind the existing repository interfaces;
  Netscape bookmark imports are parsed in Rust too
- Search suggestions (Google, DuckDuckGo, Baidu, Naver), userscript
  metadata parsing, download filename/MIME resolution, legacy
  bookmark imports/exports and reading-mode article extraction
  (`dom_smoothie`) all run in the Rust core
- URL filtering and special-page detection, error/block page HTML,
  hosts-file parsing, intent-scheme checks, tab/reader color math and
  the app-lock + parental-control passwords (Argon2id with in-place
  upgrade from legacy plaintext) run in the Rust core too

---

## Contributing

Contributions are greatly appreciated. Feel free to open an issue or pull request.

---

## License

- **[MPL-2.0 license](https://www.mozilla.org/en-US/MPL/2.0/)**

Please read the license before forking.

---

## Online services

Arktos Mobile can optionally use online services to improve the browser. Information about what is stored is available [here](ONLINE.md). Translation, news and suggestion endpoints are configurable in settings and are disabled by default.

---

## Known limitations

Arktos Mobile is built on Android WebView, so a few reported issues are platform limits we can't fix in app code — including WebRTC's ability to reveal your real IP, per-site timezone spoofing, and background media playback. See [LIMITATIONS.md](LIMITATIONS.md) for details and workarounds.

---

## Acknowledgements

Arktos Mobile is a fork of [SmartCookieWeb](https://github.com/CookieJarApps/SmartCookieWeb), which is itself a fork of Lightning Browser. Changes since the fork include rebranding, removal of third-party hosted services, and updated dependencies.

Upstream feature highlights inherited from SmartCookieWeb:

- Parental controls (site blocking, settings password, forced SafeSearch)
- Malware blocker
- Bottom navbar
- Custom navbar colour
- 'Force Zoom' option
- 'Inject JavaScript' option
- 'Force HTTPS' option
- 'Always incognito' option
- Cookie dialog blocker
