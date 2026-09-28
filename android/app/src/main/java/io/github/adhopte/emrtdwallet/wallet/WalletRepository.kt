package io.github.adhopte.emrtdwallet.wallet

import android.content.Context
import eu.europa.ec.eudi.wallet.EudiWallet
import eu.europa.ec.eudi.wallet.document.CreateDocumentSettings
import eu.europa.ec.eudi.wallet.document.DocumentExtensions.getDefaultCreateKeySettings
import eu.europa.ec.eudi.wallet.document.IssuedDocument
import eu.europa.ec.eudi.wallet.document.UnsignedDocument
import eu.europa.ec.eudi.wallet.document.credential.IssuerProvidedCredential
import eu.europa.ec.eudi.wallet.document.format.MsoMdocFormat
import io.github.adhopte.emrtdwallet.data.ActivityLog
import io.github.adhopte.emrtdwallet.data.ActivityType
import io.github.adhopte.emrtdwallet.data.EmrtdIssueRequest
import io.github.adhopte.emrtdwallet.data.IssueResponse
import io.github.adhopte.emrtdwallet.data.IssuerApi
import io.github.adhopte.emrtdwallet.data.SelfieUpload
import io.github.adhopte.emrtdwallet.data.b64
import io.github.adhopte.emrtdwallet.data.b64url
import io.github.adhopte.emrtdwallet.R
import io.github.adhopte.emrtdwallet.emrtd.ChipReadResult
import io.github.adhopte.emrtdwallet.emrtd.PassportReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.multipaz.cbor.Cbor

const val PID_DOCTYPE = "eu.europa.ec.eudi.pid.1"
const val PID_NAMESPACE = "eu.europa.ec.eudi.pid.1"

/** Outcome of an issuance attempt: the verification report and, if accepted, the stored PID. */
data class IssuanceOutcome(val response: IssueResponse, val document: IssuedDocument?)

class WalletRepository(context: Context, val wallet: EudiWallet, val api: IssuerApi, val activity: ActivityLog) {

    private val ctx = context.applicationContext
    val passportReader = PassportReader(api)
    val presentation = PresentationController(ctx, wallet, activity)
    val offers = OfferController(ctx, wallet, activity) { refresh() }

    private val _documents = MutableStateFlow<List<IssuedDocument>>(emptyList())
    val documents: StateFlow<List<IssuedDocument>> = _documents.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _documents.value = wallet.getDocuments { it is IssuedDocument }.filterIsInstance<IssuedDocument>()
    }

    fun document(id: String): IssuedDocument? = wallet.getDocumentById(id) as? IssuedDocument

    fun delete(id: String) {
        val name = document(id)?.name ?: ctx.getString(R.string.wallet_repo_document_fallback)
        wallet.deleteDocumentById(id)
        activity.add(ActivityType.DELETED, ctx.getString(R.string.wallet_repo_deleted_title, name),
            ctx.getString(R.string.wallet_repo_deleted_detail))
        refresh()
    }

    /** Create a document with a fresh hardware-backed device key; its COSE key goes into the MSO. */
    private fun createUnsigned(name: String): UnsignedDocument {
        val (secureAreaId, keySettings) = wallet.getDefaultCreateKeySettings()
        val settings = CreateDocumentSettings(
            secureAreaIdentifier = secureAreaId,
            createKeySettings = keySettings,
            credentialPolicy = CreateDocumentSettings.CredentialPolicy.RotatingBatch(numberOfCredentials = 1),
        )
        return wallet.createDocument(MsoMdocFormat(PID_DOCTYPE), settings, null).getOrThrow().also { it.name = name }
    }

    suspend fun issueFromChip(read: ChipReadResult, selfie: SelfieUpload?): IssuanceOutcome =
        issue(ctx.getString(R.string.wallet_repo_pid_from_chip)) { deviceKey ->
        api.issueFromEmrtd(
            EmrtdIssueRequest(
                session_id = read.sessionId,
                sod = read.sod.b64(),
                data_groups = read.dataGroups.mapKeys { it.key.toString() }.mapValues { it.value.b64() },
                active_auth_signature = read.activeAuthSignature?.b64(),
                chip_auth_response = read.chipAuthResponse?.b64(),
                access_control = read.accessControl,
                device_key = deviceKey.b64(),
                selfie = selfie?.selfie?.b64(),
                liveness_frames = selfie?.let { mapOf("turn_left" to it.turnLeft.b64(), "turn_right" to it.turnRight.b64()) },
                liveness_report = selfie?.report,
            )
        )
    }

    suspend fun issueFromImages(
        front: ByteArray,
        back: ByteArray?,
        ocrText: String,
        kind: String,
        uploaded: Boolean,
        selfie: SelfieUpload?,
    ): IssuanceOutcome = issue(ctx.getString(R.string.wallet_repo_pid_from_scan)) { deviceKey ->
        api.issueFromImages(front, back, ocrText, kind, if (uploaded) "upload" else "camera", deviceKey, selfie)
    }

    private suspend fun issue(name: String, call: suspend (ByteArray) -> IssueResponse): IssuanceOutcome {
        val issuerName = ctx.getString(R.string.issuer_display_name)
        val unsigned = withContext(Dispatchers.IO) { createUnsigned(name) }
        try {
            val keyInfo = unsigned.getPoPSigners().first().getKeyInfo()
            val deviceKeyCose = Cbor.encode(keyInfo.publicKey.toCoseKey().toDataItem())
            val response = call(deviceKeyCose)
            val credential = response.credential
            if (response.decision != "accepted" || credential == null) {
                wallet.deleteDocumentById(unsigned.id)
                activity.add(ActivityType.ISSUANCE_REJECTED, ctx.getString(R.string.wallet_repo_pid_not_issued),
                    response.reasons.joinToString("\n"), issuerName)
                return IssuanceOutcome(response, null)
            }
            val issued = withContext(Dispatchers.IO) {
                wallet.storeIssuedDocument(
                    unsigned,
                    listOf(IssuerProvidedCredential(keyInfo.alias, credential.issuer_signed.b64url())),
                ).getOrThrow()
            }
            refresh()
            val pidLabel = ctx.getString(R.string.badge_pid_full_name)
            activity.add(ActivityType.ISSUED, ctx.getString(R.string.wallet_repo_added_pid), name, issuerName, listOf(pidLabel))
            return IssuanceOutcome(response, issued)
        } catch (e: Throwable) {
            runCatching { wallet.deleteDocumentById(unsigned.id) }
            activity.add(ActivityType.ISSUANCE_FAILED, ctx.getString(R.string.wallet_repo_pid_issuance_failed),
                e.message ?: e.toString(), issuerName)
            throw e
        }
    }
}
