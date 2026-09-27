package io.github.adhopte.emrtdwallet.ui

import android.util.Base64

/**
 * wallet-core decodes CBOR byte strings (e.g. `portrait`) as base64url *text*, not bytes.
 * Accept both forms so images always render.
 */
fun imageBytes(value: Any?): ByteArray? = when (value) {
    is ByteArray -> value
    is String -> runCatching {
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }.getOrNull()?.takeIf { it.size > 100 && isImage(it) }
    else -> null
}

private fun isImage(b: ByteArray): Boolean =
    (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||                       // JPEG
        (b.size > 8 && b[4] == 0x6A.toByte() && b[5] == 0x50.toByte()) ||    // JPEG 2000 (jp2)
        (b[0] == 0x89.toByte() && b[1] == 0x50.toByte())                     // PNG

/** Claim names that hold images and must not be printed as text. */
val IMAGE_CLAIMS = setOf("portrait", "signature_usual_mark", "enrolment_portrait_image")
