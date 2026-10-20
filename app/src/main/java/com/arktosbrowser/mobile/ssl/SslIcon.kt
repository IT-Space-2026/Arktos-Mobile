package com.arktosbrowser.mobile.ssl

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import com.arktosbrowser.mobile.R

/**
 * Creates the proper [Drawable] to represent the [SslState].
 */
fun Context.createSslDrawableForState(sslState: SslState): Drawable? = when (sslState) {
    is SslState.None -> {
        ResourcesCompat.getDrawable(resources, R.drawable.ic_unsecured, theme)
    }
   is SslState.Valid -> {
       ResourcesCompat.getDrawable(resources, R.drawable.ic_secured, theme)
    }
    is SslState.Invalid -> {
        ResourcesCompat.getDrawable(resources, R.drawable.ic_unsecured_severe, theme)
    }
}

/**
 * The desktop padlock's verdict tint (TRUST11): green when the site is
 * both TLS-secured and on the Tranco/Majestic rank list, amber for
 * secured-but-unranked and plain http, red for a broken certificate.
 * The dark/light variants are tuned for legibility against the window
 * color in each direction.
 */
fun sslVerdictColor(sslState: SslState, rank: Long, darkTheme: Boolean): Int = when {
    sslState is SslState.Invalid -> if (darkTheme) 0xFFF87171.toInt() else 0xFFB91C1C.toInt()
    sslState is SslState.Valid && rank > 0 -> if (darkTheme) 0xFF4ADE80.toInt() else 0xFF15803D.toInt()
    else -> if (darkTheme) 0xFFFBBF24.toInt() else 0xFFB45309.toInt()
}
