package io.github.adhopte.emrtdwallet.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

fun String.b64url(): ByteArray = Base64.decode(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

@Serializable
data class ChallengeRequest(val dg14: String? = null)

@Serializable
data class ChipAuthChallenge(
    val oid: String,
    val key_id: Long? = null,
    val agreement: String,
    val terminal_public_key: String,
    val protected_command: String,
    val read_sfi: Int,
    val read_length: Int,
)

@Serializable
data class ChallengeResponse(
    val session_id: String,
    val aa_challenge: String,
    val chip_authentication: ChipAuthChallenge? = null,
    val chip_authentication_error: String? = null,
    val expires_in: Int,
)

@Serializable
data class EmrtdIssueRequest(
    val session_id: String,
    val sod: String,
    val data_groups: Map<String, String>,
    val active_auth_signature: String? = null,
    val chip_auth_response: String? = null,
    val access_control: String? = null,
    val device_key: String,
    val selfie: String? = null,
    val liveness_frames: Map<String, String>? = null,
    val liveness_report: JsonObject? = null,
)

/** Selfie frames for the issuer's face match against the document portrait + liveness re-check. */
class SelfieUpload(val selfie: ByteArray, val turnLeft: ByteArray, val turnRight: ByteArray, val report: JsonObject)

@Serializable
data class Credential(
    val format: String,
    val doctype: String,
    val issuer_signed: String,
    val valid_until: String,
    val claims: JsonObject,
    val has_portrait: Boolean = false,
)

@Serializable
data class ReportCheck(val name: String, val status: String, val detail: String = "", val data: JsonElement? = null)

@Serializable
data class ReportSection(val name: String, val status: String, val checks: List<ReportCheck>)

@Serializable
data class OfferedCredential(val id: String, val name: String)

@Serializable
data class AttestationOffer(val uri: String, val credentials: List<OfferedCredential> = emptyList())

@Serializable
data class IssueResponse(
    val decision: String,
    val reasons: List<String> = emptyList(),
    val score: Double? = null,
    val report: List<ReportSection> = emptyList(),
    val credential: Credential? = null,
    val attestation_offer: AttestationOffer? = null,
)

class IssuerException(message: String) : IOException(message)

/** Client for the PID issuer backend (see backend/app/main.py). */
class IssuerApi(private val baseUrl: () -> String) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    /** Wake the backend (hosted free tiers sleep when idle) before a time-critical NFC read. */
    suspend fun warmUp(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(baseUrl() + "/health").build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun challenge(dg14: ByteArray?): ChallengeResponse =
        post("/api/v1/emrtd/challenge", json.encodeToString(ChallengeRequest.serializer(), ChallengeRequest(dg14?.b64())))
            .let { json.decodeFromString(ChallengeResponse.serializer(), it) }

    /** TEST Wallet Provider: key attestation (WUA) for the given device keys. */
    suspend fun keyAttestation(body: JsonObject): String =
        post("/wallet-provider/key-attestation", body.toString())
            .let { (json.parseToJsonElement(it) as JsonObject)["key_attestation"]!!.toString().trim('"') }

    /** TEST Wallet Provider: Wallet Instance Attestation for OAuth attestation-based client authentication. */
    suspend fun walletAttestation(body: JsonObject): String =
        post("/wallet-provider/wallet-attestation", body.toString())
            .let { (json.parseToJsonElement(it) as JsonObject)["wallet_attestation"]!!.toString().trim('"') }

    suspend fun issueFromEmrtd(req: EmrtdIssueRequest): IssueResponse =
        post("/api/v1/emrtd/issue", json.encodeToString(EmrtdIssueRequest.serializer(), req))
            .let { json.decodeFromString(IssueResponse.serializer(), it) }

    suspend fun issueFromImages(
        front: ByteArray,
        back: ByteArray?,
        ocrText: String,
        documentKind: String,
        imageSource: String,
        deviceKey: ByteArray,
        selfie: SelfieUpload?,
    ): IssueResponse = withContext(Dispatchers.IO) {
        val jpeg = "image/jpeg".toMediaType()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("front", "front.jpg", front.toRequestBody(jpeg))
            .apply { if (back != null) addFormDataPart("back", "back.jpg", back.toRequestBody(jpeg)) }
            .addFormDataPart("device_ocr_text", ocrText)
            .addFormDataPart("document_kind", documentKind)
            .addFormDataPart("image_source", imageSource)
            .addFormDataPart("device_key", deviceKey.b64())
            .apply {
                if (selfie != null) {
                    addFormDataPart("selfie", "selfie.jpg", selfie.selfie.toRequestBody(jpeg))
                    addFormDataPart("liveness_left", "left.jpg", selfie.turnLeft.toRequestBody(jpeg))
                    addFormDataPart("liveness_right", "right.jpg", selfie.turnRight.toRequestBody(jpeg))
                    addFormDataPart("liveness_report", selfie.report.toString())
                }
            }
            .build()
        val text = execute(Request.Builder().url(baseUrl() + "/api/v1/document/issue").post(body).build())
        json.decodeFromString(IssueResponse.serializer(), text)
    }

    private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(baseUrl() + path).post(body.toRequestBody(jsonType)).build())
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code in 502..504) {
                // Render (and most hosts) answer 502/503/504 themselves while the service restarts or wakes up
                throw IssuerException(
                    "The issuer server is restarting or waking up (HTTP ${resp.code}). Wait a moment and tap Verify again."
                )
            }
            if (!resp.isSuccessful) {
                val detail = runCatching {
                    (json.parseToJsonElement(text) as JsonObject)["detail"].toString()
                }.getOrDefault(text.take(200))
                throw IssuerException("Issuer returned HTTP ${resp.code}: $detail")
            }
            return text
        }
    }
}
