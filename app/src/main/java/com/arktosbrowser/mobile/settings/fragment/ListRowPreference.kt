package com.arktosbrowser.mobile.settings.fragment

import android.content.Context
import android.util.AttributeSet
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreference
import com.arktosbrowser.mobile.R

/**
 * A list-management settings row (PREFS01): an enable switch plus a
 * per-row refresh button — the mobile rendering of the desktop
 * `AdBlockDialog` subscriptions table's enabled checkbox +
 * "Update Subscription" action pair.
 *
 * Tapping the row toggles the switch as usual; tapping the refresh
 * icon invokes [onRefresh] (and only makes sense while the list is
 * enabled, so the button is dimmed and inert when unchecked).
 */
class ListRowPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwitchPreference(context, attrs) {

    /** Invoked when the row's refresh button is tapped. */
    var onRefresh: (() -> Unit)? = null

    init {
        widgetLayoutResource = R.layout.preference_list_row_widget
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(R.id.list_row_refresh)?.apply {
            isEnabled = this@ListRowPreference.isChecked
            alpha = if (isEnabled) ENABLED_ALPHA else DISABLED_ALPHA
            setOnClickListener { onRefresh?.invoke() }
        }
    }

    companion object {
        private const val ENABLED_ALPHA = 1.0f
        private const val DISABLED_ALPHA = 0.38f
    }
}
