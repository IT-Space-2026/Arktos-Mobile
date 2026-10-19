package com.arktosbrowser.mobile.browser.tabs

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.browser.TabsView
import com.arktosbrowser.mobile.controller.UIController
import com.arktosbrowser.mobile.extensions.inflater
import com.arktosbrowser.mobile.list.HorizontalItemAnimator
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.utils.ThemeUtils
import com.arktosbrowser.mobile.utils.Utils
import com.arktosbrowser.mobile.view.SmartCookieView


/**
 * A view which displays browser tabs in a horizontal [RecyclerView].
 */
class TabsDesktopView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    userPreferences: UserPreferences
) : ConstraintLayout(context, attrs, defStyleAttr), TabsView {

    private val uiController = context as UIController
    private val tabsAdapter: TabsDesktopAdapter
    private val tabList: RecyclerView

    init {
        setBackgroundColor(Utils.mixTwoColors(ThemeUtils.getColorBackground(uiController as Context), Color.BLACK, 0.85f))
        context.inflater.inflate(R.layout.tab_strip, this, true)
        findViewById<ImageView>(R.id.new_tab_button).apply {
            setColorFilter(ThemeUtils.getTextColor(uiController as Context))
            setOnClickListener {
                uiController.newTabButtonClicked()
            }
            setOnLongClickListener {
                uiController.newTabButtonLongClicked()
                true
            }
        }

        val layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)

        val animator = HorizontalItemAnimator().apply {
            supportsChangeAnimations = false
            addDuration = 200
            changeDuration = 0
            removeDuration = 200
            moveDuration = 200
        }

        tabsAdapter = TabsDesktopAdapter(context, context.resources, uiController = uiController, userPreferences = userPreferences)

        tabList = findViewById<RecyclerView>(R.id.tabs_list).apply {
            setLayerType(View.LAYER_TYPE_NONE, null)
            itemAnimator = animator
            this.layoutManager = layoutManager
            adapter = tabsAdapter
            setHasFixedSize(true)
        }

        val backgroundColor = Utils.mixTwoColors(ThemeUtils.getColorBackground(uiController as Context), Color.BLACK, 0.85f)
        tabList.setBackgroundColor(backgroundColor)
    }

    override fun tabAdded() {
        displayTabs()
        tabList.postDelayed({ tabList.smoothScrollToPosition(tabsAdapter.itemCount - 1) }, 500)
    }

    override fun tabRemoved(position: Int) {
        displayTabs()
    }

    override fun tabChanged(position: Int) {
        displayTabs()
    }

    private fun displayTabs() {
        tabsAdapter.showTabs(uiController.getTabModel().allTabs.map(SmartCookieView::asTabViewState))
    }

    override fun tabsInitialized() {
        tabsAdapter.notifyDataSetChanged()
    }

    override fun setGoBackEnabled(isEnabled: Boolean) = Unit

    override fun setGoForwardEnabled(isEnabled: Boolean) = Unit

}
