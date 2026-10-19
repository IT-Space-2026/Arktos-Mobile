# ENGINE.md — engine-portability coupling map (mobile, ENG-M01)

Mirrors `.devin/ENGINE.md` in IT-Space-2026/arktos (desktop). Groundwork
for a possible selectable-engine effort: the Android UI shell stays;
the web engine underneath could become selectable per tab —
**Android WebView today, Servo as a future candidate.**

**No refactor has been done.** `app/src/main/java/com/arktosbrowser/mobile/engine/BrowserEngine.kt`
is the compiled-but-unused interface sketch — the Kotlin counterpart
of desktop's `src/engine/engineinterface.h`. Nothing references the
`engine` package yet; the sketch exists only to pin the boundary.

## Backends

| # | Engine | Status |
|---|--------|--------|
| 0 | Android WebView | the only backend today |
| 1 | Servo | not started — desktop ENG03 spike verdicts carried below; Android embedding viability is a separate open question (EGL surface, NDK lifecycle, no WebView-compat API) |

## Adapter boundary (where WebView types are allowed to live today)

Every file importing `android.webkit.*` is engine-coupled by
definition. A future refactor splits each into a neutral core + thin
WebView adapter:

- `app/src/main/java/com/arktosbrowser/mobile/view/SmartCookieView.kt` — tab object, WebView lifecycle, settings apply.
- `app/src/main/java/com/arktosbrowser/mobile/view/SmartCookieWebClient.kt` — WebViewClient: interception (`shouldInterceptRequest` → adblock/malware/siteblock seam), errors, downloads.
- `app/src/main/java/com/arktosbrowser/mobile/view/SmartCookieChromeClient.kt` — WebChromeClient: JS dialogs, file chooser, permissions, fullscreen.
- `app/src/main/java/com/arktosbrowser/mobile/view/SmartCookieKT.kt` — error/interstitial pages via `loadDataWithBaseURL`.
- `app/src/main/java/com/arktosbrowser/mobile/view/TabInitializer.kt` — per-tab init.
- `app/src/main/java/com/arktosbrowser/mobile/view/webrtc/WebRtcPermissionsModel.kt` — WebRTC permission model.
- `app/src/main/java/com/arktosbrowser/mobile/browser/TabsManager.kt` — tab collection; would become the `Page` registry and engine selector.
- `app/src/main/java/com/arktosbrowser/mobile/browser/activity/BrowserActivity.kt` — WebView/WebSettings touchpoints (UA, zoom, text size, cookies, JS toggle, geolocation), `onPause`/`onResume` engine lifecycle.
- `app/src/main/java/com/arktosbrowser/mobile/browser/BrowserPresenter.kt`, `app/src/main/java/com/arktosbrowser/mobile/browser/activity/` siblings — tab orchestration.
- `app/src/main/java/com/arktosbrowser/mobile/BrowserApp.kt`, `app/src/main/java/com/arktosbrowser/mobile/MainActivity.kt`, `app/src/main/java/com/arktosbrowser/mobile/IncognitoActivity.kt` — app/entry wiring.
- `app/src/main/java/com/arktosbrowser/mobile/controller/UIController.kt` — WebView-facing UI controller interface.
- `app/src/main/java/com/arktosbrowser/mobile/dialog/LightningDialogBuilder.kt` — JS dialog builders.
- `app/src/main/java/com/arktosbrowser/mobile/download/DownloadHandler.kt`, `app/src/main/java/com/arktosbrowser/mobile/download/DownloadCompleteReceiver.kt`, `app/src/main/java/com/arktosbrowser/mobile/download/LightningDownloadListener.kt` — DownloadListener seam + completion verification (CookieManager for retry headers). The rest of `download/` (DownloadHttpProbe, DownloadNaming, DownloadIntegrity, DownloadMetaStore, ContentDispositionFileNameParser, WebAddress, DownloadUtil, DownloadActivity) is engine-neutral — no `android.webkit`.
- `app/src/main/java/com/arktosbrowser/mobile/extensions/PermissionRequestExtensions.kt` — WebRTC/geolocation permission mapping.
- `app/src/main/java/com/arktosbrowser/mobile/html/homepage/HomePageFactory.kt`, `app/src/main/java/com/arktosbrowser/mobile/html/incognito/IncognitoPageFactory.kt` — `WebView` content factories.
- `app/src/main/java/com/arktosbrowser/mobile/popup/PopUpClass.kt` — popup/menu WebView handling.
- `app/src/main/java/com/arktosbrowser/mobile/preference/UserPreferencesExtensions.kt`, `app/src/main/java/com/arktosbrowser/mobile/settings/fragment/HomepageSettingsFragment.kt`, `app/src/main/java/com/arktosbrowser/mobile/settings/fragment/PrivacySettingsFragment.kt` — settings→WebSettings apply.
- `app/src/main/java/com/arktosbrowser/mobile/utils/IntentUtils.java`, `app/src/main/java/com/arktosbrowser/mobile/utils/UrlUtils.kt`, `app/src/main/java/com/arktosbrowser/mobile/utils/Utils.kt`, `app/src/main/java/com/arktosbrowser/mobile/utils/WebUtils.java` — URL/WebView helpers.
- `js/` — injected-script loaders (DarkMode/AmpBlock/CookieBlock/TextReflow) — map to `UserScript`.

<!-- engine-audit-whitelist
app/src/main/java/com/arktosbrowser/mobile/view/
-->

## Servo applicability notes (carried from desktop .devin/SERVO-SPIKE.md, libservo v0.7.0 survey)

Things that map well to the sketch: load/stop/reload + history nav +
notifications; a real request-interception hook (`load_web_resource`)
that can serve `BlocklistEngine` verdicts; `UserContentManager` ≈
injected scripts; navigation-gate delegate ≈ `RequestPolicy`;
custom-scheme `ProtocolRegistry`; permissions/context-menu/auth/IME
delegates.

Gating gaps recorded upstream — all still apply on Android:

- **No SOCKS proxy** — HTTP CONNECT only ⇒ Tor tabs would stay
  WebView-locked permanently.
- **No cert-error delegate** — only a global `ignore_certificate_errors`
  kill-switch; SEC-style interstitial not drivable.
- **No per-page settings object** — per-tab JS/content toggles have no
  equivalent (interceptor-side blocking is the workaround).
- **No find-in-page, no download delegate, no progress fraction**
  (spinner only), **no WebExtension surface**.
- **Cookie control is imperative** (`SiteDataManager` set/clear), not a
  per-request filter — third-party-cookie gate rides the interceptor.
- **JS eval returns are unmarshallable** — fire-and-forget only.

Android-specific deltas on top: `libservoshell` renders to an EGL
surface driven by the embedder's event loop (`EventLoopWaker`); an
Android backend needs a SurfaceView + lifecycle/input plumbing — no
maintained "ServoView" widget exists. Bundle cost ~50–80MB per ABI.

## Rule

Any new file touching `android.webkit` outside `app/src/main/java/com/arktosbrowser/mobile/view/` must be noted
here. The `engine` package may only reference Kotlin/stdlib +
`android.view.View`; `android.webkit.*` in `engine/` is a violation.
