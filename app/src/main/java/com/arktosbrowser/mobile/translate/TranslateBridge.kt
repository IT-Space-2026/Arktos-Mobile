package com.arktosbrowser.mobile.translate

import android.content.Context
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.di.NetworkScheduler
import com.arktosbrowser.mobile.engine.Page
import com.arktosbrowser.mobile.js.TranslatePage
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.uniffi.TranslateRequest
import io.reactivex.rxjava3.core.Scheduler
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-page translation. The engine adapter installs a
 * `WebMessageListener` exposed to pages as `window.ArktosTranslate`
 * — a postMessage-only object (same pattern as the userscript
 * `arktosGM` bridge), so no host interface is reachable from page
 * JavaScript. [translatePage] injects TranslatePage.js, which
 * batches text nodes through the listener as
 * `{"id":…,"texts":[…],"target":"…"}` messages and applies the
 * `{"id":…,"translated":[…]}` replies in order.
 *
 * The wire format (LibreTranslate `{"q":…,"translatedText":…}`) is
 * built and parsed in Rust via [ArktosCore]; this class only owns the
 * HTTP call. Message callbacks arrive on the UI thread, so the
 * blocking request runs on [networkScheduler] and the reply is posted
 * back on [mainScheduler].
 *
 * This class is engine-neutral: the `WebViewCompat` listener install
 * lives in the WebView adapter (`view/TranslateBridgeWebView.kt`),
 * which forwards each message to [dispatch].
 */
@Singleton
class TranslateBridge @Inject constructor(
    private val userPreferences: UserPreferences,
    private val translatePageJs: TranslatePage,
    private val logger: Logger,
    @NetworkScheduler private val networkScheduler: Scheduler,
    @MainScheduler private val mainScheduler: Scheduler
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Runs the translation walker on [page]. */
    fun translatePage(page: Page, context: Context, targetLanguage: String) {
        val target = targetLanguage.takeIf { it.matches(Companion.LANG_TAG) }
            ?: FALLBACK_LANGUAGE
        page.runJavaScript(
            translatePageJs.provideJs(context)
                .replace(TARGET_TOKEN, JSONObject.quote(target))
        ) {}
    }

    /**
     * Entry point for the engine-side message listener: resolves
     * [data] to a reply payload off the UI thread, then delivers it
     * to [reply] on the main scheduler.
     */
    internal fun dispatchMessage(data: String, reply: (String) -> Unit) {
        networkScheduler.scheduleDirect {
            val payload = handleMessage(data)
            mainScheduler.scheduleDirect {
                reply(payload)
            }
        }
    }

    /** Logged by the adapter when `WEB_MESSAGE_LISTENER` is unavailable. */
    internal fun logMessageListenerUnsupported() {
        logger.log(TAG, "WEB_MESSAGE_LISTENER unsupported — in-page translation disabled")
    }

    /**
     * Handles one `{"id":…,"texts":[…],"target":"…"}` walker message
     * and returns the `{"id":…,"translated":[…]}` reply. Always
     * produces a reply — even for unparseable batches — so the walker
     * never stalls waiting on a response. Runs off the UI thread.
     */
    internal fun handleMessage(data: String): String {
        val id = parseId(data)
        val translated = try {
            val request = ArktosCore.translateParseRequest(data)
            val endpoint = userPreferences.translationEndpoint
            if (request == null || endpoint.isBlank()) {
                emptyList()
            } else {
                performTranslation(endpoint, request)
            }
        } catch (e: Exception) {
            logger.log(TAG, "translate request failed: ${e.message}")
            emptyList()
        }
        return buildReply(id, translated)
    }

    /** Correlation id — lenient so a bad payload still gets an answer. */
    internal fun parseId(data: String): Long =
        runCatching { JSONObject(data).optLong("id", -1L) }.getOrDefault(-1L)

    /** Blocking LibreTranslate batch request. */
    internal fun performTranslation(endpoint: String, request: TranslateRequest): List<String> {
        val body = ArktosCore.translateRequestBody(request.texts, SOURCE_AUTO, request.target)
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(ArktosCore.translateUrl(endpoint))
            .post(body)
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            return ArktosCore.parseTranslateResponse(
                response.body.string().orEmpty()
            )
        }
    }

    internal fun buildReply(id: Long, translated: List<String>): String =
        JSONObject()
            .put("id", id)
            .put("translated", JSONArray(translated))
            .toString()

    companion object {
        private const val TAG = "TranslateBridge"
        private const val JS_OBJECT_NAME = "ArktosTranslate"
        private const val TARGET_TOKEN = "%%TARGET_JSON%%"
        private const val SOURCE_AUTO = "auto"
        private const val FALLBACK_LANGUAGE = "en"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * Caller-side sanity check for the menu-selected language —
         * Rust re-validates `target` inside the walker payload too.
         */
        private val LANG_TAG = "[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*".toRegex()
    }
}
