package io.github.adhopte.emrtdwallet.wallet

import android.net.Uri
import android.util.Log
import com.nimbusds.jose.jwk.Curve
import eu.europa.ec.eudi.openid4vci.CredentialResponseEncryptionPolicy
import eu.europa.ec.eudi.openid4vci.CredentialReusePolicies
import eu.europa.ec.eudi.openid4vci.EncryptionSupportConfig
import eu.europa.ec.eudi.openid4vci.EudiReusePolicyType
import eu.europa.ec.eudi.openid4vci.TxCodeInputMode
import eu.europa.ec.eudi.wallet.EudiWallet
import eu.europa.ec.eudi.wallet.document.CreateDocumentSettings
import eu.europa.ec.eudi.wallet.document.DocumentExtensions.getDefaultCreateDocumentSettings
import eu.europa.ec.eudi.wallet.document.DocumentExtensions.getDefaultCreateKeySettings
import eu.europa.ec.eudi.wallet.document.format.MsoMdocFormat
import eu.europa.ec.eudi.wallet.document.format.SdJwtVcFormat
import eu.europa.ec.eudi.wallet.issue.openid4vci.IssueEvent
import eu.europa.ec.eudi.wallet.issue.openid4vci.Offer
import eu.europa.ec.eudi.wallet.issue.openid4vci.OfferResult
import eu.europa.ec.eudi.wallet.issue.openid4vci.OpenId4VciManager
import io.github.adhopte.emrtdwallet.R
import io.github.adhopte.emrtdwallet.data.ActivityLog
import io.github.adhopte.emrtdwallet.data.ActivityType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OfferedItem(val name: String, val docType: String)

data class TxCodeSpec(val numeric: Boolean, val length: Int?, val description: String?)

sealed interface OfferState {
    data object Idle : OfferState
    data object Resolving : OfferState
    data class Ready(val issuer: String, val items: List<OfferedItem>, val txCode: TxCodeSpec?) : OfferState
    data class Issuing(val message: String) : OfferState
    data class Done(val issuer: String, val issued: List<String>, val failed: List<String>, val deferred: List<String>) : OfferState
    data class Failed(val message: String) : OfferState
}

/**
 * OpenID4VCI credential offers (``openid-credential-offer://`` QR codes and links): resolve the
 * offer, show it for consent, then run the pre-authorized code flow (with transaction code) or
 * the authorization code flow and store the issued credentials.
 */
class OfferController(
    context: android.content.Context,
    private val wallet: EudiWallet,
    private val activity: ActivityLog,
    private val onIssued: () -> Unit,
) {
    private val ctx = context.applicationContext
    private val _state = MutableStateFlow<OfferState>(OfferState.Idle)
    val state: StateFlow<OfferState> = _state.asStateFlow()

    private var offer: Offer? = null
    private var issuerName = ""

    private val publicManager: OpenId4VciManager by lazy { wallet.createOpenId4VciManager(managerConfig(false)) }
    private val attestedManager: OpenId4VciManager by lazy { wallet.createOpenId4VciManager(managerConfig(true)) }
    private var manager: OpenId4VciManager? = null

    fun resolve(uri: String) {
        offer = null
        _state.value = OfferState.Resolving
        publicManager.resolveDocumentOffer(uri) { result ->
            when (result) {
                is OfferResult.Success -> {
                    val o = result.offer
                    offer = o
                    issuerName = o.issuerMetadata.display.firstOrNull()?.name
                        ?: runCatching { Uri.parse(o.issuerMetadata.credentialIssuerIdentifier.toString()).host }.getOrNull()
                        ?: ctx.getString(R.string.issuer_label_default)
                    val items = o.offeredDocuments.map { d ->
                        val docType = when (val f = d.documentFormat) {
                            is MsoMdocFormat -> f.docType
                            is SdJwtVcFormat -> f.vct
                            else -> ""
                        }
                        OfferedItem(d.configuration.credentialMetadata?.display?.firstOrNull()?.name ?: docTypeName(docType, ctx), docType)
                    }
                    val tx = o.txCodeSpec?.let { TxCodeSpec(it.inputMode == TxCodeInputMode.NUMERIC, it.length, it.description) }
                    _state.value = OfferState.Ready(issuerName, items, tx)
                }
                is OfferResult.Failure -> {
                    Log.w(TAG, "offer resolution failed", result.cause)
                    _state.value = OfferState.Failed(ctx.getString(R.string.offer_could_not_read, result.cause.message ?: result.cause.toString()))
                }
            }
        }
    }

    fun accept(txCode: String?) {
        val o = offer ?: return
        val issued = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val deferred = mutableListOf<String>()
        _state.value = OfferState.Issuing(ctx.getString(R.string.offer_connecting_to, issuerName))
        val m = if (requiresClientAttestation(o)) attestedManager else publicManager
        manager = m
        m.issueDocumentByOffer(o, txCode?.takeIf { it.isNotBlank() }) { event ->
            when (event) {
                is IssueEvent.Started -> _state.value = OfferState.Issuing(ctx.getString(R.string.offer_requesting_n, event.total))
                is IssueEvent.DocumentRequiresCreateSettings.OptionalReusePolicy -> {
                    // One credential, reused for every presentation
                    event.resume(
                        wallet.getDefaultCreateDocumentSettings(
                            offeredDocument = event.offeredDocument,
                            credentialPolicy = CreateDocumentSettings.CredentialPolicy.RotatingBatch(numberOfCredentials = 1),
                        )
                    )
                }
                is IssueEvent.DocumentRequiresCreateSettings.MandatoryReusePolicy -> {
                    val (secureArea, keySettings) = wallet.getDefaultCreateKeySettings()
                    event.resume(secureArea, keySettings)
                }
                is IssueEvent.DocumentRequiresUserAuth -> event.cancel("device keys do not require user authentication")
                is IssueEvent.DocumentIssued -> {
                    issued += event.name
                    _state.value = OfferState.Issuing(ctx.getString(R.string.offer_stored_n, event.name))
                }
                is IssueEvent.DocumentFailed -> {
                    Log.w(TAG, "document failed", event.cause)
                    failed += "${event.name}: ${event.cause.message ?: event.cause}"
                }
                is IssueEvent.DocumentDeferred -> deferred += event.name
                is IssueEvent.Finished -> finish(issued, failed, deferred)
                is IssueEvent.Failure -> {
                    Log.w(TAG, "issuance failed", event.cause)
                    val msg = event.cause.message ?: event.cause.toString()
                    activity.add(ActivityType.ISSUANCE_FAILED, ctx.getString(R.string.activity_issuance_failed), msg, issuerName)
                    _state.value = OfferState.Failed(friendly(msg))
                }
                else -> Log.d(TAG, "issue event $event")
            }
        }
    }

    private fun finish(issued: List<String>, failed: List<String>, deferred: List<String>) {
        onIssued()
        if (issued.isNotEmpty()) activity.add(ActivityType.ISSUED, ctx.getString(R.string.activity_added_n, issued.joinToString()),
            "OpenID4VCI", issuerName, issued)
        if (failed.isNotEmpty()) activity.add(ActivityType.ISSUANCE_FAILED, ctx.getString(R.string.activity_issuance_failed),
            failed.joinToString("\n"), issuerName)
        _state.value = OfferState.Done(issuerName, issued, failed, deferred)
    }

    fun decline() {
        if (_state.value is OfferState.Ready) activity.add(ActivityType.ISSUANCE_REJECTED, ctx.getString(R.string.activity_declined_offer), "", issuerName)
        reset()
    }

    fun reset() {
        offer = null
        _state.value = OfferState.Idle
    }

    /** Authorization code flow: the browser redirected back to the wallet. */
    fun resumeAuthorization(uri: Uri) = runCatching { checkNotNull(manager).resumeWithAuthorization(uri) }

    private fun friendly(msg: String): String = when {
        "invalid_grant" in msg || "transaction code" in msg.lowercase() -> ctx.getString(R.string.offer_wrong_tx_code)
        else -> msg
    }

    companion object {
        private const val TAG = "OfferController"

        /**
         * Attestation-based client authentication (Wallet Instance Attestation + PoP) is used only
         * with authorization servers that advertise it: openid4vci-kt refuses it otherwise, and
         * servers that require it reject public clients with invalid_client.
         */
        fun requiresClientAttestation(offer: Offer): Boolean =
            offer.credentialOffer.authorizationServerMetadata.tokenEndpointAuthMethods.orEmpty()
                .any { it.value == "attest_jwt_client_auth" }

        fun managerConfig(attestationBased: Boolean): OpenId4VciManager.Config = OpenId4VciManager.Config.Builder()
            .withClientAuthenticationType(
                if (attestationBased) OpenId4VciManager.ClientAuthenticationType.AttestationBased(CLIENT_ID)
                else OpenId4VciManager.ClientAuthenticationType.None(CLIENT_ID)
            )
            .withAuthFlowRedirectionURI(AUTH_REDIRECT)
            .withParUsage(OpenId4VciManager.Config.ParUsage.IF_SUPPORTED)
            // Encrypt credential responses whenever the issuer supports it, but do not
            // refuse issuers (like this project's backend) that do not
            .withResponseEncryptionConfig(
                EncryptionSupportConfig(Curve.P_256, 2048, CredentialResponseEncryptionPolicy.SUPPORTED)
            )
            .withSupportedCredentialReusePolicies(
                CredentialReusePolicies.Supported(
                    setOf(EudiReusePolicyType.OnceOnly, EudiReusePolicyType.LimitedTime, EudiReusePolicyType.RotatingBatch)
                )
            )
            .build()
        const val CLIENT_ID = "getyourid-wallet"
        const val AUTH_REDIRECT = "eudi-openid4ci://authorize"

        fun isCredentialOffer(text: String): Boolean {
            val t = text.trim()
            return t.startsWith("openid-credential-offer://") || t.startsWith("haip-vci://") ||
                t.startsWith("eudi-openid4ci://credential") ||
                (t.startsWith("https://") && ("credential_offer=" in t || "credential_offer_uri=" in t))
        }
    }
}

fun docTypeName(docType: String, context: android.content.Context): String = when (docType) {
    PID_DOCTYPE -> context.getString(R.string.badge_pid_full_name)
    "org.iso.23220.photoID.1" -> context.getString(R.string.namespace_photo_id)
    "eu.europa.ec.av.1" -> context.getString(R.string.doctype_age_verification)
    "org.iso.18013.5.1.mDL" -> context.getString(R.string.doctype_mdl)
    else -> docType.substringAfterLast('.').ifBlank { docType }
}
