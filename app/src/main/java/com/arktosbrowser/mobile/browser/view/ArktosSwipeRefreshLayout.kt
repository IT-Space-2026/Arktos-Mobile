// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * SwipeRefreshLayout that only arms the pull if the gesture began
 * while the page was already at top. The stock widget checks
 * canChildScrollUp during the move, so a scroll-back-to-top drag
 * that *lands* on top keeps pulling and fires a spurious refresh —
 * this one latches the arm flag at ACTION_DOWN.
 */
class ArktosSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    private var armed = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> armed = !canChildScrollUp()
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> { /* keep the down arm */ }
        }
        return armed && super.onInterceptTouchEvent(ev)
    }
}
