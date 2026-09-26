package io.github.adhopte.emrtdwallet.docscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

/** On-device OCR of captured document photos; the text is sent to the issuer with the images. */
object DocumentOcr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(jpeg: ByteArray): String {
        val bitmap = decodeUpright(jpeg)
        return recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
    }

    /** Decode a JPEG, apply its EXIF orientation and re-encode it upright at a bounded size. */
    fun normalizeJpeg(jpeg: ByteArray, maxSide: Int = 2400): ByteArray {
        var bmp = decodeUpright(jpeg)
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.toByteArray()
        }
    }

    private fun decodeUpright(jpeg: ByteArray): Bitmap {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: throw IllegalArgumentException("not a decodable image")
        val rotation = when (ExifInterface(jpeg.inputStream()).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    }
}
