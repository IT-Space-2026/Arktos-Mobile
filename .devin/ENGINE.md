# ENGINE.md — engine-portability coupling map (mobile, ENG-M01)

Mirrors `.devin/ENGINE.md` in IT-Space-2026/arktos (desktop). Groundwork
for a possible selectable-engine effort: the Android UI shell stays;
the web engine underneath could become selectable per tab —
**Android WebView today, Servo as a future candidate.**

**The seam is live (ENG02) and now carries two backends (ENG03).**
`app/src/main/java/com/arktosbrowser/mobile/engine/BrowserEngine.kt`
is the real boundary — `Backend` / `Profile` / `Page` plus neutral
value types — and `engine/Engines.kt` is the process-wide registry.
`BrowserApp` installs whichever backend the build's `engine` flavor
dimension selects through `engine/EngineSelector.kt` (one copy per
flavor: `app/src/webview/` installs `WebViewEngine`, `app/src/gecko/`
installs `GeckoEngine`); every engine-neutral caller reaches engine
services through `Engines.backend` / `Engines.defaultProfile` / `Page`
instead of `android.webkit` types. The tab shell (`TabsManager`,
`BrowserPresenter`, `BrowserActivity`, the tabs views) is typed on
`Page` — the interface carries the whole tab surface
(foreground/new-tab flags, SSL state, timers, `toggleDesktopUA`,
`find`, print) so a backend can stub what it lacks.

## Backends

| # | Engine | Status |
|---|--------|--------|
| 0 | Android WebView | shipping adapter in `view/` implements `Backend`/`Profile`/`Page`; the `webview` flavor = "Arktos Mobile Lite" |
| 1 | GeckoView | ENG03 skeleton in `app/src/gecko/java/.../gecko/` (below); the `gecko` flavor = "Arktos Mobile", arm64-v8a release scope |
| 2 | Servo | not started — desktop ENG03 spike verdicts carried below; Android embedding viability is a separate open question (EGL surface, NDK lifecycle, no WebView-compat API) |

## Adapter boundary (where WebView types are allowed to live)

Everything under `app/src/main/java/com/arktosbrowser/mobile/view/` is
the WebView adapter — the only subtree allowed to import
`android.webkit`/`androidx.webkit`. The pieces:

- `view/WebViewEngine.kt` — `Backend`: process-wide setup
  (`setDataDirectorySuffix` for the `:incognito` process,
  `setWebContentsDebuggingEnabled` in debug, INCOG01 fresh-start
  partition wipe via `browser/IncognitoPartition`),
  `systemUserAgent`,
  `defaultProfile`/`createProfile`, `createPage`, `createView`,
  `engineNote` (installed WebView package version).
- `view/WebViewProfile.kt` — `Profile`: cookie/storage partition over
  `CookieManager`, `WebStorage`, `WebViewDatabase` + throwaway WebViews
  for cache/form/SSL clearing; AppPrivateData targets the process's
  own dir (`app_webview_incognito` in `:incognito`, `app_webview`
  elsewhere).
- `view/ArktosCookieView.kt` — `Page`: the tab object; owns the real
  `WebView`, lifecycle, settings apply, and emits the neutral `Page`
  signals. `asWebView()` is the adapter-local escape hatch for
  transport/initializer plumbing.
- `view/ArktosCookieWebClient.kt` — WebViewClient: interception (`shouldInterceptRequest` → adblock/malware/siteblock/offline/HTTPS-only seam), errors, downloads; error/interstitial pages emitted via `loadDataWithBaseURL` (upstream `SmartCookieKT.kt` folded in); SEC04 HTTPS-only warning interstitial + proceed-marker consent handled here (decision logic in engine-neutral `network/HttpsOnlyGate.kt`); SEC13 referrer control takes over main-frame GET navigations via `loadUrl` headers (decision logic in engine-neutral `network/ReferrerPolicy.kt`). Emits `Page.onLoadStatusChanged`/`onUrlChanged`.
- `view/ArktosCookieChromeClient.kt` — WebChromeClient: JS dialogs, file chooser, permissions, fullscreen. Emits `Page.onProgressChanged`/`onTitleChanged`/`onFaviconChanged`.
- `view/TabInitializer.kt` — `PageInitializer` impls (homepage, incognito, bookmark, download, history, restore, transport).
- `view/NewWindowTransport.kt` — `onCreateWindow` transport-message deliverability check.
- `view/MixedContentPolicy.kt` — mixed-content mode decision (NEVER_ALLOW default; compatibility mode only via the privacy toggle, never in incognito).
- `view/UserScriptEngine.kt` — userscript runtime: `WebViewCompat` document-start injection + `WebMessageListener` GM bridge. The page-side bootstrap and the SEC06 policy store are shared with gecko via the engine-neutral `userscript/UserScriptBootstrap.kt` (outside `view/`; no WebKit types).
- `view/ProxyUtils.kt` — engine-neutral proxy resolution (choice → `HttpProxy`) + the I2P readiness gate; the WebView `ProxyController` apply itself lives in `WebViewEngine.applyProxy` (ENG05 seam).
- `view/TranslateBridgeWebView.kt` — the `WebViewCompat.addWebMessageListener` plumbing for `translate/TranslateBridge.kt` (whose core is engine-neutral).
- `view/WebViewPages.kt` — adapter-local helpers bridging `Page` ↔ WebView specifics.
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

## Gecko adapter (`app/src/gecko/java/com/arktosbrowser/mobile/gecko/`)

Compiled only into `gecko`-flavor variants; imports
`org.mozilla.geckoview.*`, never `android.webkit` (the audit still
applies — WebKit types stay banned here).

- `gecko/GeckoEngine.kt` — `Backend`: owns the process-wide
  `GeckoRuntime` (remote debugging in debug builds; the `:incognito`
  process gets private-mode sessions), `systemUserAgent`,
  `defaultProfile`/`createProfile` (contextId partitioning),
  `createPage`, `createView`, `engineNote` (GeckoView milestone).
  ENG05: `applyProxy` maps Orbot/I2P/manual `HttpProxy` values onto
  `network.proxy.*` gecko prefs (`share_proxy_settings` covers every
  scheme); `syncRuntimePreferences` re-applies the runtime-level
  mappings (cookie behavior via `ContentBlocking`, safe browsing,
  DNT + Sec-GPC via `privacy.donottrackheader.enabled` /
  `privacy.globalprivacycontrol.enabled`, WebRTC, image blocking,
  form fill, font size/inflation, dark color scheme, force-zoom)
  — called once in `initialize` and again from every
  `GeckoPage.initializePreferences` like the WebView's `WebSettings`
  re-apply. `syncCookieBehavior` folds `acceptsCookies` +
  `blockThirdPartyCookiesEnabled` through the shared
  `engine/CookieAcceptance.kt` helper into
  `cookieBehavior`(+PrivateMode).
- `gecko/GeckoProfile.kt` — `Profile`: storage partition over
  `GeckoSessionSettings.contextId` + `StorageController.clearData`;
  `requestPolicy` is consumed by the page's navigation delegate.
  `acceptsCookies` drives the runtime cookie behavior (above); cookie
  get/set stays a documented gap — GeckoView has no
  CookieManager-style imperative API.
- `gecko/GeckoPage.kt` — `Page`: a `GeckoSession` + lazily created
  `GeckoView`; navigation/progress/content/history/prompt/permission
  delegates emit the neutral `Page` signals; supports load/loadData,
  back/forward, `gotoHistoryIndex`, find (`SessionFinder`),
  `userAgentMode` for `toggleDesktopUA`, private mode, session-state
  save/restore. ENG04 parity: `NavigationPolicy` (network/, engine-
  neutral) composes proceed-marker → changelog → SafeSearch →
  HTTPS-only → prefer-HTTPS → external-scheme → scheme check →
  referrer → verdict → connectivity (same ordering the WebView's
  override-then-intercept phases imply), the seam
  `requestPolicy` runs first, `onLoadError` serves generated pages
  (block/offline/LAN/malware/HTTPS-only/cert via `GeckoErrorPages`),
  context menus map `ContextElement`s onto `LightningDialogBuilder`,
  `onExternalResponse` → `LightningDownloadListener` →
  `DownloadHandler`/DownloadManager, `GeckoPromptDelegate` covers
  JS dialogs/auth/`<select>`/file-chooser/repost/beforeunload,
  `GeckoPermissionDelegate` covers runtime/geolocation/media/content
  permissions, `GeckoExternalLauncher` handles mailto/intent/custom
  schemes. Documented gaps: SSL bypass impossible (no
  `SslErrorHandler.proceed` equivalent), cert detail is a stub
  `SslCertificate`, window.open transport, media fullscreen uses the
  whole GeckoView, mixed content is a runtime-global gecko pref not
  per-session.
  ENG05: `loadUrl`/`reload` run the shared I2P readiness gate
  (`ProxyUtils.isProxyReady`), `popupsEnabled` gates `onNewSession`
  (off → the window.open navigation stays in-session), the
  preference UA rides `setUserAgentOverride` (re-applied after
  `toggleDesktopUA`), `useWideViewPort` maps to `VIEWPORT_MODE_*`,
  and the Save-Data header goes on `Loader.additionalHeaders`.
  Stubs: favicons, JS eval, printing, cookie get/set.
- `gecko/GeckoUserScriptHost.kt` — ENG05 userscript surface: a
  built-in WebExtension
  (`src/gecko/assets/extensions/arktos_userscripts/`) whose background
  page registers the shared `userscript/UserScriptBootstrap` (GECKO
  transport) via `contentScripts.register` at document_start; a
  `connectNative` port carries script-set pushes (re-synced on
  `UserScriptEngine.scriptSetVersion` changes). GM ops travel from
  the content script's `runtime.sendNativeMessage` straight to the
  per-session `SessionController` delegate — `sender.url`/`isTopLevel`
  describe the real page frame, which the shared SEC06
  include/exclude + `@connect` gating depends on; the
  xhr/clipboard/openTab/value ops are a port of `UserScriptEngine`'s
  bridge.
- `engine/EngineSelector.kt` (in `src/gecko` and `src/webview`) —
  flavor seam; `BrowserApp` calls `EngineSelector.createBackend(app)`
  so `main` never names a flavor type.
- `app/src/test/java/com/arktosbrowser/mobile/engine/EngineContractTest.kt`
  — seam-purity test; its `android.webkit` literal is the forbidden
  prefix it scans for, not a real WebView dependency.

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
