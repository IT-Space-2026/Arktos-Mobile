// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.search

import com.arktosbrowser.mobile.R
import android.view.View
import android.widget.ImageView
import android.widget.TextView

class SuggestionViewHolder(view: View) {
    val imageView: ImageView = view.findViewById(R.id.suggestionIcon)
    val titleView: TextView = view.findViewById(R.id.title)
    val urlView: TextView = view.findViewById(R.id.url)
    val suggestionInsert: View = view.findViewById(R.id.suggestionInsert)
}
