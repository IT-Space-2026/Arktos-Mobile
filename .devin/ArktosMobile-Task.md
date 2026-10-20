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
| FIXA01 | StartPage Mobile search URL broken (upstream #326) — details below | done | 1 | startpage.com/do/* retired (302→error page); both StartPage engines → www.startpage.com/sp/search; live curl 200; 3 unit tests + assembleScMainDebug + engine-audit PASS |
| FIXA02 | Error pages use null baseUrl with JS on (upstream #328) — details below | done | 0 | ported PR #329: null→https://arktos-error.local at 4 live sites in SmartCookieWebClient + 5 in commented-out SmartCookieKT; build not run (no JDK — deferred to BOOT01); engine-audit PASS |
| FIXA03 | Active tab indistinct in AMOLED theme (upstream #298) — details below | done | 0 | selectedBackground #0FFF→new selected_black #40FFFFFF on Theme.BaseBlackTheme only (~#0F0F0F→#404040 over black drawer); light/dark untouched; 3 unit tests + assembleScMainDebug + engine-audit PASS |
| FIXA04 | Option to trim protocol/www in URL bar (upstream #278) — details below | done | 0 | display-only `trimmedForDisplay()` in UrlUtils + `trimUrlBarEnabled` pref + switch under Advanced→URL box contents; applied in SearchBoxModel for URL/loading display; 6 unit tests + assembleScMainDebug + engine-audit PASS |
| FIXA05 | Cert info window cut off at large text scale (upstream #277) — details below | done | 1 | ScrollView pinned to screen-minus-chrome height via measured decor bounds (match_parent in unweighted customPanel never constrained); new ssl_dialog_min_content_height dimen; 6 unit tests + assembleScMainDebug + engine-audit PASS |
| FIXA06 | Raise text-size maximum (upstream #275) — details below | done | 1 | new TextZoom ladder object extends index<0 to 250/300/350/400% (old 0..7 indices keep same zoom, default still 3=100%); real percent label + scaled preview in dialog; out-of-range pref values clamp instead of crash; 6 unit tests + assembleScMainDebug + engine-audit PASS |
| FIXA07 | Follow system dark mode + auto dark-web addon (upstream #251) — details below | done | 1 | AppTheme.SYSTEM(3) resolves via uiMode (Context.isNightMode + resolve()); dark-web addon auto-on under system dark via darkWebAddonEnabled(); uiMode added to Main/Incognito configChanges + ThemableBrowserActivity restarts on resolved-theme flip; FORCE_DARK now applied in initializePreferences so it toggles live; all useTheme reads go through resolvedTheme(); 6 unit tests + assemble + engine-audit PASS |
| FIXA08 | Easier new-tab creation in drawer mode (upstream #299) — details below | done | 0 | persistent new_tab_button added to toolbar_content (default gone, shown when showTabsInDrawer) wired to newTabButtonClicked/LongClicked in BrowserActivity; one-tap + beside overflow — assembleScMainDebug + 5 DrawerNewTabButtonTest tests + engine-audit PASS |
| FIXA09 | Swap tabs/bookmarks icon positions (upstream #297) — details below | done | 0 | bookmarksAndTabsIconsSwapped pref + cb_swapicons switch in drawer settings; bookmarks_button (star, gone-by-default) at toolbar start, shown + home_button moved next to overflow when on; bottom nav uses new bottom_menu_swapped.xml; activity restarts on toggle; 5 DrawerIconSwapTest tests + assembleScMainDebug + engine-audit PASS |
| FIXB01 | Downloads correctness: early-stop + .bin fallback + html-instead-of-file (upstream #270/#311/#303) — details below | done | 1 | DownloadHttpProbe (HEAD→GET, redirects, UA/cookie/referer) + DownloadNaming (CD filename*/filename, last-dot ext, mime→ext map — no more .bin) + DownloadCompleteReceiver re-enqueues truncated/retryable-failed (≤3 attempts, meta persisted in prefs) + DM request now sends UA/Referer/Cookie & final resolved URL; html response → .html name + warning toast; dead FetchUrlMimeType deleted, downloads recorded to repo (was never wired); 30 unit tests incl. HttpServer fixture + assembleScMainDebug + engine-audit PASS |
| FIXB02 | Connection-refused replaces tab URL with data: URI after restart (upstream #281) — details below | done | 1 | errorPageOriginalUrl tracked at all 5 loadDataWithBaseURL sites; SmartCookieView.url resolves data:/arktos-error.local/about:blank committed URLs back to the real URL; saveState writes URL_KEY for error tabs, restore + undo-close load it (no data: persisted); 7 unit tests + assembleScMainDebug + engine-audit PASS |
| FIXB03 | Tapped URL ignored on first try after app switch (upstream #309) — details below | done | 1 | "; wv" UA arms race: onPageStarted stripped marker mid-navigation (setUserAgentString change reloads last-committed entry → eats pending nav) while initializePreferences re-added it every resume; strip now applied once in UserPreferences.userAgent so value is stable — 5 unit tests + assembleScMainDebug + engine-audit PASS |
| FIXB04 | Cert warning flicker + freeze — dialog loop (upstream #296) — details below | done | 0 | SslWarningGate (one prompt per page load; concurrent errors auto-cancel, answer covers rest of load, reset in onPageStarted); stale dialog cancelled w/ pending handler on new load; toast no longer crashes on empty error-code list; 6 SslWarningGateTest tests + assembleScMainDebug + engine-audit PASS |
| FIXB05 | Open links in correct external apps — intent resolution (upstream #316) — details below | done | 0 | ExternalAppLaunchPolicy ordering pinned→other installed handler→browser_fallback_url→market→browser; intent:// now routed via startActivityForUrl (was raw startActivity swallowing failures); unpinned launch requires host-matching filter so links can't loop back into Arktos; 8 unit tests + assembleScMainDebug + engine-audit PASS |
| FIXB06 | Crash on picture links (upstream #306) — details below | done | 0 | target=_blank popup path: NewWindowTransport.isDeliverable pre-checks resultMsg.obj; SmartCookieChromeClient declines (returns false → link loads in current window) on bad transport or tab-creation failure instead of unchecked-cast crash; ResultMessageInitializer safe-cast; UIController.onCreateWindow→Boolean; updateSslState url!! NPE fixed; 5 unit tests + assembleScMainDebug + engine-audit PASS |
| FIXB07 | App-lock password crash (upstream #302) — details below | done | 0 | mistyped password killed app via finishAffinity w/ no feedback; AppLockGate (exception-safe verify seam) + dialog now keeps prompt open w/ wrong_password error + field clear, Cancel still exits; 5 unit tests + assembleScMainDebug + engine-audit PASS |
| FIXB09 | Per-site JS/cookie toolbar toggles (upstream #305) — details below | done | 0 | SiteOverrides host-keyed override sets + SiteSettings (global+legacy JS list folded via JavaScriptSiteList); onPageStarted applies effective JS + CookieManager per origin; toggles in popup menu + long-press link dialog; 14 unit tests + assembleScMainDebug + engine-audit PASS |
| FIXB10 | 'No internet connection' message (upstream #323) — details below | done | 0 | ConnectivityGate (ConnectivityManager probe + pure isNetworkPageRequest/isOfflineError seams); offline main-frame http/https requests intercepted in shouldInterceptRequest → dedicated error page; onReceivedError swaps to offline title/message when no route or connectivity-loss description; generated/file/about pages never intercepted; 6 ConnectivityGateTest tests + assembleScMainDebug + engine-audit PASS |
| FIXB11 | Forced SafeSearch for parental controls (upstream #327) — details below | done | 0 | SafeSearch.enforcedUrl (google safe=active / bing adlt=strict / ddg kp=1 / yandex family=1; replaces non-strict values) enforced in SmartCookieView.loadUrl + shouldOverrideLoading + onPageStarted backstop; safeSearchForced pref + switch on password-gated parental screen; 8 SafeSearchTest tests + assembleScMainDebug + engine-audit PASS |
| FIXB12 | Replace translation provider (upstream #300) — details below | done | 0 | LibreTranslate-compatible default (libretranslate.de, pref-configurable); in-page translation via Rust wire format + TranslateBridge JS interface (added on-demand only) + TranslatePage.js DOM walker; ReadingActivity selected-text path rewired to same Rust builders — cargo 7/7, TranslateBridgeTest 6/6 |
| DOCS01 | Platform-limit issues: document honestly + partial mitigations — details below | done | 0 | landed LIMITATIONS.md (4 platform-limit entries + copy-ready upstream replies) + README Known-limitations section; verified androidx.webkit 1.17.x has no WebRTC IP-handling API; engine-audit PASS; no build needed |
| HOUSE01 | Upstream housekeeping: #168 icon vote, #314 f-droid beta, #315 userscript keyword, #325 proxy-apk question — details below | done | 0 | #168 N/A (own icon set); #314 decided: GitHub Releases + in-repo fastlane metadata (README); #315 userscript + proxy bullets in README + fastlane full_description; #325 answer = built-in Settings→General→HTTP proxy (ProxyUtils/netcipher); decision record + userscript @require caveat in details; docs-only, engine-audit PASS |
| UI01 | Error/interstitial pages styled like Arktos desktop — details below | done | 0 | desktop notfound/certerror card ported into rust pages.rs (#box/#header/#content, gradient band, floated arktos.webp, h2 divider + hint <ul>, pill buttons); new hint params + error_page_hint/page_blocked_hint strings wired at all call sites; arktos-dark class emitted off non-empty color (fragment de-!importanted); reloadCode/$!!!/showButton-proceed contracts kept — cargo 120/120, 211 unit tests 0 fail, assembleScMainDebug + engine-audit PASS |
| UI02 | Start page styled like Arktos desktop (no search bar) — details below | done | 0 | aurora hero+gradient ported into homepage.html/private.html (+4 svg assets), search box dropped, tile/modal/localStorage contract kept; arktos-dark class theming in both factories; fixed upstream incognito.html→private.html filename bugs (reader + onPageFinished) — StartPageTest 10/10 + assembleScMainDebug + engine-audit PASS |
| LICENSE01 | Sync in-app license list + README third-party/structure docs | done | 0 | removed stale snacktory entry (dep gone); added sora-editor LGPL-2.1, OkHttp/Apache, vendored I2P helper/Apache, UniFFI MPL-2.0, Rust crates MIT+Apache; README gained Source structure + Third-party components sections (desktop style), Acknowledgements kept — assembleScMainDebug PASS |
| J2K01 | Convert remaining .java files to Kotlin — details below | done | 0 | 11 files converted (9 utils/exporter + Vertical/HorizontalItemAnimator); 0 .java files remain; 232 unit tests 0 fail, assembleScMainDebug + engine-audit PASS | needs:tool:java — 12 files left (animators, utils glue); MurmurHash/WebAddress already went to Rust on rust-work |
| RENAM01 | Rename residual SmartCookie* identifiers to Arktos* — details below | done | 0 | SmartCookieView/WebClient/ChromeClient/ViewTitle → ArktosCookie* (files, classes, fields, TAG/log strings, proguard keep rule); ENGINE.md + LIMITATIONS.md paths, rust provenance comments; upstream SmartCookieWeb citations + external smartcookieweb_ytdl id and legacy-domain probes kept — ArktosCookieRenameTest 4/4, 225 unit tests 0 fail, assembleScMainDebug + engine-audit PASS |
| CHLOG01 | Changelog: repo file + in-app page — details below | done | 0 | root ChangeLog (desktop format) + assets/changelog.html (aurora style, arktos-dark) + arktos://changelog chrome URL served from assets + Settings→About entry + one-shot on versionCode bump — cargo 122/122, ChangelogPageTest 7/7 (235 total 0 fail), assembleScMainDebug + engine-audit PASS |
| SEC01 | Browser-wide app lock: PIN/password/biometric + re-lock timer — details below | done | 0 | AppLockMethod enum (NONE/PIN/PASSWORD/BIOMETRIC) + appLockRelockMinutes pref (immediate/1/5/10/custom) + AppLockState monotonic background clock via BrowserApp activity-count callbacks; gate in BrowserActivity onCreate+onResume, biometric via androidx.biometric:biometric:1.1.0; legacy passwordLock=custom migrated to PASSWORD; Privacy settings method + re-lock pickers — AppLockStateTest 7/7 |
| SEC02 | Password-encrypted in-app backup + plaintext DB excluded from Auto Backup — details below | done | 0 | BackupCrypto (PBKDF2-600k → AES-256-GCM, versioned ARKBAK blob, JVM-testable) + Encrypted backup/Restore rows in Import-Export settings reusing typed-settings JSONL + legacy bookmark importer; database domain dropped from backup_rules/data_extraction_rules — BackupCryptoTest 6/6 |
| SEC03 | Strict mixed-content in normal browsing — details below | done | 0 | MixedContentPolicy.mode() → NEVER_ALLOW default everywhere (incognito always strict); blockMixedContentEnabled pref (default on) + Privacy→Security switch relaxes normal browsing to COMPATIBILITY_MODE for legacy sites — MixedContentPolicyTest 4/4, assembleScMainDebug + engine-audit PASS |
| SEC04 | HTTPS-only mode toggle with warning interstitial — details below | done | 0 | forceHTTPS now strict: cleartext main-frame navs answered in shouldInterceptRequest (no bytes leak) + shouldOverride/onPageStarted backstops; new Rust build_https_warning_page (Use HTTPS/Go back/Proceed); proceed via arktos://insecure-allow marker gated on shown warning → session host consent; preferHTTPS keeps upgrade-once — cargo 132/132, HttpsOnlyGateTest 7/7 (260 total), assembleScMainDebug + engine-audit PASS |
| SEC05 | Migrate TranslateBridge off addJavascriptInterface — details below | done | 0 | WebViewCompat.addWebMessageListener `ArktosTranslate` postMessage object registered at tab setup (frame injection needs page load); TranslatePage.js reworked to async id-correlated batch postMessage/onmessage; reply via JavaScriptReplyProxy on main scheduler, OkHttp call on network scheduler; always-replies so walker can't stall; test dep org.json for JVM tests — TranslateBridgeTest 14/14 (incl. HttpServer round-trip), 268 unit tests 0 fail, assembleScMainDebug + engine-audit PASS |
| SEC06 | Enforce userscript @connect for GM_xmlhttpRequest — details below | done | 0 | Rust parser now captures @connect → new javascript.connects column (ALTER-migrated) → JavaScriptEntry; GmConnectPolicy (same-host always, */self/domain+subdomains, URL/port-normalized) + per-script policy map gates ALL GM bridge ops on installed-script + include/exclude origin match — spec's "parser already captures" was wrong, plumbed end-to-end; cargo 133/133, 278 unit tests 0 fail (GmConnectPolicyTest 9/9), assembleScMainDebug + engine-audit PASS |
| SEC07 | FLAG_SECURE for incognito + optional whole-app toggle — details below | done | 0 | SecureWindowPolicy (incognito always secure; regular windows iff secureWindowsEnabled) applied in BrowserActivity.onCreate pre-setContentView + BrowserApp onActivityCreated callback for every app window; secure_windows switch in Privacy→Security — SecureWindowPolicyTest 4/4, 282 unit tests 0 fail, assembleScMainDebug + engine-audit PASS |
| SEC08 | Tapjacking protection on sensitive dialogs — details below | done | 0 | TapjackingGuard (Surface enum + filterTouchesWhenObscured on decorView, S+ setHideOverlayWindows) applied to app-lock unlock/biometric-retry, parental gate + password set, credential set, manual proxy, download-confirm, backup password dialogs; showCustomDialog gained surface param — TapjackingGuardTest 4/4, assembleScMainDebug + engine-audit PASS |
| SEC09 | Audit exported components + incoming intent validation — details below | done | 0 | IncomingIntents.extractUrl gates cold-start + warm intent URLs (was: raw dataString on cold start); PROCESS_TEXT now handled; import/export-tabs moved off intent extras to in-process PendingTabTransfer (forged-extra tab-list leak closed); manifest kept: MainActivity+DownloadCompleteReceiver need export, rest already internal — 12 new tests, assembleScMainDebug + engine-audit PASS |
| SEC10 | Release R8/minify + resource shrinking — details below | done | 0 | shrinkResources true in release (minifyEnabled already on); added JNA/UniFFI keep rules + java.awt dontwarn (broad JNA keep pulled Native$AWT refs); APK has libarktos.so ×3 ABIs, 181 uniffi + 128 jna classes identity-mapped — ReleaseBuildConfigTest 3/3, assembleScMainRelease + assembleScMainDebug + engine-audit PASS |
| SEC11 | Download risk warnings (executable ext, MIME mismatch) — details below | pending | 0 | — |
| SEC12 | IDN/homograph punycode warning in URL bar — details below | pending | 0 | — |
| SEC13 | Referrer-trim privacy toggle — details below | pending | 0 | — |
| SEC14 | CI security gates: dep scan + secret scan + manifest lint — details below | pending | 0 | — |
| SEC15 | Encrypted store at rest (SQLCipher / keystore-wrapped key) — details below | pending | 0 | — |
| SEC16 | Reproducible/verified build investigation — details below | pending | 0 | — |

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

Outcome / decision record (2026-10-19):

- #168 (Icon Voting Round 2): upstream ran a community vote for a new
  default launcher icon; closed/locked Jan 2021. N/A for Arktos —
  REBRAND01 already shipped a new Arktos icon set in all
  densities/flavors. No action.
- #314 (beta on F-Droid/IzzyOnDroid): decided — GitHub Releases stays
  the distribution channel; fastlane metadata
  (`fastlane/metadata/android/en-US/`: title, short+full description,
  icon) is kept in-repo so the package already meets the
  F-Droid/IzzyOnDroid metadata prerequisite. Actual store listing is a
  maintainer/packaging action (fdroiddata RFP / IzzyOnDroid ticket),
  not a code change. Documented in README Installation section.
- #315 ('userscript' keyword): real feature — `.user.js` pages prompt
  an install dialog (SmartCookieWebClient.installExtension → Rust
  `ArktosCore.parseUserScript` → JavaScriptRepository/ArktosStore),
  scripts inject on `@include` regex match in `onPageCommitVisible`
  and are removable under Extensions settings. Added explicit
  'userscript' wording to README Features + fastlane
  full_description.txt. Caveat found while verifying (follow-up
  candidate, not fixed here): `onPageCommitVisible` only evaluates the
  stored script body inside the `!i.requirements.isNullOrEmpty()`
  branch — a script with `@include` but no `@require` installs but
  never runs. Fix = hoist the `i.code` evaluation out of the
  requirements branch; needs a test seam first since the block is
  WebView-bound.
- #325 (manual-proxy APK): answer pattern already in-tree and
  verified — Settings → General → HTTP proxy offers None / Orbot
  (localhost:8118) / I2P (localhost:4444) / Manual host:port
  (dialog_manual_proxy.xml), applied via ProxyUtils + netcipher
  `WebkitProxy.setProxy` in SmartCookieView. No special APK needed; a
  fork wanting proxy-on-by-default only needs to default
  `UserPreferences.proxyChoice` to `MANUAL` (the upstream poster's
  real bug was a transient upstream break, since reverted).
  Documented in README Features + fastlane description.

Verify: docs-only (no code change → no new tests; no build needed);
`./.devin/check-engine-audit.sh` PASS.

### UI01
Restyle the WebView error/interstitial pages to match the Arktos
desktop error pages.

- Source of truth (desktop repo, arora worktree):
  `src/htmls/notfound.html`, `src/htmls/certerror.html`.
- Mobile entry points: `Utils.buildErrorPage()` +
  `Utils.buildBlockPage()` in
  `app/src/main/java/com/arktosbrowser/mobile/utils/Utils.kt` —
  they emit inline-HTML strings loaded via
  `loadDataWithBaseURL("https://arktos-error.local", ...)`
  (stable baseUrl from FIXA02 — keep it).
- Desktop design to port: white rounded card (`#box`,
  border-radius 0.8em, subtle shadow, max-width ~640px, centered),
  blue gradient header band (`#228 → #66a`) with the Arktos logo
  floated right, error list under a thin divider, small
  suggestion text.
- Logo asset: add `arktos.svg`/`arktos.webp` reference via
  file:///android_asset (arktos.webp already shipped in assets).
- Dark mode: desktop tags `<html class="arktos-dark">` when the
  forced dark palette is active — replicate by emitting the dark
  CSS variant when `userPreferences.useTheme != AppTheme.LIGHT`
  (callers already know the theme via the `color` param).
- Do NOT break the `reloadCode` callback contract or the
  `showButton`/proceed-button logic — only restyle.
- Verify: `./gradlew assembleScMainDebug`; unit test asserting the
  generated HTML contains the card structure + still contains
  $title/$error substitution + reload JS hook.

### UI02
Restyle the in-app start/home page to match the Arktos desktop
startpage — WITHOUT the centered search bar (mobile already has the
URL bar).

- Source of truth (desktop repo, arora worktree):
  `src/htmls/startpage.html`, `startpage.css`, plus assets
  `aurora-wave.svg`, `aurora-wave-private.svg` (incognito variant),
  `arktos-plain.svg`.
- Mobile entry points: `app/src/main/assets/homepage.html`,
  `private.html` (+ `HomePageFactory`/`IncognitoPageFactory` fill
  `${TITLE}` etc.).
- Port: aurora-wave band across the top (~240px hero stage), the
  Arktos logo cresting the wave (~170px, centered), the header
  title, and the page gradient (radial cyan/purple aurora glow over
  light gradient). Copy the wave/logo SVGs into
  `app/src/main/assets/`.
- Remove the page's own search box (#search_input/.search_bar) —
  redundant with the toolbar URL bar.
- Keep the customizable link tiles (localStorage link1..linkN +
  `shouldUpdate` flow in SmartCookieWebClient.onPageFinished) —
  render them under the hero in the same card style as UI01.
- Incognito page (`private.html`) uses `aurora-wave-private.svg`
  and dark palette.
- Verify: `./gradlew assembleScMainDebug`; homepage + incognito
  render without JS errors; link-tile save/read still works;
  engine-audit PASS.

Outcome / notes (2026-10-19):

- Ported `aurora-wave.svg`, `aurora-wave-private.svg`,
  `arktos-plain.svg`, `private-sunglasses.svg` from the desktop
  worktree into `app/src/main/assets/`; both pages reference them via
  `file:///android_asset/` (generated pages live under filesDir, so
  relative asset URLs would not resolve).
- homepage.html: `#aurora` 240px wave stage + 170px `image_url` logo
  (id kept — `FOCUSED` homepage type still removes it), gradient
  `#header` card, link tiles as a rounded card, news `.card`s kept for
  `INFORMATIVE`, `html.arktos-dark` palette replacing the factories'
  appended `<style>` override (now `tag("html"){addClass}` in both
  factories). Search box, `search()`/`${BASE_URL}`/`${IMAGE}` removed.
- private.html: navy palette + private wave + `#privatelogo`,
  `title-pm`/`desc-pm` kept; dropped the obfuscated hex-escape
  cookie.info/SmartCookieWeb easter-egg blob from save.onclick and the
  dead i.stack.imgur.com favicon fallback.
- Fixed two upstream filename bugs blocking the incognito page:
  `IncognitoPageReader` opened assets `incognito.html` (asset is
  `private.html` — broken since the mezzanine→assets migration) and
  `SmartCookieWebClient.onPageFinished` checked
  `/files/incognito.html`, so link-var injection never ran for the
  private page. Both now use `private.html`; `shouldUpdate`
  write-back stays homepage-only as upstream.
- Verified: jsoup simulation of the factory pipeline (first-script
  `${ENDPOINT}` substitution + `&`→`u0026` escaping, arktos-dark
  class, all contract ids survive outerHtml); StartPageTest 10/10;
  assembleScMainDebug + testScMainDebugUnitTest green; engine-audit
  PASS.

### J2K01
Convert the remaining `.java` sources to Kotlin — target zero
`.java` files under `app/src/main`.

- Remaining files (12): `list/HorizontalItemAnimator.java`,
  `list/VerticalItemAnimator.java`, `utils/ThemeUtils.java`,
  `utils/ProxyUtils.java`, `utils/FileUtils.java`,
  `utils/IntentUtils.java`, `utils/StringBuilderUtils.java`,
  `utils/DrawableUtils.java`, `utils/MemoryLeakUtils.java`,
  `utils/WebUtils.java`,
  `database/bookmark/BookmarkExporter.java`,
  `download/FetchUrlMimeType.java`.
  All are platform-bound (Android views/intents/files/drawables) —
  convert, do NOT redesign. MurmurHash + WebAddress already moved
  to Rust on the rust-work branch — if a file on this list is
  already gone/ported when the task runs, skip it and note it.
- Method: Android Studio-style Java→Kotlin conversion semantics —
  static utility classes become `object`s, keep public API
  signatures so Kotlin call sites don't churn (check
  `@JvmStatic`-style usage patterns already in the codebase).
- If a file turns out to be dead code on this branch, delete it
  instead of converting.
- Verify: `find app/src/main -name "*.java"` → empty (or only
  files justified in Notes), `./gradlew assembleScMainDebug` +
  `testScMainDebugUnitTest` green, engine-audit PASS.

### RENAM01
Rename the remaining `SmartCookie*` type/file identifiers to `Arktos*`
(user request: `SmartCookieView.onDestroy` should read
`ArktosCookieView.onDestroy`).

- Identifiers/files to rename (class + file + all references):
  `SmartCookieView` → `ArktosCookieView`,
  `SmartCookieWebClient` → `ArktosCookieWebClient`,
  `SmartCookieChromeClient` → `ArktosCookieChromeClient`,
  `SmartCookieViewTitle` → `ArktosCookieViewTitle`.
- Sweep for other `SmartCookie[A-Za-z]*` identifiers (variables,
  comments-as-identifiers) and rename consistently; e.g. a
  `smartCookieView` field becomes `arktosCookieView`.
- DO NOT touch upstream citations in comments — strings like
  "upstream SmartCookieWeb #311" or "SmartCookieWeb fork" that refer
  to the original project stay; they are attribution, not branding.
  `SmartCookieWeb` as a project name in prose/docs also stays.
- Update `.devin/ENGINE.md` touchpoint paths that name the renamed
  files so the engine audit still maps correctly.
- Verify: `grep -rn "SmartCookie[A-Za-z]*" --include="*.kt"` shows no
  identifiers (only prose citations), `./gradlew assembleScMainDebug`
  + `testScMainDebugUnitTest` green, engine-audit PASS.

### CHLOG01
Changelog, user-visible in-app (user request: "create a changelog
like the arktos desktop, available also in the app").

- Create root `ChangeLog` in the desktop repo's format: version
  headers (`x.y — title`) + prose bullets. Seed it from WORKLOG and
  git history (rebrand, upstream issue fixes by number, rust core,
  JS/translation work). This file is the source of truth.
- Create `app/src/main/assets/changelog.html` rendering the same
  entries, styled like the Arktos pages — aurora-wave header +
  `arktos-plain.svg` logo (reuse the UI02 asset style once landed,
  or port `src/htmls/startpage.css` look directly). Keep both files
  in sync; note the two-file convention in ChangeLog's header
  comment or a CONTRIBUTING note.
- Wire it into the app: clickable `changelog` Preference in
  `preference_about.xml` under the About category → opens
  `file:///android_asset/changelog.html` in a new tab (same
  mechanism as homepage/incognito/downloads pages — check how
  `loadHomepage`/downloads page load and mirror it).
- Nice-to-have, only if it lands cleanly:
  `arktos://changelog` recognized by the special-URL system
  (`isSpecialUrl`/`pages.rs`), and a one-shot "what's new" — on
  first launch after a versionCode bump, open the changelog tab
  once (store last-seen versionCode in prefs; never re-show).
- Verify: `./gradlew assembleScMainDebug` + `testScMainDebugUnitTest`
  green, engine-audit PASS, changelog opens from Settings → About.

Outcome / notes (2026-10-19):

- Both nice-to-haves landed: `arktos://changelog` is the canonical
  URL (no local-file warning dialog like a `file://` VIEW intent
  would trigger) and the version-bump one-shot is wired.
- Root `ChangeLog` in desktop format (`17.0 — Arktos Mobile: first
  release`, grouped prose bullets seeded from WORKLOG); header notes
  the two-file sync convention with the asset page.
- `app/src/main/assets/changelog.html` mirrors the entries; aurora
  hero + header card reused from homepage.html; artwork referenced as
  `arktos://changelog/<asset>` so it resolves from any origin (the
  page also renders via `file:///android_asset/changelog.html`).
- Serving: `ArktosCookieWebClient.changelogResponse` intercepts
  `arktos://changelog[/]` → assets/changelog.html with the
  `arktos-dark` class baked at serve time via `ChangelogPage
  .applyDarkTheme` (JS-free dark), and `arktos://changelog/<flat
  name>` → bundled asset (mime via Rust `mime_type_for_extension`).
  Legacy `shouldInterceptRequest` overload covered too.
- Rust seams: `is_changelog_url` (arktos://changelog + file://*
  changelog.html → both special), `changelog_asset_name` (flat
  `[A-Za-z0-9._-]+` names only — rejects `/`, `\`, `.`/`..`,
  %-escapes), `arktos` added to intent-scheme allowlist +
  accepted-scheme prefixes (URL-bar input loads, doesn't search),
  blocklist `is_internal_url` exempts `arktos://`.
- Open paths: Settings → About → Changelog (and the version row —
  replaces the stale "SCW v… New settings page" dialog) fires an
  in-package ACTION_VIEW → `arktos` VIEW intent-filter added →
  BrowserPresenter.onNewIntent opens a normal tab (back → settings).
- One-shot: `ChangelogGate.shouldShow` +
  `UserPreferences.lastChangelogVersionCode`, gated by the existing
  `whatsNewEnabled` pref and regular-mode only (incognito process
  must not race the pref write); foreground tab on plain launches,
  background when the launch carried an external URL; skipped when a
  restored session already holds the changelog tab.
- Verified: cargo 122/122, ChangelogPageTest 7/7 (235 unit tests,
  0 failures), assembleScMainDebug + testScMainDebugUnitTest green,
  engine-audit PASS. Manual click-through not possible (no device);
  intent→tab path follows the existing HistoryActivity
  VIEW+setPackage pattern.

### SEC01
Browser-wide app lock (user request): PIN, password, or biometric
authentication to open the browser, separate from the parental-control
lock. Re-lock timing: immediate, 1 min, 5 min, 10 min, or a custom
minute count.

Landed: `AppLockMethod` enum + `appLockMethod`/`appLockRelockMinutes`
prefs (legacy `passwordLock=custom` migrates to PASSWORD, keeping the
argon2id hash), `AppLockState` monotonic (elapsedRealtime) background
clock driven by a started-activity counter in `BrowserApp`, the gate
in `BrowserActivity` onCreate + onResume, BiometricPrompt via
androidx.biometric:biometric:1.1.0 (BIOMETRIC_STRONG|DEVICE_CREDENTIAL
on API 30+), method + re-lock pickers on the Privacy screen.

Outcome notes:
- Incognito runs in its own process → its own AppLockState, so it
  re-prompts independently.
- Unlock state is process-lifetime only; process death always re-locks.
- Recovery: the existing "clear app data to reset" string still applies —
  there is deliberately no recovery bypass.

### SEC02
Encrypted backup (user request): "if it goes to backup you can restore
if you have the password". Android Auto Backup copies files verbatim
and cannot run a password prompt, so two moves were made:

1. `database` domain removed from `backup_rules.xml` +
   `data_extraction_rules.xml` — arktos.db (history, bookmarks,
   downloads, userscripts) no longer ships plaintext to the cloud /
   device-transfer. Shared prefs stay included (settings + argon2id
   hashes only, no content).
2. In-app password-protected backup: `BackupCrypto` (utils/) seals a
   `{"v":1,"settings":<jsonl>,"bookmarks":<jsonl>}` payload with
   PBKDF2WithHmacSHA256 (600k iters) → AES-256-GCM; `ARKBAK`+version
   magic. Two rows on the Import/Export screen: "Encrypted backup"
   (SAF create + confirm-password dialog) and "Restore encrypted
   backup" (SAF open + password dialog). Wrong password → GCM tag
   failure → generic error. Restore merges settings (no wipe).

Outcome notes:
- The password is never persisted; there is no recovery for a
  forgotten backup password.
- Follow-on: SEC15 encrypts the live store at rest, which would let
  Auto Backup carry ciphertext again.

### SEC03
Normal browsing still uses `MIXED_CONTENT_COMPATIBILITY_MODE`
(`view/ArktosCookieView.kt`); only incognito uses NEVER_ALLOW. Switch
normal mode to `MIXED_CONTENT_NEVER_ALLOW`, or add a privacy switch if
compat mode is still needed for legacy sites. Engine touchpoint →
update ENGINE.md. needs:tool:java

### SEC04
Add an HTTPS-only mode: `forceHTTPSenabled` currently upgrades
opportunistically; a strict mode should block cleartext http
navigations with a warning/interstitial page (the UI01 error-page
infra can host it) rather than silently upgrading. WebView touchpoint
→ ENGINE.md. needs:tool:java

### SEC05
`translate/TranslateBridge.kt` exposes `addJavascriptInterface` to
pages. Migrate to `WebViewCompat.addWebMessageListener` +
`WebMessagePort` like the userscript GM bridge, so no JS-interface
object is reachable from page JavaScript. WebView touchpoint →
ENGINE.md. needs:tool:java

### SEC06
`GM_xmlhttpRequest` in the userscript engine reaches any host through
the proxy-aware OkHttp client. Enforce the userscript's declared
`@connect` domains; scripts without any `@connect` should get no
cross-origin XHR (or an install-time warning). Rust side: the parser
already captures @connect — the check belongs in the Kotlin GM bridge
host filter. needs:tool:java

### SEC07
`FLAG_SECURE` on the incognito activity window (blocks screenshots and
recents thumbnails of private pages) plus an optional whole-app toggle
in Privacy settings. Apply in onCreate before setContentView; keep it
off in regular mode by default so screenshots keep working.
needs:tool:java

### SEC08
Tapjacking: set `filterTouchesWhenObscured` (or FLAG_SECURE) on the
password/lock dialogs, proxy host dialog, and download-confirm dialogs
so overlay apps can't inject taps. needs:tool:java

### SEC09
Audit every `android:exported="true"` component in the manifest:
confirm each really needs to be reachable, validate incoming
actions/extras (open-url intents, panic trigger), and drop exported
where internal-only. needs:tool:java

Audit result (this run):
- `MainActivity` stays exported — required for LAUNCHER plus the
  BROWSABLE VIEW / WEB_SEARCH / PROCESS_TEXT / panic TRIGGER filters.
  Fixes landed: cold-start `TabsManager.initializeTabs` used to feed
  raw `intent.dataString` into `UrlInitializer` (a VIEW intent with
  `javascript:`/`data:` bypassed the allowlist that only
  `onNewIntent` applied) — both entry points now share
  `IncomingIntents.extractUrl` (query extras run through
  smartUrlFilter, everything is scheme-allowlisted);
  `ACTION_PROCESS_TEXT` was declared but dropped on the floor, now
  handled via `EXTRA_PROCESS_TEXT` like a search query;
  `EXTRA_IMPORT_TABS` / `EXTRA_EXPORT_TABS_URI` were internal-only
  payloads riding the exported activity's extras — any app could
  forge them (export URI = open-tab-list exfil to an attacker
  provider, import = scheme-unfiltered tab opens) — moved to the
  in-process `PendingTabTransfer` handoff; panic TRIGGER stays
  externally triggerable by design (panic-button apps are the whole
  point of the filter); `INTENT_ORIGIN` retargeting accepted
  (unguessable tab hash, URL already scheme-validated).
- `DownloadCompleteReceiver` stays exported — `DOWNLOAD_COMPLETE` is
  a protected broadcast delivered by the platform download provider
  (not the system uid), so a non-exported receiver would never see
  it; `onReceive` re-verifies everything against `DownloadManager`
  queries and the local meta store regardless.
- `IncognitoActivity` / `SettingsActivity` / `ReadingActivity` were
  already `exported="false"` (their custom actions only resolve
  in-app); `DownloadActivity` / `HistoryActivity` have no filters →
  implicitly non-exported; `FileProvider` non-exported with
  grantUriPermissions.
- Library-merged surface (not ours to unexport):
  `androidx.profileinstaller.ProfileInstallReceiver` is
  permission-gated (signature `android.permission.DUMP`);
  `androidx.compose.ui.tooling.PreviewActivity` is a stub from the
  compose tooling dep; LeakCanary components are debug-only.

### SEC10
Check release buildType: enable `minifyEnabled` (R8) +
`shrinkResources` if absent; keep the existing proguard keep rules
(JNA/uniffi). Verify a release build still runs the Rust core.
needs:tool:java

### SEC11
Download risk warnings: flag executable/scriptable extensions
(.apk/.exe/.js/.bat/.msi etc.) and MIME-vs-extension mismatches before
the download is enqueued; the FIXB01 probe already detects
html-served-as-file — extend that pattern. needs:tool:java

### SEC12
IDN/homograph protection: detect confusable Unicode hosts at
display-time and show punycode (or a warning badge) in the URL bar —
Rust-side `idna`/confusable check would fit the port pattern.
needs:tool:java

### SEC13
Referrer control: toggle to strip/trim Referer on top-level
navigations (same-origin only, or full strip). WebView can't set
Referrer-Policy directly — apply at shouldOverrideUrlLoading/
loadUrl headers where supported. WebView touchpoint → ENGINE.md.
needs:tool:java

### SEC14
CI security gates in `.github/workflows/android.yml`: dependency
vulnerability scan (osv-scanner or Dependabot config for Gradle +
Cargo.lock), secret scan (gitleaks/trufflehog), and an
exported-component/permission lint step (or reuse
check-engine-audit.sh as the gate pattern). needs:tool:java only if
verification builds are run.

### SEC15
Encrypted store at rest: arktos.db is plaintext SQLite today — anyone
with the file reads full history/bookmarks. Options: SQLCipher via
rusqlite `bundled-sqlcipher-vendored-openssl` (key = argon2id of the
app-lock credential; fallback Keystore-wrapped random key when no
password lock), or field-level AES-GCM on sensitive columns. When this
lands, Auto Backup can re-include the database domain because the
backup itself would be ciphertext. needs:tool:java, needs:tool:cargo-ndk

### SEC16
Reproducible/verified builds: pin toolchain versions, lockfile
verification (Cargo.lock + Gradle verification-metadata), strip
nondeterminism (build timestamps), document the recipe — groundwork
for F-Droid inclusion.
