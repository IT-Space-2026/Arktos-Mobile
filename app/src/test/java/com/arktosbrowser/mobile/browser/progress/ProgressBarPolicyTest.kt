package com.arktosbrowser.mobile.browser.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MOD09 — AnimatedProgressBar slim. The policy object is pure JVM;
 * source-scan guards keep the view delegating to it and off the
 * legacy view-Animation/queue machinery it replaced.
 */
class ProgressBarPolicyTest {

    private fun moduleFile(path: String): File =
        listOf(path, "app/$path").map(::File).first { it.isFile }

    @Test
    fun `progress clamps into 0-100`() {
        assertEquals(0, ProgressBarPolicy.clampProgress(-50))
        assertEquals(0, ProgressBarPolicy.clampProgress(0))
        assertEquals(50, ProgressBarPolicy.clampProgress(50))
        assertEquals(100, ProgressBarPolicy.clampProgress(100))
        assertEquals(100, ProgressBarPolicy.clampProgress(150))
    }

    @Test
    fun `target width scales progress over the view`() {
        assertEquals(0, ProgressBarPolicy.targetWidthPx(0, 400))
        assertEquals(200, ProgressBarPolicy.targetWidthPx(50, 400))
        assertEquals(400, ProgressBarPolicy.targetWidthPx(100, 400))
    }

    @Test
    fun `target width clamps out-of-range progress`() {
        assertEquals(400, ProgressBarPolicy.targetWidthPx(250, 400))
        assertEquals(0, ProgressBarPolicy.targetWidthPx(-10, 400))
    }

    @Test
    fun `decrease snaps to start when not bidirectional`() {
        assertTrue(
            ProgressBarPolicy.snapsToStart(
                newProgress = 30,
                currentProgress = 80,
                bidirectionalAnimate = false
            )
        )
    }

    @Test
    fun `decrease animates backwards when bidirectional`() {
        assertFalse(
            ProgressBarPolicy.snapsToStart(
                newProgress = 30,
                currentProgress = 80,
                bidirectionalAnimate = true
            )
        )
    }

    @Test
    fun `forward and equal updates never snap`() {
        assertFalse(
            ProgressBarPolicy.snapsToStart(80, 30, bidirectionalAnimate = false)
        )
        assertFalse(
            ProgressBarPolicy.snapsToStart(50, 50, bidirectionalAnimate = false)
        )
    }

    @Test
    fun `bar fades only at full progress`() {
        assertFalse(ProgressBarPolicy.fadesOutAtEnd(0))
        assertFalse(ProgressBarPolicy.fadesOutAtEnd(99))
        assertTrue(ProgressBarPolicy.fadesOutAtEnd(100))
        assertTrue(ProgressBarPolicy.fadesOutAtEnd(140))
    }

    @Test
    fun `view delegates its update rules to the policy`() {
        val view = moduleFile(
            "src/main/java/com/arktosbrowser/mobile/browser/progress/AnimatedProgressBar.kt"
        ).readText()
        assertTrue(
            "AnimatedProgressBar no longer delegates to ProgressBarPolicy",
            view.contains("ProgressBarPolicy.")
        )
    }

    @Test
    fun `view animates with ValueAnimator not legacy view animations`() {
        val view = moduleFile(
            "src/main/java/com/arktosbrowser/mobile/browser/progress/AnimatedProgressBar.kt"
        ).readText()
        assertTrue(view.contains("ValueAnimator"))
        assertFalse(
            "legacy view Animation machinery crept back in",
            view.contains("android.view.animation.Animation") ||
                view.contains("applyTransformation") ||
                view.contains("mAnimationQueue")
        )
    }
}
