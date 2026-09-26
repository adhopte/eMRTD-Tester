package io.github.adhopte.emrtdwallet.ui

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.adhopte.emrtdwallet.WalletApp
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
    data class Finished(val outcome: IssuanceOutcome, val notes: List<String> = emptyList()) : IssuanceState
    data class Failed(val message: String) : IssuanceState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val walletApp = app as WalletApp
    val repository = walletApp.repository
    val settings = walletApp.settings

    private val _accessKey = MutableStateFlow<ChipAccessKey?>(null)
    val accessKey: StateFlow<ChipAccessKey?> = _accessKey.asStateFlow()

    private val _issuance = MutableStateFlow<IssuanceState>(IssuanceState.Idle)
    val issuance: StateFlow<IssuanceState> = _issuance.asStateFlow()

    fun setMrz(mrz: MrzKey) { _accessKey.value = ChipAccessKey.FromMrz(mrz) }
    fun setCan(can: String) { _accessKey.value = ChipAccessKey.Can(can) }
    fun resetIssuance() { _issuance.value = IssuanceState.Idle }

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
                _issuance.value = IssuanceState.Working("Verifying chip and issuing PID…")
                val outcome = repository.issueFromChip(read)
                _issuance.value = IssuanceState.Finished(outcome, read.notes)
            } catch (e: Exception) {
                _issuance.value = IssuanceState.Failed(friendly(e))
            }
        }
    }

    fun submitImages(front: ByteArray, back: ByteArray?, kind: String) {
        _issuance.value = IssuanceState.Working("Reading document text…")
        viewModelScope.launch {
            try {
                val (f, b) = withContext(Dispatchers.Default) {
                    DocumentOcr.normalizeJpeg(front) to back?.let(DocumentOcr::normalizeJpeg)
                }
                val ocr = buildString {
                    append(DocumentOcr.recognize(f))
                    b?.let { append("\n").append(DocumentOcr.recognize(it)) }
                }
                _issuance.value = IssuanceState.Working("Validating document and issuing PID…")
                _issuance.value = IssuanceState.Finished(repository.issueFromImages(f, b, ocr, kind))
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
