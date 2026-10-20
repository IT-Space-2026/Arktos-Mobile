# ENGINE.md — engine-portability coupling map (mobile, ENG-M01)

Mirrors `.devin/ENGINE.md` in IT-Space-2026/arktos (desktop). Groundwork
for a possible selectable-engine effort: the Android UI shell stays;
the web engine underneath could become selectable per tab —
**Android WebView today, Servo as a future candidate.**

**The seam is live (ENG02).**
`app/src/main/java/com/arktosbrowser/mobile/engine/BrowserEngine.kt`
is the real boundary — `Backend` / `Profile` / `Page` plus neutral
value types — and `engine/Engines.kt` is the process-wide registry.
`BrowserApp` installs the WebView adapter at startup; every
engine-neutral caller reaches engine services through `Engines.backend`
/ `Engines.defaultProfile` / `Page` instead of `android.webkit` types.
The adapter below is the only place the platform webview API may be
referenced.

## Backends

| # | Engine | Status |
|---|--------|--------|
| 0 | Android WebView | the only backend; adapter in `view/` implements `Backend`/`Profile`/`Page` |
| 1 | Servo | not started — desktop ENG03 spike verdicts carried below; Android embedding viability is a separate open question (EGL surface, NDK lifecycle, no WebView-compat API) |

## Adapter boundary (where WebView types are allowed to live)

Everything under `app/src/main/java/com/arktosbrowser/mobile/view/` is
the WebView adapter — the only subtree allowed to import
`android.webkit`/`androidx.webkit`. The pieces:

- `view/WebViewEngine.kt` — `Backend`: process-wide setup
  (`setDataDirectorySuffix` for the `:incognito` process,
  `setWebContentsDebuggingEnabled` in debug), `systemUserAgent`,
  `defaultProfile`/`createProfile`, `createPage`, `createView`,
  `engineNote` (installed WebView package version).
- `view/WebViewProfile.kt` — `Profile`: cookie/storage partition over
  `CookieManager`, `WebStorage`, `WebViewDatabase` + throwaway WebViews
  for cache/form/SSL clearing.
- `view/ArktosCookieView.kt` — `Page`: the tab object; owns the real
  `WebView`, lifecycle, settings apply, and emits the neutral `Page`
  signals. `asWebView()` is the adapter-local escape hatch for
  transport/initializer plumbing.
- `view/ArktosCookieWebClient.kt` — WebViewClient: interception (`shouldInterceptRequest` → adblock/malware/siteblock/offline/HTTPS-only seam), errors, downloads; error/interstitial pages emitted via `loadDataWithBaseURL` (upstream `SmartCookieKT.kt` folded in); SEC04 HTTPS-only warning interstitial + proceed-marker consent handled here (decision logic in engine-neutral `network/HttpsOnlyGate.kt`); SEC13 referrer control takes over main-frame GET navigations via `loadUrl` headers (decision logic in engine-neutral `network/ReferrerPolicy.kt`). Emits `Page.onLoadStatusChanged`/`onUrlChanged`.
- `view/ArktosCookieChromeClient.kt` — WebChromeClient: JS dialogs, file chooser, permissions, fullscreen. Emits `Page.onProgressChanged`/`onTitleChanged`/`onFaviconChanged`.
- `view/TabInitializer.kt` — `PageInitializer` impls (homepage, incognito, bookmark, download, history, restore, transport).
- `view/NewWindowTransport.kt` — `onCreateWindow` transport-message deliverability check.
- `view/MixedContentPolicy.kt` — mixed-content mode decision (NEVER_ALLOW default; compatibility mode only via the privacy toggle, never in incognito).
- `view/UserScriptEngine.kt` — userscript runtime: `WebViewCompat` document-start injection + `WebMessageListener` GM bridge.
- `view/TranslateBridgeWebView.kt` — the `WebViewCompat.addWebMessageListener` plumbing for `translate/TranslateBridge.kt` (whose core is engine-neutral).
- `view/WebViewPages.kt` — adapter-local helpers bridging `Page` ↔ WebView specifics.
- `view/ProxyUtils.kt` — `ProxyController` plumbing (moved out of `utils/`).
- `view/LightningDownloadListener.kt` — `DownloadListener` seam (moved out of `download/`).
- `view/PermissionRequestExtensions.kt` — WebRTC/geolocation permission mapping (moved out of `extensions/`).
- `view/webrtc/` — WebRTC permission model + view.
- `view/GmConnectPolicy.kt`, `view/RenderingMode.kt`, `view/SearchView.kt`, `view/ArktosCookieViewTitle.kt`, `view/BackgroundDrawable.kt`, `view/find/` — WebView-adjacent helpers that rode along with the adapter subtree.
- `app/src/test/java/com/arktosbrowser/mobile/view/` — adapter unit tests (`MixedContentPolicyTest`, `NewWindowTransportTest`).

Everything else in `app/src/main` is engine-neutral: `browser/`
(TabsManager, BrowserPresenter, BrowserActivity), `controller/`,
`settings/`, `download/`, `utils/`, `html/`, `translate/TranslateBridge.kt`,
`BrowserApp`, `MainActivity`, `IncognitoActivity`, `js/` loaders — they
talk to `Page`/`Profile`/`Backend` and the neutral `UrlUtils` helpers
(`isFileUrl`, `isAboutUrl`, `isValidUrl`, `fileExtensionFromUrl`) — no
`android.webkit`/`androidx.webkit` imports anywhere outside `view/`.

<!-- engine-audit-whitelist
app/src/main/java/com/arktosbrowser/mobile/view/
app/src/test/java/com/arktosbrowser/mobile/view/
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
