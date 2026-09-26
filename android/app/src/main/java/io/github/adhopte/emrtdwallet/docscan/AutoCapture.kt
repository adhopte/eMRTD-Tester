package io.github.adhopte.emrtdwallet.docscan

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.adhopte.emrtdwallet.emrtd.Mrz
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** What the camera should wait for before capturing. */
enum class CaptureTarget {
    /** Passport photo page or ID card back: a check-digit-valid MRZ must be readable. */
    MRZ_PAGE,
    /** ID card front: no MRZ; enough printed text spread across the frame. */
    CARD_FRONT,
}

enum class AutoCaptureStatus { SEARCHING, TOO_FAR, WRONG_SIDE, HOLD_STILL, CAPTURE }

/** Text found in one analysed frame. Bounds are normalised to 0..1 of the upright frame. */
data class FrameObservation(
    val mrzValid: Boolean,
    val mrzLikeLines: Int,
    val textLines: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width get() = right - left
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    companion object {
        val EMPTY = FrameObservation(false, 0, 0, 0f, 0f, 0f, 0f)
    }
}

/**
 * Pure decision logic (unit-tested): fire once the target is recognised in [requiredStableFrames]
 * consecutive frames whose text block has barely moved, i.e. the document is readable and steady.
 */
class AutoCaptureDetector(
    private val target: CaptureTarget,
    private val requiredStableFrames: Int = 3,
    private val maxShift: Float = 0.04f,
    private val minTextWidth: Float = 0.45f,
    private val minFrontLines: Int = 5,
) {
    private var stable = 0
    private var last: FrameObservation? = null

    fun onFrame(o: FrameObservation): AutoCaptureStatus {
        val recognised = when (target) {
            CaptureTarget.MRZ_PAGE -> o.mrzValid
            CaptureTarget.CARD_FRONT -> o.mrzLikeLines == 0 && o.textLines >= minFrontLines && o.width >= minTextWidth
        }
        if (!recognised) {
            reset()
            return when {
                target == CaptureTarget.CARD_FRONT && o.mrzLikeLines > 0 -> AutoCaptureStatus.WRONG_SIDE
                o.textLines > 0 && o.width < minTextWidth -> AutoCaptureStatus.TOO_FAR
                else -> AutoCaptureStatus.SEARCHING
            }
        }
        val prev = last
        stable = if (prev != null && abs(prev.centerX - o.centerX) <= maxShift &&
            abs(prev.centerY - o.centerY) <= maxShift && abs(prev.width - o.width) <= maxShift
        ) stable + 1 else 1
        last = o
        return if (stable >= requiredStableFrames) AutoCaptureStatus.CAPTURE else AutoCaptureStatus.HOLD_STILL
    }

    fun reset() {
        stable = 0
        last = null
    }
}

/**
 * CameraX analyzer: runs on-device OCR on preview frames and reports status; calls [onCapture]
 * once per arming when the document is readable and steady. Call [arm] to wait for the next side.
 */
class AutoCaptureAnalyzer(
    target: CaptureTarget,
    private val onStatus: (AutoCaptureStatus) -> Unit,
    private val onCapture: () -> Unit,
) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val detector = AutoCaptureDetector(target)
    private val busy = AtomicBoolean(false)
    private val armed = AtomicBoolean(true)
    private var lastRun = 0L

    fun arm() {
        detector.reset()
        armed.set(true)
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        val now = System.currentTimeMillis()
        // ~4 frames/s is plenty and keeps the phone cool
        if (media == null || !armed.get() || now - lastRun < 250 || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastRun = now
        val rotation = image.imageInfo.rotationDegrees
        val (w, h) = if (rotation % 180 == 0) image.width to image.height else image.height to image.width
        recognizer.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { it.lines }
                val boxes = lines.mapNotNull { it.boundingBox }
                val mrzLike = lines.count { l ->
                    val s = l.text.replace(" ", "")
                    s.length >= 28 && s.count { it == '<' } >= 2
                }
                val obs = if (boxes.isEmpty()) FrameObservation.EMPTY else FrameObservation(
                    mrzValid = Mrz.parse(text.text) != null,
                    mrzLikeLines = mrzLike,
                    textLines = lines.size,
                    left = boxes.minOf { it.left } / w.toFloat(),
                    top = boxes.minOf { it.top } / h.toFloat(),
                    right = boxes.maxOf { it.right } / w.toFloat(),
                    bottom = boxes.maxOf { it.bottom } / h.toFloat(),
                )
                val status = detector.onFrame(obs)
                onStatus(status)
                if (status == AutoCaptureStatus.CAPTURE && armed.compareAndSet(true, false)) onCapture()
            }
            .addOnCompleteListener {
                busy.set(false)
                image.close()
            }
    }
}
