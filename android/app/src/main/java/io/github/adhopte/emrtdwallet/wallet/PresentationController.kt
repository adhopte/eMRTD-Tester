package io.github.adhopte.emrtdwallet.wallet

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import eu.europa.ec.eudi.iso18013.transfer.TransferEvent
import eu.europa.ec.eudi.iso18013.transfer.response.RequestProcessor
import eu.europa.ec.eudi.wallet.EudiWallet
import io.github.adhopte.emrtdwallet.R
import io.github.adhopte.emrtdwallet.data.ActivityLog
import io.github.adhopte.emrtdwallet.data.ActivityType
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
data class RequestedItem(
    val namespace: String,
    val element: String,
    val displayName: String,
    val intentToRetain: Boolean,
    val document: String = "",
)

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
    data class Done(val redirectUri: String?, val verifier: String? = null, val shared: List<String> = emptyList()) : PresentationState
    data class Failed(val message: String) : PresentationState
}

/**
 * Bridges wallet-core transfer events (ISO 18013-5 proximity over BLE/NFC and OpenID4VP
 * remote presentation) to UI state, and turns the user's consent into a response.
 */
class PresentationController(
    context: Context,
    private val wallet: EudiWallet,
    private val activity: ActivityLog,
) : TransferEvent.Listener {

    private val ctx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<PresentationState>(PresentationState.Idle)
    val state: StateFlow<PresentationState> = _state.asStateFlow()

    private var pending: RequestProcessor.ProcessedRequest.Success? = null
    private var transport = "proximity"
    private var verifierName: String? = null
    private var sharedItems: List<String> = emptyList()
    private var sent = false

    init {
        wallet.addTransferEventListener(this)
    }

    fun startProximity() {
        clearSession()
        transport = ctx.getString(R.string.transport_in_person)
        _state.value = PresentationState.Connecting
        wallet.startProximityPresentation()
    }

    fun startRemote(uri: Uri) {
        clearSession()
        transport = remoteTransportName()
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
            is TransferEvent.ResponseSent -> {
                logShared()
                _state.value = PresentationState.Done(null, verifierName, sharedItems)
            }
            is TransferEvent.Redirect -> {
                logShared()
                _state.value = PresentationState.Done(event.redirectUri.toString(), verifierName, sharedItems)
            }
            is TransferEvent.Disconnected -> {
                val s = _state.value
                if (s !is PresentationState.Done && s !is PresentationState.Failed && s !is PresentationState.Idle) {
                    _state.value = if (sent) PresentationState.Done(null, verifierName, sharedItems)
                    else PresentationState.Failed(ctx.getString(R.string.presentation_verifier_disconnected))
                }
                stopTransports()
            }
            is TransferEvent.Error -> {
                Log.w(TAG, "transfer error", event.error)
                val msg = event.error.message ?: event.error.toString()
                activity.add(ActivityType.PRESENTATION_FAILED, ctx.getString(R.string.activity_sharing_failed), msg, verifierName)
                _state.value = PresentationState.Failed(msg)
                stopTransports()
            }
            else -> Log.d(TAG, "unhandled transfer event $event")
        }
    }

    private fun onRequest(event: TransferEvent.RequestReceived) {
        val processed = event.processedRequest
        if (processed is RequestProcessor.ProcessedRequest.Failure) {
            _state.value = PresentationState.Failed(ctx.getString(R.string.presentation_invalid_request, processed.error.message ?: ""))
            return
        }
        val success = processed.getOrThrow()
        val selections = success.presentmentSelections
        if (selections.isEmpty() || selections.all { it.matches.isEmpty() }) {
            _state.value = PresentationState.Failed(ctx.getString(R.string.presentation_credential_not_held))
            return
        }
        pending = success
        val requester = success.requester
        val verifier = success.trustMetadata?.displayName
            ?: requester.origin
            ?: requester.appId
            ?: requester.certChain?.certificates?.firstOrNull()?.subject?.name
            ?: ctx.getString(R.string.presentation_unknown_verifier)
        verifierName = verifier
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
            val docName = runCatching { match.credential.document.displayName }.getOrNull().orEmpty()
            match.claims.map { (requested, claim) ->
                val mdoc = requested as? MdocRequestedClaim
                RequestedItem(
                    namespace = mdoc?.namespaceName ?: (claim as? MdocClaim)?.namespaceName.orEmpty(),
                    element = mdoc?.dataElementName ?: claim.displayName,
                    displayName = claim.displayName,
                    intentToRetain = mdoc?.intentToRetain ?: false,
                    document = docName,
                )
            }
        }

    fun accept(optionIndex: Int = 0) {
        val request = pending ?: return
        _state.value = PresentationState.Sending
        scope.launch {
            try {
                val selection = request.presentmentSelections[optionIndex]
                sharedItems = describe(selection).map { it.displayName }
                // Device keys are created without user-auth requirements, so no unlock data is needed.
                val response = request.generateResponse(selection, emptyMap()).getOrThrow()
                wallet.sendResponse(response)
                sent = true
                // proximity transports stay connected; the event log gets the entry on ResponseSent
            } catch (e: Throwable) {
                Log.w(TAG, "response generation failed", e)
                activity.add(ActivityType.PRESENTATION_FAILED, "Sharing failed", e.message ?: e.toString(), verifierName)
                _state.value = PresentationState.Failed(e.message ?: e.toString())
                stopTransports()
            } finally {
                pending = null
            }
        }
    }

    fun reject() {
        val request = (_state.value as? PresentationState.AwaitingConsent)?.request
        pending = null
        activity.add(ActivityType.PRESENTATION_DECLINED, ctx.getString(R.string.activity_declined_request), transport, verifierName,
            request?.options?.firstOrNull()?.map { it.displayName }.orEmpty())
        if (transport == remoteTransportName()) runCatching { wallet.rejectRemotePresentation() } else stopTransports()
        _state.value = PresentationState.Idle
    }

    private var logged = false

    private fun logShared() {
        if (logged) return
        logged = true
        activity.add(ActivityType.PRESENTED, ctx.getString(R.string.activity_shared_attributes, sharedItems.size), transport, verifierName, sharedItems)
    }

    private fun remoteTransportName() = ctx.getString(R.string.transport_online)

    private fun clearSession() {
        verifierName = null
        sharedItems = emptyList()
        sent = false
        logged = false
        pending = null
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
