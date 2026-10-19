package com.arktosbrowser.mobile.browser

/**
 * The text-zoom ladder behind the "Text size" display preference
 * (upstream issue #275 — the 200% maximum was too low).
 *
 * [com.arktosbrowser.mobile.preference.UserPreferences.textSize]
 * persists an index into this ladder where a *lower* index means
 * *larger* text (a historical inversion). Indices below zero were
 * appended at the large end so every index stored by older versions
 * keeps its original zoom level.
 */
object TextZoom {

    /**
     * Index of the largest text size (highest zoom percent).
     */
    const val MIN_INDEX = -4

    /**
     * Index of the smallest text size (lowest zoom percent).
     */
    const val MAX_INDEX = 7

    /**
     * Index used when no preference has been stored (100%).
     */
    const val DEFAULT_INDEX = 3

    /**
     * Seekbar maximum: progress 0 is the smallest size, SEEKBAR_MAX the
     * largest.
     */
    const val SEEKBAR_MAX = MAX_INDEX - MIN_INDEX

    private val zoomPercentByIndex = mapOf(
        -4 to 400,
        -3 to 350,
        -2 to 300,
        -1 to 250,
        0 to 200,
        1 to 150,
        2 to 125,
        3 to 100,
        4 to 75,
        5 to 50,
        6 to 25,
        7 to 20
    )

    /**
     * The WebView `textZoom` percent for [index]. Indices outside the
     * ladder (e.g. written by a different app version) are clamped to
     * the nearest level.
     */
    fun zoomPercent(index: Int): Int =
        zoomPercentByIndex.getValue(index.coerceIn(MIN_INDEX, MAX_INDEX))

    /**
     * A preview text size in sp for [index], scaled from its zoom
     * percent.
     */
    fun previewSp(index: Int): Float = 14f + zoomPercent(index) * 0.09f

    /**
     * The seekbar progress for [index]; the bar grows left-to-right
     * toward larger text while the stored index runs the other way.
     */
    fun toProgress(index: Int): Int = (MAX_INDEX - index).coerceIn(0, SEEKBAR_MAX)

    /**
     * The stored index for a seekbar [progress].
     */
    fun fromProgress(progress: Int): Int = MAX_INDEX - progress.coerceIn(0, SEEKBAR_MAX)
}
