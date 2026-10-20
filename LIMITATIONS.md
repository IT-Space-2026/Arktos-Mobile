# Known platform limitations

Arktos Mobile renders pages with **Android System WebView**. The issues
below were reported against SmartCookieWeb (upstream
`CookieJarApps/SmartCookieWeb`, which Arktos is forked from) and are
**limitations of the WebView platform itself**, not bugs we can fix in
app code by refactoring or rewriting. Each entry lists what partial
mitigation exists today and what would have to change upstream for a
real fix.

---

## WebRTC can leak your real/local IP (upstream #310, #301)

**Status: platform limitation — no WebView API exists.**

WebView has no switch to disable `RTCPeerConnection` and no API to
control ICE candidate gathering. This was verified against both
`android.webkit.WebSettings` and the current `androidx.webkit`
feature set (`WebViewFeature`/`WebSettingsCompat` — no WebRTC
IP-handling or disable feature exists as of androidx.webkit 1.17.x;
the project pins 1.12.1).

Important detail: the in-app toggle **Settings → Privacy → "Allow
WebRTC permission requests"** (`webrtc_support`, default on) only
gates `WebChromeClient.onPermissionRequest`, i.e. camera/microphone
grants — see `ArktosCookieChromeClient.kt` /
`view/webrtc/WebRtcPermissionsModel.kt`. ICE gathering requires **no
permission**: a page can create an `RTCPeerConnection` and enumerate
local/public candidate addresses without ever asking for camera or
mic. Turning the setting off does **not** stop the IP leak.

Partial mitigations available today:

- **Use a VPN.** Tunnelling UDP removes the direct-UDP leak path.
  The app's proxy support only covers HTTP(S) fetches — it does not
  carry WebRTC media/candidate traffic.
- Keep *Allow WebRTC permission requests* off anyway: it blocks
  camera/mic access, which limits (but does not eliminate) what
  WebRTC can do.
- Stubbing `RTCPeerConnection` via an injected script is technically
  possible but fragile (subframe/worker realms, trivial feature
  detection) and is **not shipped** — we'd rather document the leak
  than ship a false sense of protection.

**Revisit trigger:** androidx.webkit adding a WebRTC disable/IP-
handling-policy feature, or an engine swap (see `.devin/ENGINE.md`).

## Per-site timezone spoofing (upstream #313)

**Status: platform limitation — no WebView API exists.**

WebView always reports the **device timezone**. There is no WebView
API to override the timezone per tab or per site, unlike desktop
engines that offer a resist-fingerprinting timezone.

Partial mitigation: JavaScript injection could override
`Date.prototype.getTimezoneOffset` and
`Intl.DateTimeFormat.prototype.resolvedOptions().timeZone`, but this
is **cosmetic only** — HTTP request timing, `Date` headers and the
network layer still expose the real timezone, the shim is detectable,
and it breaks sites that do local-time scheduling. Not shipped.

**Workaround:** change the device timezone for the browsing session.

**Revisit trigger:** a WebView timezone/locale-override API.

## Background media playback (upstream #105)

**Status: platform limitation — hard by design on WebView, not
impossible but out of scope for a bug fix.**

When the app is backgrounded, `BrowserActivity.onPause()` calls
`TabsManager.pauseAll()`, which invokes `WebView.onPause()` on every
tab (plus `pauseTimers()` on the current tab). That halts all page
media — this is WebView's intended lifecycle contract.

Keeping audio playing while backgrounded would require all of:

1. Skipping `WebView.onPause()` for tabs that are playing media — but
   WebView exposes **no API to query whether page media is playing**,
   so the app can't reliably decide which tabs to keep awake.
2. A **foreground service** with a persistent notification — Android
   kills ordinary background processes, and WebView does not host
   its own media service on the app's behalf.
3. Manual audio-focus/media-session handling, since WebView does not
   expose a `MediaController` bridge for arbitrary page media.

That is a feature project with real battery/UX trade-offs, not a
defect. Workaround: keep the screen on or use split-screen/picture-
in-picture where the site supports it.

**Revisit trigger:** WebView media-observation/session APIs, or an
explicit "play in background" feature request being scheduled as its
own project.

## Sign-in loops on GitLab (and similar SSO flows) (upstream #318)

**Status: likely WebView/cookie-policy quirk — investigated cheaply,
cannot confirm without a live repro.**

This symptom (login submits, bounces back to the sign-in page) is
typically one of:

1. **Third-party cookies blocked.** GitLab sign-in hops across
   domains (gitlab.com ↔ customers portal / SSO providers). If
   *Settings → Privacy → Block third-party cookies* is on, the SSO
   leg loses its cookies and loops. Fix: turn the setting off.
   (`setAcceptThirdPartyCookies` in `ArktosCookieView.kt`.)
2. **Cookies disabled entirely.** *Enable cookies* must be on.
3. **Incognito mode on older devices.** Where full incognito isn't
   supported (`DeviceCapabilities.FULL_INCOGNITO`), the app disables
   `domStorageEnabled`/`databaseEnabled` — GitLab's sign-in needs
   local/session storage. Fix: sign in from a normal tab.
4. **Stale Android System WebView.** Older WebView builds had
   SameSite-cookie transition bugs that produced exactly this loop.
   Fix: update "Android System WebView" in the Play Store.

If sign-in still loops after those checks **and works in Chrome on
the same device**, that is a WebView-vs-Chrome behavioural gap outside
our control — please include the WebView version
(`Settings → Apps → Android System WebView`) in the report.

## Do Not Track / Global Privacy Control only reach top-level navigations

**Status: platform limitation — WebView can't rewrite outgoing request
headers.**

*Settings → Privacy → "Request 'Do Not Track'"* sends `DNT: 1` and
`Sec-GPC: 1` (Global Privacy Control, the legally-backed successor to
the deprecated DNT signal) via the extra-headers argument of
`WebView.loadUrl` (`network/DoNotTrackPolicy.kt`, applied in
`ArktosCookieView.initializePreferences`). That argument only exists
for app-initiated top-level navigations — **subresources, XHR/fetch
and POST requests can never carry custom headers on WebView** (there
is no `onBeforeSendHeaders`-style API), and the signals ride
in-page link navigations only when the referrer policy takes the
navigation over into a `loadUrl` call.

Partial mitigations available today:

- Sites that honor GPC treat the main-document signal as an opt-out
  for the visit, which is the coverage that matters for most
  consent-regime purposes.
- The **gecko flavor** (`engine=gecko` build) has full coverage: it
  sets GeckoView's `privacy.donottrackheader.enabled` and
  `privacy.globalprivacycontrol.enabled` prefs, which attach the
  signals to *every* request including subresources — see
  `gecko/GeckoEngine.kt`. This is why no `webRequest` WebExtension
  shim was needed there.
- `navigator.globalPrivacyControl` / `navigator.doNotTrack` JS
  properties can't be shimmed reliably either — WebView offers no
  way to override navigator properties before page scripts run.

**Revisit trigger:** a WebView request-header mutation API (e.g. an
androidx.webkit `WebViewClientCompat` onBeforeSendHeaders feature),
or the gecko flavor becoming the default build.

---

## Suggested issue replies

Copy-ready responses for the upstream tracker (and for equivalent
Arktos issues):

**#310 / #301 (WebRTC IP leak):**
> This is a WebView platform limitation: WebView exposes no API to
> disable `RTCPeerConnection` or change ICE candidate policy, and
> androidx.webkit has no WebRTC feature flag either (checked through
> 1.17.x). The app's WebRTC permission toggle only controls
> camera/mic grants — ICE gathering needs no permission, so the leak
> can't be closed from app code. The only reliable mitigation today
> is a VPN (HTTP proxies don't carry WebRTC traffic). Documented in
> LIMITATIONS.md; we'll revisit if a WebView API lands.

**#313 (per-site timezone):**
> Not fixable on WebView: the engine always reports the device
> timezone and offers no override. A JS shim over `Date`/`Intl` would
> be cosmetic, detectable, and break local-time sites — we won't
> ship a fake fix. Documented in LIMITATIONS.md.

**#105 (background play):**
> WebView's lifecycle contract requires `WebView.onPause()` when the
> app backgrounds, which stops media. Real background audio needs a
> foreground service plus manual audio-focus/media-session handling —
> and WebView gives no API to even detect that page media is playing.
> It's a feature project, not a bug fix; documented in
> LIMITATIONS.md.

**#318 (gitlab login loop):**
> Most likely a cookie/storage policy issue rather than an app bug.
> Please check: (1) *Block third-party cookies* off (SSO hops
> domains), (2) cookies enabled, (3) not in incognito on older
> devices (DOM storage is disabled there), (4) Android System
> WebView updated — old builds had a SameSite bug causing exactly
> this. If it loops after all four while Chrome on the same device
> works, please report your WebView version.
