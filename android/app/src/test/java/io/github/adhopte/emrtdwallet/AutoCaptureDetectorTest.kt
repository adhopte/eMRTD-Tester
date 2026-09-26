package io.github.adhopte.emrtdwallet

import io.github.adhopte.emrtdwallet.docscan.AutoCaptureDetector
import io.github.adhopte.emrtdwallet.docscan.AutoCaptureStatus
import io.github.adhopte.emrtdwallet.docscan.CaptureTarget
import io.github.adhopte.emrtdwallet.docscan.FrameObservation
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCaptureDetectorTest {
    private fun page(mrz: Boolean, mrzLines: Int = if (mrz) 2 else 0, lines: Int = 12, dx: Float = 0f, width: Float = 0.8f) =
        FrameObservation(mrz, mrzLines, lines, 0.1f + dx, 0.2f, 0.1f + dx + width, 0.8f)

    @Test
    fun passportCapturesAfterThreeSteadyFramesWithValidMrz() {
        val d = AutoCaptureDetector(CaptureTarget.MRZ_PAGE)
        assertEquals(AutoCaptureStatus.HOLD_STILL, d.onFrame(page(true)))
        assertEquals(AutoCaptureStatus.HOLD_STILL, d.onFrame(page(true)))
        assertEquals(AutoCaptureStatus.CAPTURE, d.onFrame(page(true)))
    }

    @Test
    fun movementRestartsTheCountdown() {
        val d = AutoCaptureDetector(CaptureTarget.MRZ_PAGE)
        d.onFrame(page(true))
        d.onFrame(page(true))
        assertEquals(AutoCaptureStatus.HOLD_STILL, d.onFrame(page(true, dx = 0.1f))) // moved
        assertEquals(AutoCaptureStatus.HOLD_STILL, d.onFrame(page(true, dx = 0.1f)))
        assertEquals(AutoCaptureStatus.CAPTURE, d.onFrame(page(true, dx = 0.1f)))
    }

    @Test
    fun unreadableMrzNeverCaptures() {
        val d = AutoCaptureDetector(CaptureTarget.MRZ_PAGE)
        repeat(10) { assertEquals(AutoCaptureStatus.SEARCHING, d.onFrame(page(false, mrzLines = 2, width = 0.8f).copy(textLines = 0))) }
    }

    @Test
    fun cardFrontRejectsTheMrzSideAndFarAwayCards() {
        val d = AutoCaptureDetector(CaptureTarget.CARD_FRONT)
        assertEquals(AutoCaptureStatus.WRONG_SIDE, d.onFrame(page(true)))
        assertEquals(AutoCaptureStatus.TOO_FAR, d.onFrame(page(false, width = 0.2f)))
        d.onFrame(page(false))
        d.onFrame(page(false))
        assertEquals(AutoCaptureStatus.CAPTURE, d.onFrame(page(false)))
    }
}
