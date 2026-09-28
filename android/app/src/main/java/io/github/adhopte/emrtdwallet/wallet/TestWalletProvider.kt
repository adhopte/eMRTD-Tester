package io.github.adhopte.emrtdwallet.wallet

import android.util.Base64
import eu.europa.ec.eudi.openid4vci.Nonce
import eu.europa.ec.eudi.wallet.provider.WalletAttestationsProvider
import io.github.adhopte.emrtdwallet.data.IssuerApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.securearea.KeyInfo

/**
 * Bridges wallet-core to the backend's TEST Wallet Provider, which signs the Wallet Instance
 * Attestation used for OAuth attestation-based client authentication and attests the device keys
 * (Wallet Unit Attestation) that OpenID4VCI proofs must carry.
 */
class TestWalletProvider(private val api: IssuerApi, private val walletUnitId: () -> String) : WalletAttestationsProvider {

    override suspend fun getWalletAttestation(keyInfo: KeyInfo): Result<String> = runCatching {
        api.walletAttestation(
            JsonObject(mapOf(
                "jwk" to keyInfo.jwk(),
                "client_id" to JsonPrimitive(OfferController.CLIENT_ID),
                "wallet_unit_id" to JsonPrimitive(walletUnitId()),
            ))
        )
    }

    override suspend fun getKeyAttestation(keys: List<KeyInfo>, nonce: Nonce?): Result<String> = runCatching {
        val jwks = keys.map { it.jwk() }
        // Android Keystore attestation chains, recorded by the provider
        val chains = keys.map { info ->
            JsonArray(info.attestation.certChain?.certificates.orEmpty().map {
                JsonPrimitive(Base64.encodeToString(it.encoded.toByteArray(), Base64.NO_WRAP))
            })
        }
        api.keyAttestation(
            JsonObject(buildMap {
                put("keys", JsonArray(jwks))
                nonce?.let { put("nonce", JsonPrimitive(it.value)) }
                put("android_attestation", JsonArray(chains))
                put("wallet_unit_id", JsonPrimitive(walletUnitId()))
            })
        )
    }

    private fun KeyInfo.jwk(): JsonObject {
        val pub = publicKey as? EcPublicKeyDoubleCoordinate
            ?: error("unsupported device key type ${publicKey::class.simpleName}")
        return JsonObject(mapOf(
            "kty" to JsonPrimitive("EC"),
            "crv" to JsonPrimitive("P-256"),
            "x" to JsonPrimitive(pub.x.b64url()),
            "y" to JsonPrimitive(pub.y.b64url()),
        ))
    }

    private fun ByteArray.b64url(): String = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
