# Devin autonomous task list — Arktos Mobile RUST LANE

Rust-component queue driven by `.devin/taskloop-rust.sh`, modeled on
the desktop repo's `Arora-Task-Rust.md` (rust lane). Each run works
the FIRST row whose Status is pending or in_progress, marks it done,
commits, and stops.

Status values: `pending` | `in_progress` | `done` | `blocked`

Rules for the agent working this file:
- Mark the row `in_progress` before starting, `done` only after verifying.
- `blocked:<reason>` in Notes when genuinely stuck — never fake done.
- One task per run: after committing, STOP.
- NEVER edit `.devin/ArktosMobile-Task.md` — that is the main lane's
  queue.
- Cross-lane deps: a note may reference main-lane tasks via
  needs:task:X — check `.devin/ArktosMobile-Task.md` for that row's
  status before starting.
- Append a one-line summary to `.devin/WORKLOG.md` when a task
  completes.
- Format: the table holds a one-line summary per row; the FULL spec
  lives in the `### <id>` section under `## Task details`. Keep table
  cells short — long text belongs in the details section.

Context: Arktos Mobile, Kotlin Android WebView browser
(com.arktosbrowser.mobile). Target: incremental Rust core
(`libarktos.so` via UniFFI) — blocklist engine, storage core,
suggestions/userscript/download logic. Kotlin shell stays (WebView
host, activities, settings UI). Upstream = CookieJarApps/SmartCookieWeb.
Toolchain: `cargo`/`rustup`, `cargo-ndk`, JDK (`needs:tool:java`).

| ID | Task | Status | Attempts | Notes |
|----|------|--------|----------|-------|
| RCORE01 | Rust core foundation — workspace + UniFFI + Gradle/CI wiring — details below | done | 0 | rust/ workspace + arktos-core cdylib (uniffi =0.32.2), cargo-ndk→jniLibs + uniffi-bindgen→kotlin srcDir wired in Gradle; armeabi/mips dropped, x86_64 added; CI rustup+cargo-ndk+NDK steps; libarktos.so in APK @328–575KB stripped/ABI; cargo test + assembleScMainDebug + testScMainDebugUnitTest green (incl. ArktosCore seam tests) |
| BLOCK01 | BlocklistEngine: unify ads/malware/site-block/LAN into Rust — details below | pending | 0 | needs:task:RCORE01 — closes upstream #244 at the shouldInterceptRequest seam |
| STORE01 | Rust storage core: bookmarks/history/downloads (rusqlite) — details below | pending | 0 | needs:task:RCORE01 — closes upstream #156 + #308 |
| PORT01 | Remaining logic ports: suggestions/userscripts/download core/importers — details below | pending | 0 | needs:task:BLOCK01 needs:task:STORE01 |
| SERVO01 | Servo backend — do NOT implement (spike-gated) — details below | blocked | 0 | blocked:spike-gated — engine/BrowserEngine.kt boundary + .devin/ENGINE.md exist; desktop ENG03 verdicts apply; needs separate Android-embedding spike first |

## Task details

### RCORE01
Rust core foundation — no feature code yet.

- `rust/` cargo workspace at repo root + `arktos-core` cdylib crate.
- UniFFI bindings generation wired into Gradle
  (mozilla/rust-android-gradle or equivalent cargo-ndk plugin).
- `cargo-ndk` targets: arm64-v8a, armeabi-v7a, x86_64 (emulator).
- Drop `armeabi`/`mips` from `abiFilters` in `app/build.gradle` —
  Rust cannot target them and they are dead ABIs.
- `.github/workflows/android.yml` gains a Rust toolchain step
  (rustup + cargo-ndk) so CI builds both halves.
- `git update-index` note: keep `gradlew` executable bit.
- Size check: confirm libarktos.so ≲1.5MB/ABI
  (opt-level="z", lto, strip, panic=abort profile).
- Verify: `./gradlew assembleScMainDebug` builds with Rust toolchain
  installed; `libarktos.so` present under
  `app/build/**/jniLibs/<abi>/`.

### BLOCK01
One Rust `BlocklistEngine` behind UniFFI, called once per request at
the `SmartCookieWebClient.shouldInterceptRequest` seam — replaces the
three parallel Kotlin blocklists:

- sources: `hosts.txt` bloom-filter adblock, `malware.txt` check
  (`SmartCookieWebClient:408`), parental `siteBlockNames`.
- Parser port with parity vs `app/src/test/.../HostParserTest.kt`.
- Filter structure: bloom filter or aho-corasick host trie — rebuilt
  at load, so NO MurmurHash parity constraint.
- Private-IP/localhost range detection → upstream #244 (block
  third-party LAN/localhost access, toggle under privacy settings).
- Kotlin keeps data *sourcing* (assets/files/prefs); list content
  pushed into Rust once at init.
- JNI is the per-request hot path: batch init; verdict call must be
  thin (µs, no alloc churn).
- Delete the Kotlin-side malware.txt path once parity proven.
- Verify: rust unit tests + HostParserTest parity pass,
  `assembleScMainDebug` green, blocklist toggle still works.

### STORE01
Rust storage core (`rusqlite`/`sqlx`) replacing the Room layer
(~1.9k LOC: `database/bookmark`, `history`, `downloads`, `adblock`,
`allowlist`, `javascript` entities) behind the existing repository
interfaces.

- New applicationId = fresh installs — no data migration needed;
  define schema in Rust from day one.
- Bookmark folder-tree queries designed to make upstream #156
  (sort by date) and #308 (save into currently-open folder) trivial.
- Netscape bookmark importer ported to a Rust HTML parser
  (`scraper`/`kuchiki` or streaming).
- Keep Room until parity — repositories switch via interface.
- Verify: repository-level tests pass against Rust store; no Room
  references left in the swapped modules.

### PORT01
Remaining logic ports after BLOCK01 + STORE01:

- Search suggestion clients (Google/DDG/Baidu/Naver HTTP models).
- Userscript parsing + injection prep (extension metadata parsing).
- Download engine core: filename/MIME from headers — fixes upstream
  #270/#311/#303 at the core level (coordinate with main-lane FIXB01:
  whoever lands second reconciles).
- Legacy bookmark importer, remaining parsers.
- Evaluate readability4j → Rust readability port (desktop already
  runs dom_smoothie — same crate family).
- Verify: per-module tests; no Kotlin duplicates left.

### SERVO01
Future Servo backend — DO NOT IMPLEMENT in this queue.

Groundwork exists: `engine/BrowserEngine.kt` (unused interface sketch
mirroring desktop `src/engine/engineinterface.h`) + `.devin/ENGINE.md`
coupling audit + `check-engine-audit.sh` gate.

Blocking verdicts from desktop `.devin/SERVO-SPIKE.md` (libservo
v0.7.0), all still apply: no SOCKS proxy (Tor stays WebView-locked),
no cert-error delegate, no per-page settings object, no find-in-page,
no download delegate, no progress fraction, no WebExtensions,
imperative-only cookie control. Android adds EGL-surface + NDK
lifecycle plumbing and ~50–80MB/ABI.

Unblock condition: a dedicated Android-embedding spike proving
lifecycle/surface/input viability, decided explicitly.
