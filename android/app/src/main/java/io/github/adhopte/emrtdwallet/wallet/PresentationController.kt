package io.github.adhopte.emrtdwallet.wallet

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import eu.europa.ec.eudi.iso18013.transfer.TransferEvent
import eu.europa.ec.eudi.iso18013.transfer.response.RequestProcessor
import eu.europa.ec.eudi.wallet.EudiWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.multipaz.claim.MdocClaim
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.request.MdocRequestedClaim

/** A claim the verifier asked for, ready for the consent screen. */
data class RequestedItem(val namespace: String, val element: String, val displayName: String, val intentToRetain: Boolean)

data class ConsentRequest(
    val verifier: String,
    val verifierTrusted: Boolean,
    val transport: String,
    val options: List<List<RequestedItem>>,
)

sealed interface PresentationState {
    data object Idle : PresentationState
    data class QrReady(val qr: Bitmap, val content: String) : PresentationState
    data object Connecting : PresentationState
    data object Connected : PresentationState
    data class AwaitingConsent(val request: ConsentRequest) : PresentationState
    data object Sending : PresentationState
    data class Done(val redirectUri: String?) : PresentationState
    data class Failed(val message: String) : PresentationState
}

/**
 * Bridges wallet-core transfer events (ISO 18013-5 proximity over BLE/NFC and OpenID4VP
 * remote presentation) to UI state, and turns the user's consent into a response.
 */
class PresentationController(context: Context, private val wallet: EudiWallet) : TransferEvent.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<PresentationState>(PresentationState.Idle)
    val state: StateFlow<PresentationState> = _state.asStateFlow()

    private var pending: RequestProcessor.ProcessedRequest.Success? = null
    private var transport = "proximity"

    init {
        wallet.addTransferEventListener(this)
    }

    fun startProximity() {
        transport = "ISO/IEC 18013-5 (BLE)"
        _state.value = PresentationState.Connecting
        wallet.startProximityPresentation()
    }

    fun startRemote(uri: Uri) {
        transport = "OpenID4VP"
        _state.value = PresentationState.Connecting
        wallet.startRemotePresentation(uri)
    }

    override fun onTransferEvent(event: TransferEvent) {
        when (event) {
            is TransferEvent.QrEngagementReady ->
                _state.value = PresentationState.QrReady(event.qrCode.asBitmap(720), event.qrCode.content)
            is TransferEvent.Connecting -> _state.value = PresentationState.Connecting
            is TransferEvent.Connected -> _state.value = PresentationState.Connected
            is TransferEvent.RequestReceived -> onRequest(event)
            is TransferEvent.ResponseSent -> _state.value = PresentationState.Done(null)
            is TransferEvent.Redirect -> _state.value = PresentationState.Done(event.redirectUri.toString())
            is TransferEvent.Disconnected -> {
                if (_state.value !is PresentationState.Done) _state.value = PresentationState.Done(null)
                stopTransports()
            }
            is TransferEvent.Error -> {
                Log.w(TAG, "transfer error", event.error)
                _state.value = PresentationState.Failed(event.error.message ?: event.error.toString())
                stopTransports()
            }
            else -> Log.d(TAG, "unhandled transfer event $event")
        }
    }

    private fun onRequest(event: TransferEvent.RequestReceived) {
        val processed = event.processedRequest
        if (processed is RequestProcessor.ProcessedRequest.Failure) {
            _state.value = PresentationState.Failed("Invalid request: ${processed.error.message}")
            return
        }
        val success = processed.getOrThrow()
        val selections = success.presentmentSelections
        if (selections.isEmpty() || selections.all { it.matches.isEmpty() }) {
            _state.value = PresentationState.Failed("The verifier asked for a credential this wallet does not hold")
            return
        }
        pending = success
        val requester = success.requester
        val verifier = success.trustMetadata?.displayName
            ?: requester.origin
            ?: requester.appId
            ?: requester.certChain?.certificates?.firstOrNull()?.subject?.name
            ?: "Unknown verifier"
        _state.value = PresentationState.AwaitingConsent(
            ConsentRequest(
                verifier = verifier,
                verifierTrusted = success.trustMetadata != null,
                transport = transport,
                options = selections.map(::describe),
            )
        )
    }

    private fun describe(selection: CredentialPresentmentSelection): List<RequestedItem> =
        selection.matches.flatMap { match ->
            match.claims.map { (requested, claim) ->
                val mdoc = requested as? MdocRequestedClaim
                RequestedItem(
                    namespace = mdoc?.namespaceName ?: (claim as? MdocClaim)?.namespaceName.orEmpty(),
                    element = mdoc?.dataElementName ?: claim.displayName,
                    displayName = claim.displayName,
                    intentToRetain = mdoc?.intentToRetain ?: false,
                )
            }
        }

    fun accept(optionIndex: Int = 0) {
        val request = pending ?: return
        _state.value = PresentationState.Sending
        scope.launch {
            try {
                val selection = request.presentmentSelections[optionIndex]
                // Device keys are created without user-auth requirements, so no unlock data is needed.
                val response = request.generateResponse(selection, emptyMap()).getOrThrow()
                wallet.sendResponse(response)
            } catch (e: Throwable) {
                Log.w(TAG, "response generation failed", e)
                _state.value = PresentationState.Failed(e.message ?: e.toString())
                stopTransports()
            } finally {
                pending = null
            }
        }
    }

    fun reject() {
        pending = null
        if (transport == "OpenID4VP") wallet.rejectRemotePresentation() else stopTransports()
        _state.value = PresentationState.Idle
    }

    fun reset() {
        stopTransports()
        pending = null
        _state.value = PresentationState.Idle
    }

    private fun stopTransports() {
        runCatching { wallet.stopProximityPresentation() }
        runCatching { wallet.stopRemotePresentation() }
    }

    private companion object {
        const val TAG = "Presentation"
    }
}
