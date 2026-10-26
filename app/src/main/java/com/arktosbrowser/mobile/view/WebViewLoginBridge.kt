// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.view

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.extensions.resizeAndShow
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.passwords.LoginVaults
import com.arktosbrowser.mobile.passwords.StoredLogin
import com.arktosbrowser.mobile.preference.UserPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.reactivex.rxjava3.core.Scheduler
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * AUTOF02 — the WebView (light) flavor's password manager.
 *
 * WebView has no login API: `importantForAutofill` only exposes the
 * page to an *external* autofill service and `saveFormData` keeps
 * unencrypted form junk — neither stores credentials. This bridge is
 * the equivalent of the gecko flavor's `Autocomplete.StorageDelegate`
 * + `onLoginSave`/`onLoginSelect` pair, driven by [CAPTURE_JS]
 * injected per page:
 *
 *   page --{type:submit,user,pass}--> [onSubmit] -> save dialog -> vault
 *   page --{type:fields|focus}-----> [maybeFill] -> `__arktosLoginFill`
 *
 * The channel is `WebMessageListener`/`postMessage` only — no
 * `addJavascriptInterface` surface — and vault lookups key on the
 * listener's `sourceOrigin` (the frame's real origin), never on a
 * string the page sent. Only main-frame messages are honored, so a
 * cross-origin iframe can't trick the bridge into filling the top
 * document with its own site's credentials.
 *
 * Never register on an incognito view: private pages neither read
 * nor write the vault.
 */
@Singleton
class WebViewLoginBridge @Inject constructor(
    private val application: Application,
    private val userPreferences: UserPreferences,
    private val logger: Logger,
    @MainScheduler private val mainScheduler: Scheduler
) {

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WebViewLogins").apply { isDaemon = true }
    }

    /** Install the `ArktosLogin` postMessage object — call before load. */
    fun register(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            logger.log(TAG, "WEB_MESSAGE_LISTENER unsupported — login bridge disabled")
            return
        }
        WebViewCompat.addWebMessageListener(
            webView, JS_OBJECT_NAME, setOf("*")
        ) { view, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame) return@addWebMessageListener
            val data = message.data ?: return@addWebMessageListener
            dispatch(view, data, sourceOrigin)
        }
    }

    /** Inject the capture script — call from `onPageFinished`. */
    fun install(webView: WebView) {
        webView.evaluateJavascript(CAPTURE_JS, null)
    }

    private fun dispatch(webView: WebView, data: String, sourceOrigin: Uri) {
        val origin = normalize(sourceOrigin) ?: return
        val message = try {
            JSONObject(data)
        } catch (e: org.json.JSONException) {
            return
        }
        when (message.optString("type")) {
            TYPE_SUBMIT -> io.execute {
                onSubmit(
                    webView, origin,
                    message.optString("user"), message.optString("pass")
                )
            }
            TYPE_FIELDS -> io.execute { maybeFill(webView, origin, chooser = false) }
            TYPE_FOCUS -> io.execute { maybeFill(webView, origin, chooser = true) }
        }
    }

    /** scheme://host[:port], matching `location.origin` on the JS side. */
    private fun normalize(uri: Uri): String? {
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val port = uri.port
        val default = if (scheme == "https") 443 else 80
        return if (port > 0 && port != default) {
            "$scheme://$host:$port"
        } else {
            "$scheme://$host"
        }
    }

    private fun onSubmit(webView: WebView, origin: String, user: String, pass: String) {
        if (!userPreferences.savePasswordsEnabled || pass.isEmpty()) return
        val vault = LoginVaults.get(application)
        val existing = vault.forOrigin(origin)
        // Same credentials already saved — a nag prompt on every
        // login would be worse than no prompt.
        if (existing.any { it.username == user && it.password == pass }) return
        val activity = webView.context as? Activity ?: return
        val login = StoredLogin(
            guid = null,
            origin = origin,
            formActionOrigin = null,
            httpRealm = null,
            username = user,
            password = pass
        )
        val host = Uri.parse(origin).host ?: origin
        mainScheduler.scheduleDirect {
            MaterialAlertDialogBuilder(activity).apply {
                setTitle(R.string.save_login_title)
                setMessage(activity.getString(R.string.save_login_message, user, host))
                setPositiveButton(R.string.save_login_save) { _, _ ->
                    io.execute { vault.upsert(login) }
                }
                setNegativeButton(R.string.save_login_not_now, null)
            }.resizeAndShow()
        }
    }

    private fun maybeFill(webView: WebView, origin: String, chooser: Boolean) {
        val matches = LoginVaults.get(application).forOrigin(origin)
        if (matches.isEmpty()) return
        when {
            matches.size == 1 -> fill(webView, matches[0])
            // A chooser popping at load time (before any focus) is too
            // aggressive — wait for the user to touch the field.
            chooser -> {
                val activity = webView.context as? Activity ?: return
                val labels = matches.map { it.username }.toTypedArray()
                mainScheduler.scheduleDirect {
                    MaterialAlertDialogBuilder(activity).apply {
                        setTitle(R.string.select_login_title)
                        setItems(labels) { dialog, which ->
                            fill(webView, matches[which])
                            dialog.dismiss()
                        }
                    }.resizeAndShow()
                }
            }
        }
    }

    private fun fill(webView: WebView, login: StoredLogin) {
        val script = "window.__arktosLoginFill && window.__arktosLoginFill(" +
            JSONObject.quote(login.username) + ", " +
            JSONObject.quote(login.password) + ")"
        mainScheduler.scheduleDirect {
            webView.evaluateJavascript(script, null)
            io.execute { LoginVaults.get(application).markUsed(login) }
        }
    }

    companion object {
        private const val TAG = "WebViewLoginBridge"
        private const val JS_OBJECT_NAME = "ArktosLogin"
        private const val TYPE_SUBMIT = "submit"
        private const val TYPE_FIELDS = "fields"
        private const val TYPE_FOCUS = "focus"

        // Installed once per document; the `focusin`/`submit`
        // listeners delegate at document level so dynamically added
        // login forms (SPA route changes included) are still caught.
        // `__arktosLoginFill` uses the native property setter so
        // framework-controlled inputs (React/Vue) see real events.
        private val CAPTURE_JS = """
            (function() {
                if (window.__arktosLoginInstalled) { return true; }
                window.__arktosLoginInstalled = true;

                function post(msg) {
                    try {
                        if (window.ArktosLogin) {
                            window.ArktosLogin.postMessage(JSON.stringify(msg));
                        }
                    } catch (e) {}
                }

                function setVal(el, v) {
                    var d = Object.getOwnPropertyDescriptor(
                        window.HTMLInputElement.prototype, 'value');
                    if (d && d.set) { d.set.call(el, v); } else { el.value = v; }
                    el.dispatchEvent(new Event('input', {bubbles: true}));
                    el.dispatchEvent(new Event('change', {bubbles: true}));
                }

                function passwordFields(root) {
                    return Array.prototype.slice.call(
                        (root || document).querySelectorAll('input[type=password]'));
                }

                function usernameField(pw) {
                    var scope = (pw && pw.form) ? pw.form : document;
                    return scope.querySelector(
                        'input[autocomplete=username], input[type=email], input[type=tel], input[type=text]');
                }

                document.addEventListener('submit', function(e) {
                    var form = e.target;
                    if (!(form instanceof HTMLFormElement)) { return; }
                    var pw = passwordFields(form).filter(function(f) {
                        return f.value && !f.disabled;
                    })[0];
                    if (!pw) { return; }
                    var uf = usernameField(pw);
                    post({type: 'submit', user: uf ? uf.value : '', pass: pw.value});
                }, true);

                document.addEventListener('focusin', function(e) {
                    var el = e.target;
                    if (el instanceof HTMLInputElement && el.type === 'password' &&
                            el.dataset.arktosAsked !== '1' &&
                            el.dataset.arktosFilled !== '1') {
                        el.dataset.arktosAsked = '1';
                        post({type: 'focus'});
                    }
                }, true);

                window.__arktosLoginFill = function(user, pass) {
                    var pw = (document.activeElement instanceof HTMLInputElement &&
                            document.activeElement.type === 'password')
                        ? document.activeElement : passwordFields()[0];
                    if (!pw || pw.dataset.arktosFilled === '1') { return; }
                    pw.dataset.arktosFilled = '1';
                    if (user) {
                        var uf = usernameField(pw);
                        if (uf) { setVal(uf, user); }
                    }
                    setVal(pw, pass);
                };

                if (passwordFields().length > 0) { post({type: 'fields'}); }
                return true;
            })();
        """.trimIndent()
    }
}
