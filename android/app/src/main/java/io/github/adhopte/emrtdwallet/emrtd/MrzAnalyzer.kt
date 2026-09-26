package io.github.adhopte.emrtdwallet.emrtd

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/** CameraX analyzer that runs ML Kit OCR on frames until a check-digit-valid MRZ is found. */
class MrzAnalyzer(private val onMrz: (MrzKey) -> Unit) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    private val done = AtomicBoolean(false)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null || done.get() || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        recognizer.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { text ->
                Mrz.parse(text.text)?.let {
                    if (done.compareAndSet(false, true)) onMrz(it)
                }
            }
            .addOnCompleteListener {
                busy.set(false)
                image.close()
            }
    }
}
