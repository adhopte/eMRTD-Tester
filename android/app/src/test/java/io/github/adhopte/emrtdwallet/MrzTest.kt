package io.github.adhopte.emrtdwallet

import io.github.adhopte.emrtdwallet.emrtd.Mrz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MrzTest {
    @Test
    fun parsesIcaoTd3Specimen() {
        val key = Mrz.parse(
            "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\nL898902C36UTO7408122F1204159ZE184226B<<<<<10"
        )
        assertNotNull(key)
        assertEquals("L898902C3", key!!.documentNumber)
        assertEquals("740812", key.dateOfBirth)
        assertEquals("120415", key.dateOfExpiry)
        assertEquals("TD3", key.format)
    }

    @Test
    fun parsesIcaoTd1Specimen() {
        val key = Mrz.parse(
            "I<UTOD231458907<<<<<<<<<<<<<<<\n7408122F1204159UTO<<<<<<<<<<<6\nERIKSSON<<ANNA<MARIA<<<<<<<<<<"
        )
        assertEquals("D23145890", key?.documentNumber)
        assertEquals("TD1", key?.format)
    }

    @Test
    fun correctsOcrConfusionsInDates() {
        // 'O' read instead of '0' in the birth date is repaired before checking digits
        val key = Mrz.parse(
            "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\nL898902C36UTO74O8122F1204159ZE184226B<<<<<10"
        )
        assertEquals("740812", key?.dateOfBirth)
    }

    @Test
    fun rejectsBadCheckDigit() {
        assertNull(Mrz.parse("P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\nL898902C35UTO7408122F1204159ZE184226B<<<<<10"))
    }
}
