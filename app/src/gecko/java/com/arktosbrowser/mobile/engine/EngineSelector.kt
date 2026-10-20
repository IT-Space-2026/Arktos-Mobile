package com.arktosbrowser.mobile.engine

import com.arktosbrowser.mobile.BrowserApp
import com.arktosbrowser.mobile.gecko.GeckoEngine

/**
 * ENG03 — flavor seam: which [Backend] this build installs into
 * [Engines]. The `gecko` engine flavor installs `GeckoEngine`; the
 * `webview` flavor's copy installs the Dagger-provided `WebViewEngine`.
 *
 * `GeckoEngine` isn't part of the Dagger graph (the graph lives in
 * `main` and can't name a flavor type), so it pulls the same
 * dependencies `WebViewEngine` is injected with from `AppComponent`'s
 * provision methods.
 */
object EngineSelector {

    fun createBackend(app: BrowserApp): Backend {
        val component = app.applicationComponent
        return GeckoEngine(
            app,
            component.buildInfo(),
            component.homePageInitializer(),
            component.incognitoPageInitializer(),
            component.bookmarkPageInitializer(),
            component.downloadPageInitializer(),
            component.historyPageInitializer(),
            component.userPreferences(),
            component.logger(),
            component.lightningDialogBuilder(),
            component.allowListModel(),
            component.connectivityGate(),
            component.siteSettings(),
            component.blocklistLoader(),
            component.rankListLoader()
        )
    }

}
