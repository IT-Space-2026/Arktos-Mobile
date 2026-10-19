/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arktosbrowser.mobile.list

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.ViewPropertyAnimatorCompat
import androidx.core.view.ViewPropertyAnimatorListener
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import androidx.recyclerview.widget.SimpleItemAnimator
import com.arktosbrowser.mobile.interpolator.BezierDecelerateInterpolator

/**
 * This implementation of [RecyclerView.ItemAnimator] provides basic
 * animations on remove, add, and move events that happen to the items in
 * a RecyclerView. RecyclerView uses a HorizontalItemAnimator by default.
 *
 * @see RecyclerView.setItemAnimator
 */
class HorizontalItemAnimator : SimpleItemAnimator() {

    private val pendingRemovals = ArrayList<ViewHolder>()
    private val pendingAdditions = ArrayList<ViewHolder>()
    private val pendingMoves = ArrayList<MoveInfo>()
    private val pendingChanges = ArrayList<ChangeInfo>()

    private val additionsList = ArrayList<ArrayList<ViewHolder>>()
    private val movesList = ArrayList<ArrayList<MoveInfo>>()
    private val changesList = ArrayList<ArrayList<ChangeInfo>>()

    private val addAnimations = ArrayList<ViewHolder>()
    private val moveAnimations = ArrayList<ViewHolder>()
    private val removeAnimations = ArrayList<ViewHolder>()
    private val changeAnimations = ArrayList<ViewHolder>()

    private var defaultInterpolator: TimeInterpolator? = null

    private class MoveInfo(
        val holder: ViewHolder,
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int
    )

    private class ChangeInfo(
        var oldHolder: ViewHolder?,
        var newHolder: ViewHolder?,
        var fromX: Int = 0,
        var fromY: Int = 0,
        var toX: Int = 0,
        var toY: Int = 0
    ) {
        override fun toString(): String {
            return ("ChangeInfo{" +
                "oldHolder=" + oldHolder +
                ", newHolder=" + newHolder +
                ", fromX=" + fromX +
                ", fromY=" + fromY +
                ", toX=" + toX +
                ", toY=" + toY +
                '}')
        }
    }

    override fun runPendingAnimations() {
        val removalsPending = pendingRemovals.isNotEmpty()
        val movesPending = pendingMoves.isNotEmpty()
        val changesPending = pendingChanges.isNotEmpty()
        val additionsPending = pendingAdditions.isNotEmpty()
        if (!removalsPending && !movesPending && !additionsPending && !changesPending) {
            // nothing to animate
            return
        }
        // First, remove stuff
        for (holder in pendingRemovals) {
            animateRemoveImpl(holder)
        }
        pendingRemovals.clear()
        // Next, move stuff
        if (movesPending) {
            val moves = ArrayList(pendingMoves)
            movesList.add(moves)
            pendingMoves.clear()
            val mover = Runnable {
                for (moveInfo in moves) {
                    animateMoveImpl(
                        moveInfo.holder, moveInfo.fromX, moveInfo.fromY,
                        moveInfo.toX, moveInfo.toY
                    )
                }
                moves.clear()
                movesList.remove(moves)
            }
            if (removalsPending) {
                val view = moves[0].holder.itemView
                ViewCompat.postOnAnimationDelayed(view, mover, removeDuration)
            } else {
                mover.run()
            }
        }
        // Next, change stuff, to run in parallel with move animations
        if (changesPending) {
            val changes = ArrayList(pendingChanges)
            changesList.add(changes)
            pendingChanges.clear()
            val changer = Runnable {
                for (change in changes) {
                    animateChangeImpl(change)
                }
                changes.clear()
                changesList.remove(changes)
            }
            if (removalsPending) {
                val holder = changes[0].oldHolder
                if (holder != null) {
                    ViewCompat.postOnAnimationDelayed(holder.itemView, changer, removeDuration)
                }
            } else {
                changer.run()
            }
        }
        // Next, add stuff
        if (additionsPending) {
            val additions = ArrayList(pendingAdditions)
            additionsList.add(additions)
            pendingAdditions.clear()
            val adder = Runnable {
                for (holder in additions) {
                    animateAddImpl(holder)
                }
                additions.clear()
                additionsList.remove(additions)
            }
            if (removalsPending || movesPending || changesPending) {
                val removeDuration = if (removalsPending) removeDuration else 0
                val moveDuration = if (movesPending) moveDuration else 0
                val changeDuration = if (changesPending) changeDuration else 0
                val totalDelay = removeDuration + Math.max(moveDuration, changeDuration)
                val view = additions[0].itemView
                ViewCompat.postOnAnimationDelayed(view, adder, totalDelay)
            } else {
                adder.run()
            }
        }
    }

    override fun animateRemove(holder: ViewHolder): Boolean {
        resetAnimation(holder)
        pendingRemovals.add(holder)
        return true
    }

    private fun animateRemoveImpl(holder: ViewHolder) {
        val view = holder.itemView
        val animation = ViewCompat.animate(view)
        removeAnimations.add(holder)
        animation.setDuration(removeDuration)
            .alpha(0f).translationY(holder.itemView.height.toFloat())
            .setInterpolator(AccelerateInterpolator()).setListener(object : VpaListenerAdapter() {
                override fun onAnimationStart(view: View) {
                    dispatchRemoveStarting(holder)
                }

                override fun onAnimationEnd(view: View) {
                    animation.setListener(null)
                    ViewCompat.setAlpha(view, 1f)
                    ViewCompat.setTranslationY(view, 0f)
                    dispatchRemoveFinished(holder)
                    removeAnimations.remove(holder)
                    dispatchFinishedWhenDone()
                }
            }).start()
    }

    override fun animateAdd(holder: ViewHolder): Boolean {
        resetAnimation(holder)
        ViewCompat.setAlpha(holder.itemView, 0f)
        ViewCompat.setTranslationY(holder.itemView, holder.itemView.height.toFloat())
        pendingAdditions.add(holder)
        return true
    }

    private fun animateAddImpl(holder: ViewHolder) {
        val view = holder.itemView
        val animation = ViewCompat.animate(view)
        addAnimations.add(holder)
        animation.alpha(1f).translationY(0f)
            .setInterpolator(BezierDecelerateInterpolator()).setDuration(addDuration)
            .setListener(object : VpaListenerAdapter() {
                override fun onAnimationStart(view: View) {
                    dispatchAddStarting(holder)
                }

                override fun onAnimationCancel(view: View) {
                    ViewCompat.setTranslationY(view, 0f)
                    ViewCompat.setAlpha(view, 1f)
                }

                override fun onAnimationEnd(view: View) {
                    animation.setListener(null)
                    dispatchAddFinished(holder)
                    addAnimations.remove(holder)
                    dispatchFinishedWhenDone()
                }
            }).start()
    }

    override fun animateMove(
        holder: ViewHolder, fromX: Int, fromY: Int,
        toX: Int, toY: Int
    ): Boolean {
        var fromX = fromX
        var fromY = fromY
        val view = holder.itemView
        fromX += ViewCompat.getTranslationX(holder.itemView).toInt()
        fromY += ViewCompat.getTranslationY(holder.itemView).toInt()
        val deltaX = toX - fromX
        val deltaY = toY - fromY
        if (deltaX == 0 && deltaY == 0) {
            dispatchMoveFinished(holder)
            return false
        }
        resetAnimation(holder)
        if (deltaX != 0) {
            ViewCompat.setTranslationX(view, -deltaX.toFloat())
        }
        if (deltaY != 0) {
            ViewCompat.setTranslationY(view, -deltaY.toFloat())
        }
        pendingMoves.add(MoveInfo(holder, fromX, fromY, toX, toY))
        return true
    }

    private fun animateMoveImpl(holder: ViewHolder, fromX: Int, fromY: Int, toX: Int, toY: Int) {
        val view = holder.itemView
        val deltaX = toX - fromX
        val deltaY = toY - fromY
        if (deltaX != 0) {
            ViewCompat.animate(view).translationX(0f)
        }
        if (deltaY != 0) {
            ViewCompat.animate(view).translationY(0f)
        }
        // TODO: make EndActions end listeners instead, since end actions aren't called when
        // vpas are canceled (and can't end them. why?)
        // need listener functionality in VPACompat for this. Ick.
        val animation = ViewCompat.animate(view)
        moveAnimations.add(holder)
        animation.setDuration(moveDuration).setListener(object : VpaListenerAdapter() {
            override fun onAnimationStart(view: View) {
                dispatchMoveStarting(holder)
            }

            override fun onAnimationCancel(view: View) {
                if (deltaX != 0) {
                    ViewCompat.setTranslationX(view, 0f)
                }
                if (deltaY != 0) {
                    ViewCompat.setTranslationY(view, 0f)
                }
            }

            override fun onAnimationEnd(view: View) {
                animation.setListener(null)
                dispatchMoveFinished(holder)
                moveAnimations.remove(holder)
                dispatchFinishedWhenDone()
            }
        }).start()
    }

    override fun animateChange(
        oldHolder: ViewHolder, newHolder: ViewHolder,
        fromX: Int, fromY: Int, toX: Int, toY: Int
    ): Boolean {
        if (oldHolder === newHolder) {
            // Don't know how to run change animations when the same view holder is re-used.
            // run a move animation to handle position changes.
            if (fromX - toX == 0 && fromY - toY == 0) {
                dispatchMoveFinished(oldHolder)
                return false
            }
            return animateMove(oldHolder, fromX, fromY, toX, toY)
        }
        val prevTranslationX = ViewCompat.getTranslationX(oldHolder.itemView)
        val prevTranslationY = ViewCompat.getTranslationY(oldHolder.itemView)
        val prevAlpha = ViewCompat.getAlpha(oldHolder.itemView)
        resetAnimation(oldHolder)
        val deltaX = (toX - fromX - prevTranslationX).toInt()
        val deltaY = (toY - fromY - prevTranslationY).toInt()
        // recover prev translation state after ending animation
        ViewCompat.setTranslationX(oldHolder.itemView, prevTranslationX)
        ViewCompat.setTranslationY(oldHolder.itemView, prevTranslationY)
        ViewCompat.setAlpha(oldHolder.itemView, prevAlpha)
        // carry over translation values
        resetAnimation(newHolder)
        ViewCompat.setTranslationX(newHolder.itemView, -deltaX.toFloat())
        ViewCompat.setTranslationY(newHolder.itemView, -deltaY.toFloat())
        ViewCompat.setAlpha(newHolder.itemView, 0f)
        pendingChanges.add(ChangeInfo(oldHolder, newHolder, fromX, fromY, toX, toY))
        return true
    }

    private fun animateChangeImpl(changeInfo: ChangeInfo) {
        val holder = changeInfo.oldHolder
        val view = holder?.itemView
        val newHolder = changeInfo.newHolder
        val newView = newHolder?.itemView
        if (view != null) {
            val oldViewAnim = ViewCompat.animate(view).setDuration(
                changeDuration
            )
            changeAnimations.add(changeInfo.oldHolder!!)
            oldViewAnim.translationX((changeInfo.toX - changeInfo.fromX).toFloat())
            oldViewAnim.translationY((changeInfo.toY - changeInfo.fromY).toFloat())
            oldViewAnim.alpha(0f).setListener(object : VpaListenerAdapter() {
                override fun onAnimationStart(view: View) {
                    dispatchChangeStarting(changeInfo.oldHolder, true)
                }

                override fun onAnimationEnd(view: View) {
                    oldViewAnim.setListener(null)
                    ViewCompat.setAlpha(view, 1f)
                    ViewCompat.setTranslationX(view, 0f)
                    ViewCompat.setTranslationY(view, 0f)
                    dispatchChangeFinished(changeInfo.oldHolder, true)
                    changeAnimations.remove(changeInfo.oldHolder)
                    dispatchFinishedWhenDone()
                }
            }).start()
        }
        if (newView != null) {
            val newViewAnimation = ViewCompat.animate(newView)
            changeAnimations.add(changeInfo.newHolder!!)
            newViewAnimation.translationX(0f).translationY(0f).setDuration(changeDuration)
                .alpha(1f).setListener(object : VpaListenerAdapter() {
                    override fun onAnimationStart(view: View) {
                        dispatchChangeStarting(changeInfo.newHolder, false)
                    }

                    override fun onAnimationEnd(view: View) {
                        newViewAnimation.setListener(null)
                        ViewCompat.setAlpha(newView, 1f)
                        ViewCompat.setTranslationX(newView, 0f)
                        ViewCompat.setTranslationY(newView, 0f)
                        dispatchChangeFinished(changeInfo.newHolder, false)
                        changeAnimations.remove(changeInfo.newHolder)
                        dispatchFinishedWhenDone()
                    }
                }).start()
        }
    }

    private fun endChangeAnimation(infoList: MutableList<ChangeInfo>, item: ViewHolder) {
        for (i in infoList.indices.reversed()) {
            val changeInfo = infoList[i]
            if (endChangeAnimationIfNecessary(changeInfo, item)) {
                if (changeInfo.oldHolder == null && changeInfo.newHolder == null) {
                    infoList.remove(changeInfo)
                }
            }
        }
    }

    private fun endChangeAnimationIfNecessary(changeInfo: ChangeInfo) {
        if (changeInfo.oldHolder != null) {
            endChangeAnimationIfNecessary(changeInfo, changeInfo.oldHolder!!)
        }
        if (changeInfo.newHolder != null) {
            endChangeAnimationIfNecessary(changeInfo, changeInfo.newHolder!!)
        }
    }

    private fun endChangeAnimationIfNecessary(changeInfo: ChangeInfo, item: ViewHolder): Boolean {
        var oldItem = false
        if (changeInfo.newHolder === item) {
            changeInfo.newHolder = null
        } else if (changeInfo.oldHolder === item) {
            changeInfo.oldHolder = null
            oldItem = true
        } else {
            return false
        }
        ViewCompat.setAlpha(item.itemView, 1f)
        ViewCompat.setTranslationX(item.itemView, 0f)
        ViewCompat.setTranslationY(item.itemView, 0f)
        dispatchChangeFinished(item, oldItem)
        return true
    }

    override fun endAnimation(item: ViewHolder) {
        val view = item.itemView
        // this will trigger end callback which should set properties to their target values.
        ViewCompat.animate(view).cancel()
        // TODO if some other animations are chained to end, how do we cancel them as well?
        for (i in pendingMoves.indices.reversed()) {
            val moveInfo = pendingMoves[i]
            if (moveInfo.holder === item) {
                ViewCompat.setTranslationY(view, 0f)
                ViewCompat.setTranslationX(view, 0f)
                dispatchMoveFinished(item)
                pendingMoves.removeAt(i)
            }
        }
        endChangeAnimation(pendingChanges, item)
        if (pendingRemovals.remove(item)) {
            ViewCompat.setAlpha(view, 1f)
            dispatchRemoveFinished(item)
        }
        if (pendingAdditions.remove(item)) {
            ViewCompat.setAlpha(view, 1f)
            dispatchAddFinished(item)
        }

        for (i in changesList.indices.reversed()) {
            val changes = changesList[i]
            endChangeAnimation(changes, item)
            if (changes.isEmpty()) {
                changesList.removeAt(i)
            }
        }
        for (i in movesList.indices.reversed()) {
            val moves = movesList[i]
            for (j in moves.indices.reversed()) {
                val moveInfo = moves[j]
                if (moveInfo.holder === item) {
                    ViewCompat.setTranslationY(view, 0f)
                    ViewCompat.setTranslationX(view, 0f)
                    dispatchMoveFinished(item)
                    moves.removeAt(j)
                    if (moves.isEmpty()) {
                        movesList.removeAt(i)
                    }
                    break
                }
            }
        }
        for (i in additionsList.indices.reversed()) {
            val additions = additionsList[i]
            if (additions.remove(item)) {
                ViewCompat.setAlpha(view, 1f)
                dispatchAddFinished(item)
                if (additions.isEmpty()) {
                    additionsList.removeAt(i)
                }
            }
        }

        // animations should be ended by the cancel above.
        if (removeAnimations.remove(item) && DEBUG) {
            throw IllegalStateException(
                "after animation is cancelled, item should not be in "
                    + "mRemoveAnimations list"
            )
        }

        if (addAnimations.remove(item) && DEBUG) {
            throw IllegalStateException(
                "after animation is cancelled, item should not be in "
                    + "mAddAnimations list"
            )
        }

        if (changeAnimations.remove(item) && DEBUG) {
            throw IllegalStateException(
                "after animation is cancelled, item should not be in "
                    + "mChangeAnimations list"
            )
        }

        if (moveAnimations.remove(item) && DEBUG) {
            throw IllegalStateException(
                "after animation is cancelled, item should not be in "
                    + "mMoveAnimations list"
            )
        }
        dispatchFinishedWhenDone()
    }

    private fun resetAnimation(holder: ViewHolder) {
        clearInterpolator(holder.itemView)
        endAnimation(holder)
    }

    private fun clearInterpolator(view: View) {
        if (defaultInterpolator == null) {
            defaultInterpolator = ValueAnimator().interpolator
        }
        view.animate().interpolator = defaultInterpolator
    }

    override fun isRunning(): Boolean {
        return (pendingAdditions.isNotEmpty() ||
            pendingChanges.isNotEmpty() ||
            pendingMoves.isNotEmpty() ||
            pendingRemovals.isNotEmpty() ||
            moveAnimations.isNotEmpty() ||
            removeAnimations.isNotEmpty() ||
            addAnimations.isNotEmpty() ||
            changeAnimations.isNotEmpty() ||
            movesList.isNotEmpty() ||
            additionsList.isNotEmpty() ||
            changesList.isNotEmpty())
    }

    /**
     * Check the state of currently pending and running animations. If there are none
     * pending/running, call [.dispatchAnimationsFinished] to notify any
     * listeners.
     */
    private fun dispatchFinishedWhenDone() {
        if (!isRunning) {
            dispatchAnimationsFinished()
        }
    }

    override fun endAnimations() {
        var count = pendingMoves.size
        for (i in count - 1 downTo 0) {
            val item = pendingMoves[i]
            val view = item.holder.itemView
            ViewCompat.setTranslationY(view, 0f)
            ViewCompat.setTranslationX(view, 0f)
            dispatchMoveFinished(item.holder)
            pendingMoves.removeAt(i)
        }
        count = pendingRemovals.size
        for (i in count - 1 downTo 0) {
            val item = pendingRemovals[i]
            dispatchRemoveFinished(item)
            pendingRemovals.removeAt(i)
        }
        count = pendingAdditions.size
        for (i in count - 1 downTo 0) {
            val item = pendingAdditions[i]
            val view = item.itemView
            ViewCompat.setAlpha(view, 1f)
            dispatchAddFinished(item)
            pendingAdditions.removeAt(i)
        }
        count = pendingChanges.size
        for (i in count - 1 downTo 0) {
            endChangeAnimationIfNecessary(pendingChanges[i])
        }
        pendingChanges.clear()
        if (!isRunning) {
            return
        }

        var listCount = movesList.size
        for (i in listCount - 1 downTo 0) {
            val moves = movesList[i]
            count = moves.size
            for (j in count - 1 downTo 0) {
                val moveInfo = moves[j]
                val item = moveInfo.holder
                val view = item.itemView
                ViewCompat.setTranslationY(view, 0f)
                ViewCompat.setTranslationX(view, 0f)
                dispatchMoveFinished(moveInfo.holder)
                moves.removeAt(j)
                if (moves.isEmpty()) {
                    movesList.remove(moves)
                }
            }
        }
        listCount = additionsList.size
        for (i in listCount - 1 downTo 0) {
            val additions = additionsList[i]
            count = additions.size
            for (j in count - 1 downTo 0) {
                val item = additions[j]
                val view = item.itemView
                ViewCompat.setAlpha(view, 1f)
                dispatchAddFinished(item)
                additions.removeAt(j)
                if (additions.isEmpty()) {
                    additionsList.remove(additions)
                }
            }
        }
        listCount = changesList.size
        for (i in listCount - 1 downTo 0) {
            val changes = changesList[i]
            count = changes.size
            for (j in count - 1 downTo 0) {
                endChangeAnimationIfNecessary(changes[j])
                if (changes.isEmpty()) {
                    changesList.remove(changes)
                }
            }
        }

        cancelAll(removeAnimations)
        cancelAll(moveAnimations)
        cancelAll(addAnimations)
        cancelAll(changeAnimations)

        dispatchAnimationsFinished()
    }

    private fun cancelAll(viewHolders: List<ViewHolder>) {
        for (i in viewHolders.indices.reversed()) {
            ViewCompat.animate(viewHolders[i].itemView).cancel()
        }
    }

    private open class VpaListenerAdapter : ViewPropertyAnimatorListener {
        override fun onAnimationStart(view: View) {}

        override fun onAnimationEnd(view: View) {}

        override fun onAnimationCancel(view: View) {}
    }

    companion object {
        private const val DEBUG = false
    }
}
