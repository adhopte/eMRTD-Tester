package io.github.adhopte.emrtdwallet.emrtd

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import io.github.adhopte.emrtdwallet.data.ChallengeResponse
import io.github.adhopte.emrtdwallet.data.ChipAuthChallenge
import io.github.adhopte.emrtdwallet.data.IssuerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CommandAPDU
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.AccessKeySpec
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.ActiveAuthenticationInfo
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.protocol.EACCAAPDUSender
import org.jmrtd.protocol.EACCAProtocol
import org.jmrtd.protocol.SecureMessagingAPDUSender
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.interfaces.DHPublicKey
import javax.crypto.spec.DHPublicKeySpec

/** How the user unlocks the chip: MRZ-derived key (BAC or PACE) or the 6-digit CAN (PACE only). */
sealed interface ChipAccessKey {
    data class FromMrz(val mrz: MrzKey) : ChipAccessKey
    data class Can(val can: String) : ChipAccessKey
}

/** Everything read from the chip plus the answers to the server's AA / CA challenges. */
class ChipReadResult(
    val sessionId: String,
    val accessControl: String,
    val sod: ByteArray,
    val dataGroups: Map<Int, ByteArray>,
    val activeAuthSignature: ByteArray?,
    val chipAuthResponse: ByteArray?,
    val notes: List<String>,
)

/**
 * Reads an ICAO eMRTD with JMRTD.
 *
 * Genuineness proofs are driven by the issuer backend rather than trusted from the phone:
 *  - Active Authentication signs a server-generated challenge;
 *  - Chip Authentication uses a server-generated ephemeral key and a server-computed
 *    secure-messaging command, so only the backend can check the chip's response.
 */
class PassportReader(private val api: IssuerApi) {

    fun interface Progress {
        fun update(message: String, fraction: Float)
    }

    suspend fun read(tag: Tag, key: ChipAccessKey, progress: Progress): ChipReadResult = withContext(Dispatchers.IO) {
        val isoDep = IsoDep.get(tag) ?: throw IllegalArgumentException("Tag is not an ISO-DEP (ISO 14443-4) chip")
        isoDep.timeout = 15_000
        val cardService = CardService.getInstance(isoDep)
        val notes = mutableListOf<String>()
        cardService.open()
        try {
            val service = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,
                false,
            )
            service.open()

            progress.update("Establishing secure channel…", 0.05f)
            val accessControl = establishAccess(service, key, notes)

            progress.update("Reading security object (EF.SOD)…", 0.1f)
            val sod = service.getInputStream(PassportService.EF_SOD, PassportService.DEFAULT_MAX_BLOCKSIZE).use { it.readBytes() }
            val available = SODFile(sod.inputStream()).dataGroupHashes.keys
            val wanted = WANTED_DGS.filter { it in available }

            val dgs = linkedMapOf<Int, ByteArray>()
            wanted.forEachIndexed { i, dg ->
                progress.update("Reading DG$dg…", 0.15f + 0.6f * i / wanted.size)
                try {
                    dgs[dg] = service.getInputStream(DG_FIDS.getValue(dg), PassportService.DEFAULT_MAX_BLOCKSIZE).use { it.readBytes() }
                } catch (e: Exception) {
                    // Some DGs (e.g. DG3/DG4) are access-protected; the backend reports what is missing.
                    notes += "DG$dg could not be read: ${e.message}"
                    Log.w(TAG, "DG$dg read failed", e)
                }
            }

            progress.update("Requesting challenges from issuer…", 0.8f)
            val challenge = api.challenge(dgs[14])
            challenge.chip_authentication_error?.let { notes += "Issuer could not prepare CA: $it" }

            val aaSignature = dgs[15]?.let { dg15 ->
                progress.update("Active Authentication…", 0.85f)
                runCatching { activeAuthenticate(service, dg15, dgs[14], challenge) }
                    .onFailure { notes += "Active Authentication failed: ${it.message}"; Log.w(TAG, "AA", it) }
                    .getOrNull()
            }

            // CA must be the last chip operation: it replaces the secure-messaging keys.
            val caResponse = challenge.chip_authentication?.let { ca ->
                progress.update("Chip Authentication…", 0.92f)
                runCatching { chipAuthenticate(service, cardService, dgs.getValue(14), ca) }
                    .onFailure { notes += "Chip Authentication failed: ${it.message}"; Log.w(TAG, "CA", it) }
                    .getOrNull()
            }
            progress.update("Chip read complete", 1f)
            ChipReadResult(challenge.session_id, accessControl, sod, dgs, aaSignature, caResponse, notes)
        } finally {
            runCatching { cardService.close() }
        }
    }

    private fun establishAccess(service: PassportService, key: ChipAccessKey, notes: MutableList<String>): String {
        val bacKey: BACKey? = (key as? ChipAccessKey.FromMrz)?.mrz?.let {
            BACKey(it.documentNumber, it.dateOfBirth, it.dateOfExpiry)
        }
        val paceKey: AccessKeySpec? = when (key) {
            is ChipAccessKey.Can -> PACEKeySpec.createCANKey(key.can)
            is ChipAccessKey.FromMrz -> bacKey
        }
        var pace = false
        try {
            val cardAccess = CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE))
            val paceInfo = cardAccess.securityInfos.filterIsInstance<PACEInfo>().firstOrNull()
            if (paceInfo != null && paceKey != null) {
                service.doPACE(paceKey, paceInfo.objectIdentifier, PACEInfo.toParameterSpec(paceInfo.parameterId), null)
                pace = true
            }
        } catch (e: Exception) {
            notes += "PACE not used: ${e.message}"
            Log.i(TAG, "PACE unavailable, falling back to BAC", e)
        }
        service.sendSelectApplet(pace)
        if (!pace) {
            requireNotNull(bacKey) { "This chip does not support PACE; scan the MRZ to use BAC" }
            service.doBAC(bacKey)
        }
        return if (pace) "PACE" else "BAC"
    }

    private fun activeAuthenticate(
        service: PassportService,
        dg15: ByteArray,
        dg14: ByteArray?,
        challenge: ChallengeResponse,
    ): ByteArray {
        val publicKey = DG15File(dg15.inputStream()).publicKey
        val (digest, sigAlg) = if (publicKey.algorithm == "RSA") {
            "SHA-1" to "SHA1WithRSA/ISO9796-2"
        } else {
            val oid = dg14?.let { d ->
                DG14File(d.inputStream()).securityInfos.filterIsInstance<ActiveAuthenticationInfo>()
                    .firstOrNull()?.signatureAlgorithmOID
            }
            "SHA-256" to (ECDSA_OIDS[oid] ?: "SHA256withECDSA")
        }
        val result = service.doAA(publicKey, digest, sigAlg, challenge.aa_challenge.hexToBytes())
        return result.response
    }

    private fun chipAuthenticate(
        service: PassportService,
        rawService: CardService,
        dg14: ByteArray,
        ca: ChipAuthChallenge,
    ): ByteArray {
        val infos = DG14File(dg14.inputStream()).securityInfos.filterIsInstance<ChipAuthenticationPublicKeyInfo>()
        val keyId = ca.key_id?.let { BigInteger.valueOf(it) }
        val chipKeyInfo = infos.firstOrNull { keyId == null || it.keyId == keyId } ?: infos.first()
        val terminalKey = terminalPublicKey(chipKeyInfo.subjectPublicKey, ca.terminal_public_key.hexToBytes())
        // Send the *server's* ephemeral public key under the current (BAC/PACE) secure channel.
        EACCAProtocol.sendPublicKey(EACCAAPDUSender(SecureMessagingAPDUSender(rawService)), service.wrapper, ca.oid, keyId, terminalKey)
        // The chip now uses session keys only the backend (and a genuine chip) can derive.
        // Relay the server-built protected READ BINARY verbatim over the raw channel.
        val response = rawService.transmit(CommandAPDU(ca.protected_command.hexToBytes()))
        return response.bytes
    }

    /** Build a Java public key for the server's ephemeral key on the chip's domain parameters. */
    private fun terminalPublicKey(chipKey: PublicKey, encoded: ByteArray): PublicKey = when (chipKey) {
        is ECPublicKey -> {
            require(encoded[0] == 0x04.toByte()) { "expected an uncompressed EC point" }
            val len = (encoded.size - 1) / 2
            val point = ECPoint(
                BigInteger(1, encoded.copyOfRange(1, 1 + len)),
                BigInteger(1, encoded.copyOfRange(1 + len, encoded.size)),
            )
            KeyFactory.getInstance("EC", BouncyCastleProvider()).generatePublic(ECPublicKeySpec(point, chipKey.params))
        }
        is DHPublicKey -> KeyFactory.getInstance("DH", BouncyCastleProvider())
            .generatePublic(DHPublicKeySpec(BigInteger(1, encoded), chipKey.params.p, chipKey.params.g))
        else -> throw IllegalArgumentException("unsupported chip authentication key ${chipKey.algorithm}")
    }

    private companion object {
        const val TAG = "PassportReader"
        // DG3/DG4 (fingerprints/iris) require Terminal Authentication and are not needed for a PID.
        val WANTED_DGS = listOf(1, 2, 11, 12, 14, 15)
        val DG_FIDS = mapOf(
            1 to PassportService.EF_DG1, 2 to PassportService.EF_DG2, 11 to PassportService.EF_DG11,
            12 to PassportService.EF_DG12, 14 to PassportService.EF_DG14, 15 to PassportService.EF_DG15,
        )
        val ECDSA_OIDS = mapOf(
            "1.2.840.10045.4.1" to "SHA1withECDSA",
            "1.2.840.10045.4.3.1" to "SHA224withECDSA",
            "1.2.840.10045.4.3.2" to "SHA256withECDSA",
            "1.2.840.10045.4.3.3" to "SHA384withECDSA",
            "1.2.840.10045.4.3.4" to "SHA512withECDSA",
        )
    }
}

internal fun String.hexToBytes(): ByteArray {
    val clean = replace(" ", "")
    return ByteArray(clean.length / 2) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
