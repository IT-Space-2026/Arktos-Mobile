package com.arktosbrowser.mobile.gecko

import android.app.Activity
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.controller.UIController
import com.arktosbrowser.mobile.dialog.BrowserDialog
import com.arktosbrowser.mobile.dialog.DialogItem
import com.arktosbrowser.mobile.extensions.resizeAndShow
import com.arktosbrowser.mobile.log.Logger
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BeforeUnloadPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PopupPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RedirectPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RepostConfirmPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt

/**
 * ENG04 — `GeckoSession.PromptDelegate` parity of the dialogs the
 * WebView shows natively or through `ArktosCookieWebClient`/
 * `ArktosCookieChromeClient`: JS alerts/confirms/prompts, file
 * pickers (the `uiController.showFileChooser` hook), HTTP-auth
 * (`dialog_auth_request`), select-element choice lists, form
 * reposts, beforeunload and popup confirmations.
 *
 * GeckoView delivers every prompt asynchronously through a
 * `GeckoResult<PromptResponse>` — each handler shows the matching
 * dialog and completes the prompt from the button callbacks (or
 * dismisses it when the dialog is cancelled).
 */
class GeckoPromptDelegate(
    private val activity: Activity,
    private val uiController: UIController,
    private val logger: Logger
) : GeckoSession.PromptDelegate {

    override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt): GeckoResult<PromptResponse>? {
        val result = GeckoResult<PromptResponse>()
        activity.runOnUiThread {
            MaterialAlertDialogBuilder(activity).apply {
                setTitle(prompt.title ?: activity.getString(R.string.title_warning))
                setMessage(prompt.message)
                setOnCancelListener { result.complete(prompt.dismiss()) }
                setPositiveButton(R.string.action_ok) { _, _ ->
                    result.complete(prompt.dismiss())
                }
            }.resizeAndShow()
        }
        return result
    }

    /** window.confirm — positive/negative button prompt. */
    override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt): GeckoResult<PromptResponse>? {
        val result = GeckoResult<PromptResponse>()
        activity.runOnUiThread {
            MaterialAlertDialogBuilder(activity).apply {
                setTitle(prompt.title ?: activity.getString(R.string.title_warning))
                setMessage(prompt.message)
                setCancelable(true)
                setOnCancelListener { result.complete(prompt.dismiss()) }
                setPositiveButton(R.string.action_ok) { _, _ ->
                    result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE))
                }
                setNegativeButton(R.string.action_cancel) { _, _ ->
                    result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE))
                }
            }.resizeAndShow()
        }
        return result
    }

    /** window.prompt — text entry. */
    override fun onTextPrompt(session: GeckoSession, prompt: TextPrompt): GeckoResult<PromptResponse>? {
        val result = GeckoResult<PromptResponse>()
        activity.runOnUiThread {
            val dialogView = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_edit_text, null)
            val editText = dialogView.findViewById<EditText>(R.id.dialog_edit_text)
            editText.setText(prompt.defaultValue)
            MaterialAlertDialogBuilder(activity).apply {
                setTitle(prompt.title ?: prompt.message)
                setView(dialogView)
                setOnCancelListener { result.complete(prompt.dismiss()) }
                setPositiveButton(R.string.action_ok) { _, _ ->
                    result.complete(prompt.confirm(editText.text.toString()))
                }
            }.resizeAndShow()
        }
        return result
    }

    /**
     * HTTP auth — same dialog layout the WebView's
     * `onReceivedHttpAuthRequest` uses (realm label + user/pass).
     */
    override fun onAuthPrompt(session: GeckoSession, prompt: AuthPrompt): GeckoResult<PromptResponse>? {
        val result = GeckoResult<PromptResponse>()
        activity.runOnUiThread {
            MaterialAlertDialogBuilder(activity).apply {
                val dialogView = LayoutInflater.from(activity)
                    .inflate(R.layout.dialog_auth_request, null)
                val realmLabel = dialogView.findViewById<TextView>(
                    R.id.auth_request_realm_textview
                )
                val name = dialogView.findViewById<EditText>(
                    R.id.auth_request_username_edittext
                )
                val password = dialogView.findViewById<EditText>(
                    R.id.auth_request_password_edittext
                )
                realmLabel.text = activity.getString(
                    R.string.label_realm, prompt.authOptions.uri
                )
                prompt.authOptions.username?.let(name::setText)
                setView(dialogView)
                setTitle(R.string.title_sign_in)
                setCancelable(true)
                setOnCancelListener { result.complete(prompt.dismiss()) }
                setPositiveButton(R.string.title_sign_in) { _, _ ->
                    result.complete(
                        prompt.confirm(
                            name.text.toString().trim(),
                            password.text.toString().trim()
                        )
                    )
                    logger.log(TAG, "Attempting HTTP Authentication")
                }
                setNegativeButton(R.string.action_cancel) { _, _ ->
                    result.complete(prompt.dismiss())
                }
            }.resizeAndShow()
        }
        return result
    }

    /** `<select>` elements — single-choice and multi-choice lists. */
    override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt): GeckoResult<PromptResponse>? {
        val result = GeckoResult<PromptResponse>()
        val choices = prompt.choices?.filter { !it.separator } ?: emptyList()
        activity.runOnUiThread {
            val labels = choices.map { it.label }.toTypedArray()
            val builder = MaterialAlertDialogBuilder(activity)
                .setTitle(prompt.title ?: prompt.message)
                .setOnCancelListener { result.complete(prompt.dismiss()) }
            if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
                val checked = choices.map { it.selected }.toBooleanArray()
                builder
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(R.string.action_ok) { _, _ ->
                        result.complete(
                            prompt.confirm(
                                choices.filterIndexed { i, _ -> checked[i] }.toTypedArray()
                            )
                        )
                    }
            } else {
                builder.setSingleChoiceItems(
                    labels, choices.indexOfFirst { it.selected }
                ) { dialog, which ->
                    result.complete(prompt.confirm(choices[which]))
                    dialog.dismiss()
                }
            }
            builder.resizeAndShow()
        }
        return result
    }

    /**
     * `<input type=file>` — routed through the same file-chooser hook
     * the WebView's `onShowFileChooser` uses so the pick lands in the
     * activity's `onActivityResult` plumbing.
     */
    override fun onFilePrompt(session: GeckoSession, prompt: FilePrompt): GeckoResult<PromptResponse>? {
        val result = GeckoResult<PromptResponse>()
        activity.runOnUiThread {
            uiController.showFileChooser { uris ->
                if (uris == null) {
                    result.complete(prompt.dismiss())
                } else if (prompt.type == FilePrompt.Type.MULTIPLE) {
                    result.complete(prompt.confirm(activity, uris))
                } else {
                    result.complete(prompt.confirm(activity, uris.first()))
                }
            }
        }
        return result
    }

    /** window.open / popup consent — allow so tabs just open. */
    override fun onPopupPrompt(session: GeckoSession, prompt: PopupPrompt): GeckoResult<PromptResponse>? =
        GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

    /** Form resubmission — same title/message as the WebView dialog. */
    override fun onRepostConfirmPrompt(
        session: GeckoSession, prompt: RepostConfirmPrompt
    ): GeckoResult<PromptResponse>? {
        val result = GeckoResult<PromptResponse>()
        activity.runOnUiThread {
            MaterialAlertDialogBuilder(activity).apply {
                setTitle(R.string.title_form_resubmission)
                setMessage(R.string.message_form_resubmission)
                setCancelable(true)
                setOnCancelListener { result.complete(prompt.dismiss()) }
                setPositiveButton(R.string.action_yes) { _, _ ->
                    result.complete(prompt.confirm(AllowOrDeny.ALLOW))
                }
                setNegativeButton(R.string.action_no) { _, _ ->
                    result.complete(prompt.confirm(AllowOrDeny.DENY))
                }
            }.resizeAndShow()
        }
        return result
    }

    /** beforeunload — confirm the user wants to leave. */
    override fun onBeforeUnloadPrompt(
        session: GeckoSession, prompt: BeforeUnloadPrompt
    ): GeckoResult<PromptResponse>? {
        val result = GeckoResult<PromptResponse>()
        activity.runOnUiThread {
            BrowserDialog.showPositiveNegativeDialog(
                activity = activity,
                title = R.string.title_warning,
                message = R.string.message_leaving_confirm,
                positiveButton = DialogItem(title = R.string.action_yes) {
                    result.complete(prompt.confirm(AllowOrDeny.ALLOW))
                },
                negativeButton = DialogItem(title = R.string.action_no) {
                    result.complete(prompt.confirm(AllowOrDeny.DENY))
                },
                onCancel = { result.complete(prompt.dismiss()) }
            )
        }
        return result
    }

    /** Rare redirect consent — decline and keep the current page. */
    override fun onRedirectPrompt(session: GeckoSession, prompt: RedirectPrompt): GeckoResult<PromptResponse>? =
        GeckoResult.fromValue(prompt.dismiss())

    companion object {
        private const val TAG = "GeckoPromptDelegate"
    }
}
