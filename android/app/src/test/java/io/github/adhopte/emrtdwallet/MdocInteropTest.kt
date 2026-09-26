package io.github.adhopte.emrtdwallet

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tagged
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.mso.MobileSecurityObjectParser
import kotlinx.io.bytestring.ByteString
import kotlin.time.ExperimentalTime

/**
 * Checks that a PID issued by the backend (fixture produced by backend/scripts/make_fixture.py)
 * is accepted by multipaz — the mdoc stack wallet-core uses to store and present it.
 */
@OptIn(ExperimentalTime::class)
class MdocInteropTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.readBytes()

    @Test
    fun backendIssuedPidParsesAndVerifiesWithMultipaz() = runBlocking {
        val issuerSigned = Cbor.decode(fixture("issuer_signed.cbor"))
        val issuerAuth: CoseSign1 = issuerSigned["issuerAuth"].asCoseSign1

        // x5chain carries the Document Signer certificate
        val x5chain = issuerAuth.unprotectedHeaders[CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)]!!
        val dsCert = X509Cert(ByteString(x5chain.asBstr))
        val iaca = X509Cert(ByteString(fixture("iaca.der")))
        assertEquals(iaca.subject, dsCert.issuer)
        dsCert.verify(iaca.ecPublicKey) // throws if the DS certificate was not signed by the IACA

        // COSE_Sign1 signature over the MSO
        Cose.coseSign1Check(dsCert.ecPublicKey, null, issuerAuth, Algorithm.ES256)

        // MSO content, parsed exactly like MsoMdocCredentialCertifier does when storing it
        val msoBytes = (Cbor.decode(issuerAuth.payload!!) as Tagged).taggedItem.asBstr
        val mso = MobileSecurityObjectParser(msoBytes).parse()
        assertEquals("eu.europa.ec.eudi.pid.1", mso.docType)
        assertEquals(Algorithm.SHA256, mso.digestAlgorithm)
        val deviceKey = CoseKey.fromDataItem(Cbor.decode(fixture("device_key.cbor"))).ecPublicKey
        assertEquals(deviceKey, mso.deviceKey)
        assertTrue(mso.validFrom < mso.validUntil)

        // Every IssuerSignedItem digest matches the MSO
        val namespaces = issuerSigned["nameSpaces"]
        var count = 0
        for ((ns, items) in namespaces.asMap) {
            val digests = mso.getDigestIDs(ns.asTstr)!!
            for (item in items.asArray) {
                val itemBytes = Cbor.encode(item)
                val digestId = Cbor.decode((item as Tagged).taggedItem.asBstr)["digestID"].asNumber
                val expected = digests[digestId]!!
                assertTrue(expected.contentEquals(Crypto.digest(Algorithm.SHA256, itemBytes)))
                count++
            }
        }
        assertTrue("expected PID elements", count > 15)
    }
}
