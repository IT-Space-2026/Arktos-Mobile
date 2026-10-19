package com.arktosbrowser.mobile.js

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

/**
 * Loads the in-page translation walker. `%%TARGET_JSON%%` must be
 * replaced with a JSON-quoted language tag before injection — see
 * [com.arktosbrowser.mobile.translate.TranslateBridge].
 */
class TranslatePage @Inject constructor() {

    fun provideJs(context: Context): String {
        val inputStream = context.assets.open("TranslatePage.js")
        val bufferedReader = BufferedReader(InputStreamReader(inputStream))
        return bufferedReader.use { it.readText() }
    }
}
