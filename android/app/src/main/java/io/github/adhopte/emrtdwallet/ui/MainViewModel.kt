package io.github.adhopte.emrtdwallet.ui

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.adhopte.emrtdwallet.WalletApp
import io.github.adhopte.emrtdwallet.data.SelfieUpload
import io.github.adhopte.emrtdwallet.emrtd.ChipReadResult
import io.github.adhopte.emrtdwallet.liveness.SelfieCapture
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.adhopte.emrtdwallet.docscan.DocumentOcr
import io.github.adhopte.emrtdwallet.emrtd.ChipAccessKey
import io.github.adhopte.emrtdwallet.emrtd.MrzKey
import io.github.adhopte.emrtdwallet.wallet.IssuanceOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface IssuanceState {
    data object Idle : IssuanceState
    data class Working(val message: String, val progress: Float? = null) : IssuanceState
    /** The document was captured / read; the holder's selfie is taken next. */
    data object NeedSelfie : IssuanceState
    data class Finished(val outcome: IssuanceOutcome, val notes: List<String> = emptyList()) : IssuanceState
    data class Failed(val message: String) : IssuanceState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val walletApp = app as WalletApp
    val repository = walletApp.repository
    val settings = walletApp.settings
    val lock = walletApp.lock
    val activity = walletApp.activity
    val offers = repository.offers

    private class ImageSubmission(val front: ByteArray, val back: ByteArray?, val kind: String, val uploaded: Boolean)
    private var pendingChip: ChipReadResult? = null
    private var pendingImages: ImageSubmission? = null

    private val _accessKey = MutableStateFlow<ChipAccessKey?>(null)
    val accessKey: StateFlow<ChipAccessKey?> = _accessKey.asStateFlow()

    private val _issuance = MutableStateFlow<IssuanceState>(IssuanceState.Idle)
    val issuance: StateFlow<IssuanceState> = _issuance.asStateFlow()

    fun setMrz(mrz: MrzKey) { _accessKey.value = ChipAccessKey.FromMrz(mrz) }
    fun setCan(can: String) { _accessKey.value = ChipAccessKey.Can(can) }
    fun resetIssuance() {
        _issuance.value = IssuanceState.Idle
        pendingChip = null
        pendingImages = null
    }

    /** Called when the user starts adding a PID, so a sleeping backend is awake before the chip read. */
    fun warmUpIssuer() {
        viewModelScope.launch { repository.api.warmUp() }
    }

    fun onPassportTag(tag: Tag) {
        val key = _accessKey.value ?: return
        if (_issuance.value is IssuanceState.Working) return
        _issuance.value = IssuanceState.Working("Hold the document still against the phone…", 0f)
        viewModelScope.launch {
            try {
                val read = repository.passportReader.read(tag, key) { message, fraction ->
                    _issuance.value = IssuanceState.Working(message, fraction)
                }
                pendingChip = read
                _issuance.value = IssuanceState.NeedSelfie
            } catch (e: Exception) {
                _issuance.value = IssuanceState.Failed(friendly(e))
            }
        }
    }

    /** Document images are ready: continue with the selfie. */
    fun prepareImages(front: ByteArray, back: ByteArray?, kind: String, uploaded: Boolean) {
        pendingImages = ImageSubmission(front, back, kind, uploaded)
        _issuance.value = IssuanceState.NeedSelfie
    }

    /** Submit the pending chip read or document images, with the selfie (null = skipped). */
    fun submitWithSelfie(capture: SelfieCapture?) {
        val selfie = capture?.let { SelfieUpload(it.selfie, it.turnLeft, it.turnRight, it.report.toJson()) }
        pendingChip?.let { read ->
            _issuance.value = IssuanceState.Working(
                if (selfie != null) "Verifying chip, matching your selfie and issuing PID…" else "Verifying chip and issuing PID…")
            viewModelScope.launch {
                try {
                    val outcome = repository.issueFromChip(read, selfie)
                    _issuance.value = IssuanceState.Finished(outcome, read.notes)
                } catch (e: Exception) {
                    _issuance.value = IssuanceState.Failed(friendly(e))
                }
            }
            return
        }
        val images = pendingImages ?: return
        submitImages(images.front, images.back, images.kind, images.uploaded, selfie)
    }

    private fun submitImages(front: ByteArray, back: ByteArray?, kind: String, uploaded: Boolean, selfie: SelfieUpload?) {
        _issuance.value = IssuanceState.Working("Reading document text…")
        viewModelScope.launch {
            try {
                val (f, b) = withContext(Dispatchers.Default) {
                    DocumentOcr.normalizeJpeg(front) to back?.let(DocumentOcr::normalizeJpeg)
                }
                // OCR at higher resolution than the upload: on full-page scans the MRZ is small
                val ocr = buildString {
                    append(DocumentOcr.recognize(withContext(Dispatchers.Default) { DocumentOcr.normalizeJpeg(front, 3000) }))
                    back?.let {
                        val hi = withContext(Dispatchers.Default) { DocumentOcr.normalizeJpeg(it, 3000) }
                        append("\n").append(DocumentOcr.recognize(hi))
                    }
                }
                _issuance.value = IssuanceState.Working(
                    if (selfie != null) "Validating document, matching your selfie and issuing PID…"
                    else "Validating document and issuing PID…")
                _issuance.value = IssuanceState.Finished(repository.issueFromImages(f, b, ocr, kind, uploaded, selfie))
            } catch (e: Exception) {
                _issuance.value = IssuanceState.Failed(friendly(e))
            }
        }
    }

    private fun friendly(e: Exception): String = when {
        e.javaClass.simpleName == "TagLostException" || e.message?.contains("Tag was lost") == true ->
            "Connection to the chip was lost. Keep the document on the phone until reading completes."
        e is java.net.ConnectException || e is java.net.UnknownHostException ->
            "Cannot reach the issuer at ${settings.issuerUrl}. Check Settings."
        e.javaClass.simpleName.contains("CardServiceException") && e.message?.contains("BAC") == true ->
            "Access denied by the chip: check document number, date of birth and expiry (or CAN)."
        else -> e.message ?: e.toString()
    }
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { it.key.toString() to it.value.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

private fun Map<String, Any>.toJson(): JsonObject = toJsonElement() as JsonObject
