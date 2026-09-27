package io.github.adhopte.emrtdwallet.liveness

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** The frames and report sent to the issuer, which runs the face match and re-checks the poses. */
class SelfieCapture(
    val selfie: ByteArray,
    val turnLeft: ByteArray,
    val turnRight: ByteArray,
    val report: Map<String, Any>,
)

enum class LivenessStep(val instruction: String) {
    CENTER("Look straight at the camera"),
    BLINK("Blink slowly"),
    TURN("Turn your head slowly to one side"),
    TURN_OTHER("Now turn your head to the other side"),
    DONE("Done"),
}

data class LivenessUi(
    val step: LivenessStep,
    val hint: String? = null,
    val faceOk: Boolean = false,
    val completed: Int = 0,
    val total: Int = 4,
)

/**
 * Active liveness with ML Kit face detection: the user blinks and turns the head both ways in a
 * random order, then looks straight at the camera for the selfie. One frame per pose is kept;
 * the server verifies that the frames show the same person and that the head really turned.
 *
 * This raises the bar above a printed photo or a static screen; it is not a certified presentation
 * attack detection (ISO/IEC 30107-3) and a video replay could defeat it.
 */
class LivenessAnalyzer(
    private val onUi: (LivenessUi) -> Unit,
    private val onComplete: (SelfieCapture) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.25f)
            .build()
    )

    // Random order of the blink and head-turn challenges; the frontal selfie is taken last
    private val steps: List<LivenessStep> = run {
        val middle = if (Math.random() < 0.5) listOf(LivenessStep.BLINK, LivenessStep.TURN, LivenessStep.TURN_OTHER)
        else listOf(LivenessStep.TURN, LivenessStep.TURN_OTHER, LivenessStep.BLINK)
        middle + LivenessStep.CENTER
    }
    private var index = 0
    private var stable = 0
    private var eyesWereOpen = false
    private var eyesClosedSeen = false
    private var firstTurnYaw = 0f
    private var turnA: Pair<Float, ByteArray>? = null
    private var turnB: Pair<Float, ByteArray>? = null
    private val started = SystemClock.elapsedRealtime()
    private var finished = false

    val currentStep: LivenessStep get() = if (finished) LivenessStep.DONE else steps[index]

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (finished || media == null) {
            proxy.close(); return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces -> handle(faces, proxy, input.width, input.height) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun handle(faces: List<Face>, proxy: ImageProxy, width: Int, height: Int) {
        val step = steps[index]
        fun ui(hint: String?, ok: Boolean) {
            if (!ok) stable = 0
            onUi(LivenessUi(step, hint, ok, index, steps.size))
        }
        if (faces.isEmpty()) return ui("Show your face inside the oval", false)
        if (faces.size > 1) return ui("Only one person should be in view", false)
        val face = faces[0]
        // input width/height are before rotation; the smaller side is the upright width for portrait
        val frameWidth = minOf(width, height).toFloat()
        if (face.boundingBox.width() < 0.28f * frameWidth) return ui("Move closer", false)
        val yaw = face.headEulerAngleY
        val roll = face.headEulerAngleZ
        val left = face.leftEyeOpenProbability ?: 1f
        val right = face.rightEyeOpenProbability ?: 1f
        when (step) {
            LivenessStep.CENTER -> {
                if (abs(yaw) > 10 || abs(roll) > 12) return ui("Face the camera and keep your head straight", false)
                if (left < 0.6f || right < 0.6f) return ui("Keep your eyes open", false)
                if (++stable < 4) return ui("Hold still…", true)
                val selfie = proxy.toUprightJpeg()
                advance()
                finish(selfie)
                return
            }
            LivenessStep.BLINK -> {
                if (left > 0.7f && right > 0.7f) {
                    if (eyesClosedSeen) { advance(); return ui(null, true) }
                    eyesWereOpen = true
                } else if (eyesWereOpen && left < 0.25f && right < 0.25f) {
                    eyesClosedSeen = true
                }
                return ui(null, true)
            }
            LivenessStep.TURN -> {
                if (abs(yaw) < TURN_DEGREES) { stable = 0; return ui(null, true) }
                if (++stable < 2) return ui("Hold it there…", true)
                firstTurnYaw = yaw
                turnA = yaw to proxy.toUprightJpeg()
                advance()
                return ui(null, true)
            }
            LivenessStep.TURN_OTHER -> {
                if (abs(yaw) < TURN_DEGREES || yaw * firstTurnYaw > 0) { stable = 0; return ui(null, true) }
                if (++stable < 2) return ui("Hold it there…", true)
                turnB = yaw to proxy.toUprightJpeg()
                advance()
                return ui(null, true)
            }
            LivenessStep.DONE -> Unit
        }
    }

    private fun advance() {
        stable = 0
        if (index < steps.lastIndex) index++
    }

    private fun finish(selfie: ByteArray) {
        val a = turnA ?: return
        val b = turnB ?: return
        finished = true
        // ML Kit: positive Euler Y = face turned towards the right of the (unmirrored) image
        val (pos, neg) = if (a.first > 0) a to b else b to a
        onUi(LivenessUi(LivenessStep.DONE, null, true, steps.size, steps.size))
        onComplete(
            SelfieCapture(
                selfie = selfie,
                turnLeft = pos.second,
                turnRight = neg.second,
                report = mapOf(
                    "method" to "mlkit_face_active_challenge",
                    "challenges" to steps.map { it.name.lowercase() },
                    "blink_detected" to eyesClosedSeen,
                    "yaw_turn_left_deg" to pos.first.toDouble(),
                    "yaw_turn_right_deg" to neg.first.toDouble(),
                    "duration_ms" to (SystemClock.elapsedRealtime() - started),
                ),
            )
        )
        detector.close()
    }

    fun close() = runCatching { detector.close() }

    private companion object {
        const val TURN_DEGREES = 22f
    }
}

/** Rotate the camera frame upright and encode it as a JPEG of at most 720 px. */
private fun ImageProxy.toUprightJpeg(): ByteArray {
    var bmp = toBitmap()
    val rotation = imageInfo.rotationDegrees
    val scale = 720f / maxOf(bmp.width, bmp.height)
    val m = Matrix().apply {
        if (rotation != 0) postRotate(rotation.toFloat())
        if (scale < 1f) postScale(scale, scale)
    }
    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    return ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        out.toByteArray()
    }
}
