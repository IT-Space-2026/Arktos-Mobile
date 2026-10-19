package com.arktosbrowser.mobile.translate

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.arktosbrowser.mobile.js.TranslatePage
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-page translation. [translatePage] exposes this object to the page
 * as `window.ArktosTranslate` — deliberately only when the user asks
 * for a translation, so pages never see the interface otherwise — then
 * injects TranslatePage.js, which batches text nodes back through
 * [translate].
 *
 * The wire format (LibreTranslate `{"q":…,"translatedText":…}`) is
 * built and parsed in Rust via [ArktosCore]; this class only owns the
 * HTTP call. `@JavascriptInterface` methods run on a private WebView
 * thread, so the blocking request does not touch the main thread.
 */
@Singleton
class TranslateBridge @Inject constructor(
    private val userPreferences: UserPreferences,
    private val translatePageJs: TranslatePage,
    private val logger: Logger
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Attaches the bridge and runs the translation walker on [webView]. */
    fun translatePage(webView: WebView, targetLanguage: String) {
        val target = targetLanguage.takeIf { it.matches(Companion.LANG_TAG) }
            ?: FALLBACK_LANGUAGE
        webView.addJavascriptInterface(this, JS_INTERFACE_NAME)
        webView.evaluateJavascript(
            translatePageJs.provideJs(webView.context)
                .replace(TARGET_TOKEN, JSONObject.quote(target)),
            null
        )
    }

    /**
     * Called from TranslatePage.js with `{"texts":[…],"target":"xx"}`.
     * Returns a JSON array of translated strings in the same order, or
     * `[]` on any failure (the page keeps its original text).
     */
    @JavascriptInterface
    fun translate(requestJson: String): String {
        return try {
            val request = ArktosCore.translateParseRequest(requestJson) ?: return "[]"

            val endpoint = userPreferences.translationEndpoint
            if (endpoint.isBlank()) return "[]"

            val body = RequestBody.create(
                JSON_MEDIA_TYPE,
                ArktosCore.translateRequestBody(request.texts, SOURCE_AUTO, request.target)
            )
            val httpRequest = Request.Builder()
                .url(ArktosCore.translateUrl(endpoint))
                .post(body)
                .build()

            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) return "[]"
                val parsed = ArktosCore.parseTranslateResponse(
                    response.body()?.string().orEmpty()
                )
                JSONArray(parsed).toString()
            }
        } catch (e: Exception) {
            logger.log(TAG, "translate request failed: ${e.message}")
            "[]"
        }
    }

    companion object {
        private const val TAG = "TranslateBridge"
        private const val JS_INTERFACE_NAME = "ArktosTranslate"
        private const val TARGET_TOKEN = "%%TARGET_JSON%%"
        private const val SOURCE_AUTO = "auto"
        private const val FALLBACK_LANGUAGE = "en"
        private val JSON_MEDIA_TYPE = MediaType.parse("application/json")

        /**
         * Caller-side sanity check for the menu-selected language —
         * Rust re-validates `target` inside the walker payload too.
         */
        private val LANG_TAG = "[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*".toRegex()
    }
}
