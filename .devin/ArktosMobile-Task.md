# Devin autonomous task list — Arktos Mobile (main lane)

This file is the task queue for the Devin CLI task loop
(`.devin/taskloop.sh`), ported from the desktop repo's
`.devin/Arora-Task.md`. Each run of the loop picks the FIRST row whose
Status is `pending` or `in_progress` (crash-restart), works it to
completion, updates the row, commits, and stops.

Status values: `pending` | `in_progress` | `done` | `blocked`

Rules for the agent working this file:
- Mark the row `in_progress` before starting work, `done` only after
  building and verifying per the task's own verification hint.
- `blocked:<reason>` in Notes when genuinely stuck — never fake done.
- One task per run: after committing, STOP.
- Never revert unrelated working-tree changes.
- NEVER edit `.devin/ArktosMobile-Task-Rust.md` — that is the rust
  lane's queue.
- Cross-lane deps: a note may reference rust-lane tasks via
  needs:task:X — check `.devin/ArktosMobile-Task-Rust.md` for that
  row's status before starting.
- Append a one-line summary to `.devin/WORKLOG.md` when a task
  completes.
- Format: the table holds a one-line summary per row; the FULL spec
  lives in the `### <id>` section under `## Task details`. Keep table
  cells short — long text belongs in the details section.

Context: repo is Arktos Mobile, a Kotlin Android WebView browser
(~23.4k LOC Kotlin) forked from CookieJarApps/SmartCookieWeb and
rebranded (package `com.arktosbrowser.mobile`). Upstream issue numbers
referenced throughout are github.com/CookieJarApps/SmartCookieWeb.
Build: `./gradlew assembleScMainDebug` (needs JDK — `needs:tool:java`
on every build-verifying task; no JDK on this host as of queue
creation). Tests: `./gradlew testScMainDebugUnitTest`. Engine
coupling is audited by `.devin/check-engine-audit.sh` — any file newly
touching `android.webkit.*` must be added to `.devin/ENGINE.md`.
NO SUDO — everything user-local.

| ID | Task | Status | Attempts | Notes |
|----|------|--------|----------|-------|
| ENG01 | Engine boundary sketch + coupling audit (Servo groundwork) — details below | done | 0 | landed engine/BrowserEngine.kt (unused interface mirroring desktop engineinterface.h) + .devin/ENGINE.md coupling map + .devin/check-engine-audit.sh gate PASS (28 touchpoints accounted) |
| REBRAND01 | Rebrand to Arktos Mobile — details below | done | 0 | com.cookiegames.smartcookie→com.arktosbrowser.mobile across ~260 files, Arktos logo icon set all densities+flavors, dead services → neutral defaults (translate/news endpoints emptied, SCW suggestions + CookieJarApps SearX removed, recover.php link removed), README/ONLINE/fastlane rewritten, FUNDING/crowdin/stale AAB deleted — pushed master a53e4d3 |
| BOOT01 | Toolchain gate + build sanity, fix build blockers — details below | done | 0 | JDK21+SDK37 present; #321/#324 already moot (deps modernized, no jcenter); real blockers: unescaped `'` in recover_app_lock (8 locales) + mangled %N$s in message_permission_request (es/lt/ja/sr/nl/el) — fixed; jacoco 0.8.6→0.8.13 for JDK21; assembleScMainDebug + testScMainDebugUnitTest exit 0 (2/2 tests pass) |
| FIXA01 | StartPage Mobile search URL broken (upstream #326) — details below | pending | 0 | needs:tool:java |
| FIXA02 | Error pages use null baseUrl with JS on (upstream #328) — details below | done | 0 | ported PR #329: null→https://arktos-error.local at 4 live sites in SmartCookieWebClient + 5 in commented-out SmartCookieKT; build not run (no JDK — deferred to BOOT01); engine-audit PASS |
| FIXA03 | Active tab indistinct in AMOLED theme (upstream #298) — details below | pending | 0 | needs:tool:java |
| FIXA04 | Option to trim protocol/www in URL bar (upstream #278) — details below | pending | 0 | needs:tool:java |
| FIXA05 | Cert info window cut off at large text scale (upstream #277) — details below | pending | 0 | needs:tool:java |
| FIXA06 | Raise text-size maximum (upstream #275) — details below | pending | 0 | needs:tool:java |
| FIXA07 | Follow system dark mode + auto dark-web addon (upstream #251) — details below | pending | 0 | needs:tool:java |
| FIXA08 | Easier new-tab creation in drawer mode (upstream #299) — details below | pending | 0 | needs:tool:java |
| FIXA09 | Swap tabs/bookmarks icon positions (upstream #297) — details below | pending | 0 | needs:tool:java |
| FIXB01 | Downloads correctness: early-stop + .bin fallback + html-instead-of-file (upstream #270/#311/#303) — details below | pending | 0 | needs:tool:java — note: Rust download core lands later (rust lane PORT01), keep Kotlin-side seam clean |
| FIXB02 | Connection-refused replaces tab URL with data: URI after restart (upstream #281) — details below | pending | 0 | needs:tool:java |
| FIXB03 | Tapped URL ignored on first try after app switch (upstream #309) — details below | pending | 0 | needs:tool:java |
| FIXB04 | Cert warning flicker + freeze — dialog loop (upstream #296) — details below | pending | 0 | needs:tool:java |
| FIXB05 | Open links in correct external apps — intent resolution (upstream #316) — details below | pending | 0 | needs:tool:java |
| FIXB06 | Crash on picture links (upstream #306) — details below | pending | 0 | needs:tool:java |
| FIXB07 | App-lock password crash (upstream #302) — details below | pending | 0 | needs:tool:java |
| FIXB09 | Per-site JS/cookie toolbar toggles (upstream #305) — details below | pending | 0 | needs:tool:java |
| FIXB10 | 'No internet connection' message (upstream #323) — details below | pending | 0 | needs:tool:java |
| FIXB11 | Forced SafeSearch for parental controls (upstream #327) — details below | pending | 0 | needs:tool:java |
| FIXB12 | Replace translation provider (upstream #300) — details below | pending | 0 | needs:tool:java — endpoint emptied during rebrand; pick a working public/self-host option or remove the menu entry |
| DOCS01 | Platform-limit issues: document honestly + partial mitigations — details below | done | 0 | landed LIMITATIONS.md (4 platform-limit entries + copy-ready upstream replies) + README Known-limitations section; verified androidx.webkit 1.17.x has no WebRTC IP-handling API; engine-audit PASS; no build needed |
| HOUSE01 | Upstream housekeeping: #168 icon vote, #314 f-droid beta, #315 userscript keyword, #325 proxy-apk question — details below | pending | 0 | mostly closed/answered items; decide Arktos-side equivalents |

## Task details

### ENG01
Engine boundary groundwork for a future selectable-engine effort,
mirroring the desktop repo's `src/engine/engineinterface.h` +
`.devin/ENGINE.md`. Deliverables landed: `engine/BrowserEngine.kt`
(compiled-but-unused Backend/Profile/Page/RequestPolicy sketch),
`.devin/ENGINE.md` coupling map of every `android.webkit.*`
touchpoint, `.devin/check-engine-audit.sh` gate keeping the doc
honest. Verify: `./.devin/check-engine-audit.sh` → PASS.

### REBRAND01
Full rebrand of the SmartCookieWeb fork to Arktos Mobile — package
rename `com.cookiegames.smartcookie`→`com.arktosbrowser.mobile`,
Arktos logo icon set, dead hosted services replaced with neutral
defaults, docs/metadata rewritten. Verify: repo grep sweep shows no
old-domain references outside MPL copyright headers + the external
`smartcookieweb_ytdl` companion-app id.

### BOOT01
Gate: confirm `java`/`javac` on PATH (JDK 11+), `chmod +x gradlew`,
`./gradlew assembleScMainDebug` builds clean, `./gradlew
testScMainDebugUnitTest` passes. Expected blockers: upstream #321
(JCenter-retired deps — permissions-1.1.2, mezzanine-1.1.1,
netcipher-webkit-2.0.0-alpha1 may be stale coordinates in this tree)
and upstream #324 (R8 missing classes → proguard rules or dep
alignment). Fix both. Verify: both gradle commands exit 0.

### FIXA01
`StartPageMobileSearch` URL template redirects to an error page
(upstream #326). Check `search/engine/StartPageMobileSearch.kt`
template against current Startpage mobile endpoint; likely stale
path. Verify: engine produces working query URL; unit test or manual
URL inspection.

### FIXA02
Five interstitial/error pages in `view/SmartCookieKT.kt` pass `null`
as baseUrl to `loadDataWithBaseURL` with JS enabled (upstream #328,
defense-in-depth). Cherry-pick upstream PR #329 (`git fetch
https://github.com/CookieJarApps/SmartCookieWeb pull/329/head` — note
package paths differ post-rebrand) or apply equivalent: stable
non-null baseUrl. Verify: all five call sites non-null, build green.

### FIXA03
AMOLED theme: active tab in drawer lacks contrast (upstream #298).
Find the drawer tab background selector for the black theme and
increase its contrast vs neighbors. Verify: visual diff in drawer on
AMOLED theme; check no other theme regressed.

### FIXA04
Add option to trim protocol + www from URL bar display (upstream
#278). Display-only transform in the location bar; underlying URL
unchanged. Verify: setting toggles display; page loads unaffected.

### FIXA05
Cert info dialog cut off at maximum system text scaling (upstream
#277). Make the SSL/cert dialog scrollable / unconstrained height.
Verify: dialog content fully reachable at max font scale.

### FIXA06
Text-zoom maximum too low (upstream #275). Raise the text-size
seekbar/preference cap (WebView textZoom percent). Verify: values
above old max apply and persist.

### FIXA07
Follow Android dark mode: switch browser theme + dark-web addon when
system uiMode flips (upstream #251). Listen for config uiMode, add
'Follow system' theme option. Verify: theme flips with system; manual
override still works.

### FIXA08
New-tab creation in drawer mode needs multiple steps (upstream #299).
Add a persistent + button/drawer action matching tabs-strip mode.
Verify: one-tap new tab with tabs-in-drawer enabled.

### FIXA09
Option to swap positions of the tabs/bookmarks drawer icons
(upstream #297), mirroring the existing drawer-swap option. Verify:
icons swap; persists across restart.

### FIXB01
Download reliability (upstream #270 partial downloads, #311 .bin
extension, #303 html-instead-of-file). Trace the download path
(`download/` + Mozilla concept-fetch) for content-length/resume and
MIME handling from Content-Disposition/Content-Type. Verify: fixture
server downloads complete, correct extension, no html-saved-as-file.

### FIXB02
After a connection-refused error, the tab's saved URL becomes
`data:text/html;base64,...` on restart (upstream #281). The error
page's data-URI is being persisted as the tab URL. Fix: keep original
URL in tab state when an error page is shown. Verify: blocked-domain
tab survives close/reopen with real URL.

### FIXB03
After backgrounding and returning, the first tapped link/pasted URL is
ignored and the current page reloads instead (upstream #309).
Lifecycle/focus bug in BrowserActivity intent/focus path. Verify:
first input after resume navigates correctly.

### FIXB04
SSL warning flickers repeatedly then freezes/crashes (upstream #296).
Dialog-in-loop on every failed request. Gate to one warning per page
load. Verify: single warning, no ANR.

### FIXB05
Links prompt Play-Store install instead of an installed handler app
(upstream #316). Fix intent resolution order to prefer installed
handlers (see Fulguris third-party-apps approach). Verify: reddit link
offers installed app.

### FIXB06
Crash opening picture-link page (upstream #306 — thesafety.us test
case). Reproduce, get stack trace, fix null/unhandled path. Verify:
page opens without crash.

### FIXB07
App closes when entering app-lock password (upstream #302). Reproduce
password-prompt path, fix crash on unlock. Verify: password unlock
keeps app running.

### FIXB09
Toolbar quick-toggles for per-site JS/cookies (upstream #305). Two
toggle entries in the page/toolbar menu + long-press option. Verify:
toggle flips WebSettings for current origin only.

### FIXB10
Show 'No internet connection' message when device is offline
(upstream #323). Connectivity check ahead of load + distinct error
page variant. Verify: airplane-mode load shows message, not generic
error.

### FIXB11
Parental-controls SafeSearch forced across engines (upstream #327) —
append safe-search params (google `safe=active`, bing `adlt=strict`,
ddg `kp=1`, yandex `family=1`) behind a locked parental setting.
Verify: forced suffix survives URL edits.

### FIXB12
Microsoft web-translator EOL'd; the app POSTs reader-mode text to a
configurable endpoint (currently empty after rebrand). Pick a working
option: public/self-hosted LibreTranslate-compatible endpoint default,
or remove the translate menu entry. Verify: translate succeeds or
entry hidden; no dead button.

### DOCS01
Upstream issues that are Android-WebView platform limits, not fixable
by refactor or language: #310/#301 (WebRTC IP leak — no WebView API),
#313 (per-site timezone — no API; JS-injection partial only), #105
(background play — media-focus hard on WebView), #318 (gitlab login
loop — likely WebView quirk, investigate cheaply). Deliverable:
LIMITATIONS doc section + note in README; revisit only if a WebView
API lands.

### HOUSE01
Housekeeping ports: #168 (icon vote — N/A for Arktos), #314 (beta on
F-Droid/IzzyOnDroid — decide repo strategy), #315 (mention
'userscript' support in README/store text), #325 (manual-proxy APK —
answer pattern is already in-tree).
