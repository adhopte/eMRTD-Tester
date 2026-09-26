package io.github.adhopte.emrtdwallet.docscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

/** Renders scanned-document PDFs to JPEG page images with the platform PdfRenderer. */
object DocumentPdf {
    private const val MAX_BYTES = 25L * 1024 * 1024
    private const val TARGET_DPI = 300f
    private const val MAX_SIDE = 3000

    /**
     * Render up to [maxPages] pages as upright JPEGs (300 DPI, capped at 3000 px on the long side).
     * Throws IllegalArgumentException with a user-facing message on unreadable/encrypted files.
     */
    fun renderPages(context: Context, uri: Uri, maxPages: Int = 2): List<ByteArray> {
        // PdfRenderer needs a seekable file descriptor; content URIs are not always seekable, so copy first.
        val tmp = File.createTempFile("upload", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val copied = input.copyTo(out)
                    require(copied <= MAX_BYTES) { "PDF is larger than 25 MB" }
                }
            } ?: throw IllegalArgumentException("Could not open the selected file")

            val fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: SecurityException) {
                fd.close()
                throw IllegalArgumentException("The PDF is password-protected; remove the password and try again")
            } catch (e: Exception) {
                fd.close()
                throw IllegalArgumentException("Not a readable PDF file")
            }
            renderer.use { pdf ->
                require(pdf.pageCount > 0) { "The PDF has no pages" }
                return (0 until minOf(pdf.pageCount, maxPages)).map { index ->
                    pdf.openPage(index).use { page -> renderPage(page) }
                }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun renderPage(page: PdfRenderer.Page): ByteArray {
        var scale = TARGET_DPI / 72f // PDF units are points (1/72 inch)
        val longest = maxOf(page.width, page.height) * scale
        if (longest > MAX_SIDE) scale *= MAX_SIDE / longest
        val w = (page.width * scale).toInt().coerceAtLeast(1)
        val h = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE) // PDF pages are transparent by default
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
