package com.arktosbrowser.mobile.browser.progress

/**
 * MOD09 — pure decision logic behind [AnimatedProgressBar]. The view
 * stays a custom widget because the thin accent line, CSS-`ease`
 * bezier and fade-out-at-100 are the app's progress-bar design (a
 * platform ProgressBar would need the same custom drawable and alpha
 * plumbing to match); this object keeps the update rules
 * JVM-testable.
 */
internal object ProgressBarPolicy {

    const val MAX_PROGRESS = 100
    const val PROGRESS_DURATION_MS = 500L
    const val ALPHA_DURATION_MS = 200L

    /** Incoming progress is clamped into [0, MAX_PROGRESS]. */
    fun clampProgress(progress: Int): Int = progress.coerceIn(0, MAX_PROGRESS)

    /** Bar width in px for [progress] across a [viewWidth]-wide view. */
    fun targetWidthPx(progress: Int, viewWidth: Int): Int =
        viewWidth * clampProgress(progress) / MAX_PROGRESS

    /**
     * A decreasing update restarts the bar from zero unless it
     * animates in both directions.
     */
    fun snapsToStart(
        newProgress: Int,
        currentProgress: Int,
        bidirectionalAnimate: Boolean
    ): Boolean = newProgress < currentProgress && !bidirectionalAnimate

    /** The bar fades away once progress reaches the maximum. */
    fun fadesOutAtEnd(progress: Int): Boolean = progress >= MAX_PROGRESS
}
