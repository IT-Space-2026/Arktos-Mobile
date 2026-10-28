// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser.activity

import android.app.Activity
import android.media.MediaPlayer
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.os.Handler
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.engine.Page
import com.arktosbrowser.mobile.extensions.removeFromParent
import com.arktosbrowser.mobile.log.Logger

/**
 * The tab-display plumbing extracted from `BrowserActivity`: swapping
 * the content widget between tabs (`setTabView`/`removeTabView`) and
 * the HTML5-video fullscreen surface (`onShowCustomView`/
 * `onHideCustomView`). Everything it touches is handed in — the
 * activity keeps no parallel copies of the state, and `currentTabView`
 * is exposed for the places that still read/animate it directly.
 */
internal class TabViewController(
    private val activity: Activity,
    private val contentFrame: ViewGroup,
    private val toolbarLayout: View,
    private val drawerLayout: DrawerLayout,
    private val mainHandler: Handler,
    private val logger: Logger,
    private val currentTab: () -> Page?,
    private val isFullScreenMode: () -> Boolean,
    private val hideStatusBar: () -> Boolean,
    private val setFullscreen: (enabled: Boolean, immersive: Boolean) -> Unit,
    private val showActionBar: () -> Unit
) {

    var currentTabView: View? = null

    private var fullscreenContainerView: FrameLayout? = null
    private var customView: View? = null
    private var customViewCallback: (() -> Unit)? = null
    private var videoView: VideoView? = null
    private var originalOrientation: Int = 0

    /** The tab view's toolbar-offset before gecko fullscreen zeroed it. */
    private var preFullscreenTranslationY: Float? = null

    /** Whether a video-fullscreen surface is currently active. */
    val isVideoFullscreen: Boolean
        get() = customView != null || customViewCallback != null

    fun removeTabView() {
        currentTabView.removeFromParent()
        currentTabView = null
        // Use a delayed handler to make the transition smooth
        // otherwise it will get caught up with the showTab code
        // and cause a janky motion
        mainHandler.postDelayed(drawerLayout::closeDrawers, 200)
    }

    fun setTabView(view: View) {
        if (currentTabView == view) {
            // Same widget re-shown — the Gecko backend swaps the
            // session inside its one shared GeckoView, so this path is
            // its every-switch route: still focus the view and close
            // the drawer like a real swap.
            view.requestFocus()
            mainHandler.postDelayed(drawerLayout::closeDrawers, 200)
            return
        }

        logger.log(TAG, "Setting the tab view")

        view.removeFromParent()
        currentTabView.removeFromParent()

        contentFrame.addView(view, 0, MATCH_PARENT)

        view.translationY = if (isFullScreenMode()) {
            toolbarLayout.height + toolbarLayout.translationY
        } else {
            0f
        }

        currentTabView = view

        view.requestFocus()

        showActionBar()

        // Use a delayed handler to make the transition smooth
        // otherwise it will get caught up with the showTab code
        // and cause a janky motion
        mainHandler.postDelayed(drawerLayout::closeDrawers, 200)
    }

    fun onShowCustomView(view: View, callback: () -> Unit, requestedOrientation: Int) {
        if (customView != null) {
            try {
                callback()
            } catch (e: Exception) {
                logger.log(TAG, "Error hiding custom view", e)
            }
            return
        }

        try {
            view.keepScreenOn = true
        } catch (e: SecurityException) {
            logger.log(TAG, "WebView is not allowed to keep the screen on")
        }

        originalOrientation = activity.requestedOrientation
        customViewCallback = callback
        customView = view

        //activity.setRequestedOrientation(requestedOrientation)
        activity.requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR

        if (view === currentTabView) {
            // The engine lent its page widget (GeckoView): gecko
            // renders the fullscreen element inside the existing
            // view, so fullscreen here is a layout matter — hide the
            // chrome so the widget's bounds cover the window.
            // Reparenting it into an overlay would tear the session's
            // display binding, which gecko reads as a fullscreen-exit
            // — and the element only grows to the widget's own bounds
            // anyway (the Fenix/mozilla-components model). The
            // toolbar-offset translation has to come off too, or the
            // top stays white and the bottom clips.
            preFullscreenTranslationY = view.translationY
            view.translationY = 0f
            toolbarLayout.visibility = View.GONE
            setFullscreen(true, true)
            return
        }

        val decorView = activity.window.decorView as FrameLayout

        fullscreenContainerView = FrameLayout(activity)
        fullscreenContainerView?.setBackgroundColor(
            ContextCompat.getColor(activity, R.color.black)
        )
        if (view is FrameLayout) {
            val child = view.focusedChild
            if (child is VideoView) {
                videoView = child
                child.setOnErrorListener(VideoCompletionListener())
                child.setOnCompletionListener(VideoCompletionListener())
            }
        } else if (view is VideoView) {
            videoView = view
            view.setOnErrorListener(VideoCompletionListener())
            view.setOnCompletionListener(VideoCompletionListener())
        }
        decorView.addView(fullscreenContainerView, COVER_SCREEN_PARAMS)
        view.removeFromParent()
        if (view.parent == null) {
            fullscreenContainerView?.addView(view, COVER_SCREEN_PARAMS)
        } else {
            // Parent is not a ViewGroup we can detach — better a
            // degraded fullscreen than a crashed app.
            logger.log(TAG, "custom view still attached; fullscreen skipped")
            callback()
            customView = null
            customViewCallback = null
            return
        }
        decorView.requestLayout()
        setFullscreen(true, true)
        currentTab()?.setVisibility(INVISIBLE)
    }

    fun onHideCustomView() {
        // Restore chrome FIRST — a fullscreen that left the fields
        // latched (aborted/abnormal exit, or the declined-fullscreen
        // guard clearing the callback early) must not survive this
        // early return: onWindowFocusChanged replays the latched
        // flags and the status bar stays hidden for good.
        setFullscreen(hideStatusBar(), false)
        val currentTab = currentTab()
        if (customView == null || customViewCallback == null || currentTab == null) {
            if (customViewCallback != null) {
                try {
                    customViewCallback?.invoke()
                } catch (e: Exception) {
                    logger.log(TAG, "Error hiding custom view", e)
                }
                customViewCallback = null
            }
            return
        }
        logger.log(TAG, "onHideCustomView")
        currentTab.setVisibility(VISIBLE)
        try {
            customView?.keepScreenOn = false
        } catch (e: SecurityException) {
            logger.log(TAG, "WebView is not allowed to keep the screen on")
        }

        // The chrome-hide path (GeckoView fullscreen) suppressed the
        // toolbar — bring it back at its home offset; the WebView
        // path never hid it. A scroll-hide animation that ran while
        // fullscreen could have left it translated offscreen.
        toolbarLayout.translationY = 0f
        toolbarLayout.visibility = VISIBLE
        if (fullscreenContainerView != null) {
            val parent = fullscreenContainerView?.parent as ViewGroup
            parent.removeView(fullscreenContainerView)
            fullscreenContainerView?.removeAllViews()
        }
        // When the tab view itself went fullscreen (GeckoView), the
        // container removal left it parentless — put it back; the
        // WebView path keeps its widget attached so this is a no-op.
        currentTabView?.takeIf { it.parent == null }?.let {
            contentFrame.addView(it, 0, MATCH_PARENT)
        }

        fullscreenContainerView = null
        customView = null

        logger.log(TAG, "VideoView is being stopped")
        videoView?.stopPlayback()
        videoView?.setOnErrorListener(null)
        videoView?.setOnCompletionListener(null)
        videoView = null

        try {
            customViewCallback?.invoke()
        } catch (e: Exception) {
            logger.log(TAG, "Error hiding custom view", e)
        }

        customViewCallback = null
        activity.requestedOrientation = originalOrientation

        // An engine that lends its page widget as the fullscreen view
        // (GeckoView) came out of the fullscreen container detached —
        // put it back. The WebView's custom view is a separate
        // surface, so a still-attached current view skips this.
        currentTabView?.takeIf { it.parent == null }?.let {
            contentFrame.addView(it, 0, MATCH_PARENT)
            it.requestFocus()
        }
        preFullscreenTranslationY?.let { currentTabView?.translationY = it }
        preFullscreenTranslationY = null
    }

    private inner class VideoCompletionListener :
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {

        override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean = false

        override fun onCompletion(mp: MediaPlayer) = onHideCustomView()
    }

    private companion object {
        private const val TAG = "TabViewController"
        private val MATCH_PARENT = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        private val COVER_SCREEN_PARAMS = FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
    }
}
