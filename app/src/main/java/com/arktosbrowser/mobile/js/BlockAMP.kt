// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.js

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject


class BlockAMP @Inject constructor() {

    fun provideJs(context: Context): String {
        val inputStream = context.assets.open("AmpBlock.js")
        val bufferedReader = BufferedReader(InputStreamReader(inputStream))
        return bufferedReader.use { it.readText() }
    }

}