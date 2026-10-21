// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.utils

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.rust.ArktosCore

object DrawableUtils {

    /**
     * Creates a rounded square of a certain color with
     * a character imprinted in white on it.
     *
     * @param character the character to write on the image.
     * @param width     the width of the final image.
     * @param height    the height of the final image.
     * @param color     the background color of the rounded square.
     * @return a valid bitmap of a rounded square with a character on it.
     */
    fun createRoundedLetterImage(
        character: Char,
        width: Int,
        height: Int,
        color: Int
    ): Bitmap {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val paint = Paint()
        paint.color = color
        val boldText = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.typeface = boldText
        paint.textSize = Utils.dpToPx(14f).toFloat()
        paint.isAntiAlias = true
        paint.textAlign = Paint.Align.CENTER
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)

        val radius = Utils.dpToPx(2f)

        val outer = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        canvas.drawRoundRect(outer, radius.toFloat(), radius.toFloat(), paint)

        val xPos = canvas.width / 2
        val yPos = (canvas.height / 2 - (paint.descent() + paint.ascent()) / 2).toInt()

        paint.color = Color.WHITE
        canvas.drawText(character.toString(), xPos.toFloat(), yPos.toFloat(), paint)

        return image
    }

    /**
     * Hashes a character to one of four colors:
     * blue, green, red, or orange.
     *
     * @param character the character to hash.
     * @param app       the application needed to get the color.
     * @return one of the above colors, or black something goes wrong.
     */
    @ColorInt
    fun characterToColorHash(character: Char, app: Application): Int {
        val smallHash = Character.getNumericValue(character) % 4
        return when (Math.abs(smallHash)) {
            0 -> ContextCompat.getColor(app, R.color.bookmark_default_blue)
            1 -> ContextCompat.getColor(app, R.color.bookmark_default_green)
            2 -> ContextCompat.getColor(app, R.color.bookmark_default_red)
            3 -> ContextCompat.getColor(app, R.color.bookmark_default_orange)
            else -> Color.BLACK
        }
    }

    // mixColor's math moved to the Rust core (PORT02) —
    // `ArktosCore.mixColorFraction`.
    fun mixColor(fraction: Float, startValue: Int, endValue: Int): Int =
        ArktosCore.mixColorFraction(fraction, startValue, endValue)
}
