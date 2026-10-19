package com.arktosbrowser.mobile.ssl

/**
 * Gates SSL certificate warnings to one prompt per page load (upstream #296).
 *
 * `WebViewClient.onReceivedSslError` fires once per failing request, so a
 * page whose certificate errors hit many subresources used to stack one
 * dialog per request — the flicker-then-freeze loop from the issue. The gate
 * returns a single decision for the whole page load: while a prompt is
 * showing, extra errors are cancelled, and once the user answers, that
 * answer applies to every later error until [reset] is called on the next
 * page load.
 */
class SslWarningGate {

    private var decision: SslWarningPreferences.Behavior? = null
    private var promptShowing = false

    /** A new page load started; the next load may warn again. */
    fun reset() {
        decision = null
        promptShowing = false
    }

    /**
     * Claims the behavior for an incoming SSL error that has no remembered
     * per-domain behavior. Returns the behavior to apply, or `null` when the
     * warning dialog should be shown (exactly once per page load).
     */
    fun claimPrompt(): SslWarningPreferences.Behavior? {
        decision?.let { return it }
        if (promptShowing) return SslWarningPreferences.Behavior.CANCEL
        promptShowing = true
        return null
    }

    /** Records the user's answer so the rest of the page load follows it. */
    fun onPromptDismissed(behavior: SslWarningPreferences.Behavior) {
        decision = behavior
        promptShowing = false
    }
}
