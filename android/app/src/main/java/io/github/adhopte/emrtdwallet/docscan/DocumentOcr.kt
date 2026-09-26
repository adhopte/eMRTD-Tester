package io.github.adhopte.emrtdwallet.docscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
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
    fun normalizeJpeg(jpeg: ByteArray, maxSide: Int = 1800): ByteArray {
        var bmp = decodeUpright(jpeg)
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.toByteArray()
        }
    }

    private const val MAX_UPLOAD_BYTES = 25 * 1024 * 1024

    /**
     * Read an image the user picked (gallery / files) and return it as an upright JPEG.
     * Accepts anything Android can decode (JPEG, PNG, WebP, HEIC on Android 9+).
     */
    fun loadImage(context: Context, uri: Uri): ByteArray {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                require(buffer.size() <= MAX_UPLOAD_BYTES) { "Image is larger than 25 MB" }
            }
            buffer.toByteArray()
        } ?: throw IllegalArgumentException("Could not open the selected file")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image format" }
        require(minOf(bounds.outWidth, bounds.outHeight) >= 600) {
            "Image is too small (${bounds.outWidth}×${bounds.outHeight}); use at least 600 px on the short side"
        }
        return normalizeJpeg(bytes)
    }

    private fun decodeUpright(jpeg: ByteArray, maxSide: Int = 4096): Bitmap {
        // Downsample very large photos while decoding to avoid running out of memory
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("not a decodable image")
        val orientation = runCatching {
            ExifInterface(jpeg.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    }
}
