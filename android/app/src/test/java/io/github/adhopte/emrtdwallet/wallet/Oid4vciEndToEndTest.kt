package io.github.adhopte.emrtdwallet.wallet

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import eu.europa.ec.eudi.wallet.EudiWallet
import eu.europa.ec.eudi.wallet.EudiWalletConfig
import eu.europa.ec.eudi.wallet.document.CreateDocumentSettings
import eu.europa.ec.eudi.wallet.document.IssuedDocument
import eu.europa.ec.eudi.wallet.document.format.MsoMdocData
import eu.europa.ec.eudi.wallet.issue.openid4vci.IssueEvent
import eu.europa.ec.eudi.wallet.issue.openid4vci.OfferResult
import io.github.adhopte.emrtdwallet.data.IssuerApi
import io.github.adhopte.emrtdwallet.ui.imageBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * End to end against a running backend: EUDI wallet-core's OpenId4VciManager resolves an
 * offer created through the issuer API, redeems the pre-authorized code with the transaction
 * code, gets the device keys attested by the TEST Wallet Provider and stores the credentials.
 *
 * Needs network: `./gradlew :app:testDebugUnitTest -Pe2eIssuer=https://emrtd-pid-issuer.onrender.com`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@OptIn(kotlin.time.ExperimentalTime::class)
class Oid4vciEndToEndTest {

    @Test
    fun preAuthorizedCodeFlowWithTxCode() = runBlocking {
        val issuer = System.getProperty("e2eIssuer").orEmpty()
        assumeTrue("set -Pe2eIssuer=<backend url> to run", issuer.isNotBlank())
        val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
        val portrait = File("../../backend/tests/fixtures/face_public_domain.jpg").readBytes()
        val body = """{"subject":{"family_name":"Mustermann","given_name":"Erika","birth_date":"1964-08-12",
            "sex":"F","nationality":"DEU","document_number":"C01X00T47","document_expiry":"2031-10-31",
            "portrait":"${Base64.encodeToString(portrait, Base64.NO_WRAP)}"}}"""
        val created = http.newCall(Request.Builder().url("$issuer/api/v1/oid4vci/offers")
            .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use {
            Json.parseToJsonElement(it.body!!.string()) as JsonObject
        }
        val offerUri = created["credential_offer_uri"]!!.jsonPrimitive.content
        val txCode = created["tx_code"]!!.jsonPrimitive.content

        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val config = EudiWalletConfig().configureDocumentManager(File(ctx.cacheDir, "e2e.db").absolutePath)
        val wallet = EudiWallet(ctx, config, TestWalletProvider(IssuerApi { issuer }) { "e2e-test" }) {
            withStorage(storage)
            withSecureAreas(listOf(secureArea))
        }
        val manager = wallet.createOpenId4VciManager(OfferController.managerConfig())
        val executor = Executors.newSingleThreadExecutor()

        val resolved = CompletableDeferred<OfferResult>()
        manager.resolveDocumentOffer(offerUri, executor) { resolved.complete(it) }
        val offer = (withTimeout(90_000) { resolved.await() } as? OfferResult.Success
            ?: error("offer not resolved: ${(resolved.await() as OfferResult.Failure).cause}")).offer
        assertEquals(3, offer.offeredDocuments.size)
        assertNotNull(offer.txCodeSpec)

        val finished = CompletableDeferred<List<String>>()
        val failures = mutableListOf<String>()
        manager.issueDocumentByOffer(offer, txCode, executor) { event ->
            when (event) {
                is IssueEvent.DocumentRequiresCreateSettings.OptionalReusePolicy -> event.resume(
                    CreateDocumentSettings(
                        secureAreaIdentifier = secureArea.identifier,
                        createKeySettings = SoftwareCreateKeySettings.Builder().build(),
                        credentialPolicy = CreateDocumentSettings.CredentialPolicy.RotatingBatch(numberOfCredentials = 1),
                    )
                )
                is IssueEvent.DocumentRequiresCreateSettings.MandatoryReusePolicy ->
                    event.resume(secureArea.identifier, SoftwareCreateKeySettings.Builder().build())
                is IssueEvent.DocumentFailed -> failures += "${event.name}: ${event.cause}"
                is IssueEvent.Failure -> finished.completeExceptionally(event.cause)
                is IssueEvent.Finished -> finished.complete(event.issuedDocuments)
                else -> Unit
            }
        }
        val issued = withTimeout(120_000) { finished.await() }
        assertEquals("failures: $failures", 3, issued.size)
        val docs = issued.map { wallet.getDocumentById(it) as IssuedDocument }
        val types = docs.map { (it.format as eu.europa.ec.eudi.wallet.document.format.MsoMdocFormat).docType }.toSet()
        assertEquals(setOf("eu.europa.ec.eudi.pid.1", "org.iso.23220.photoID.1", "eu.europa.ec.av.1"), types)
        val pid = docs.first { (it.format as eu.europa.ec.eudi.wallet.document.format.MsoMdocFormat).docType == "eu.europa.ec.eudi.pid.1" }
        val claims = (pid.data as MsoMdocData).nameSpacedDataDecoded["eu.europa.ec.eudi.pid.1"]!!
        assertEquals("MUSTERMANN", claims["family_name"])
        assertNotNull("portrait decodes", imageBytes(claims["portrait"]))
        executor.shutdown()
    }
}
