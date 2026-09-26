package io.github.adhopte.emrtdwallet.emrtd

/**
 * ICAO 9303 MRZ parsing with check-digit validation. Only the fields needed for chip
 * access (BAC / PACE-MRZ) are extracted: document number, date of birth, date of expiry.
 */
data class MrzKey(
    val documentNumber: String,
    val dateOfBirth: String, // YYMMDD
    val dateOfExpiry: String, // YYMMDD
    val format: String = "",
    val name: String = "",
) {
    val isComplete: Boolean
        get() = documentNumber.isNotBlank() && dateOfBirth.length == 6 && dateOfExpiry.length == 6
}

object Mrz {
    private val WEIGHTS = intArrayOf(7, 3, 1)

    fun checkDigit(data: String): Char {
        var sum = 0
        data.forEachIndexed { i, c ->
            val v = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'Z' -> c - 'A' + 10
                else -> 0
            }
            sum += v * WEIGHTS[i % 3]
        }
        return '0' + sum % 10
    }

    private fun valid(data: String, cd: Char) = checkDigit(data) == cd

    private fun normalize(text: String): List<String> = text.uppercase()
        .replace('«', '<')
        .lines()
        .map { line -> line.replace(" ", "").filter { it.isLetterOrDigit() || it == '<' } }
        .filter { it.length >= 28 }

    /** Try to extract a valid MRZ from OCR text (tolerates OCR confusions in numeric fields). */
    fun parse(text: String): MrzKey? {
        val lines = normalize(text)
        for (i in lines.indices) {
            val l = lines[i]
            // TD3: second line of 44 chars
            if (l.length >= 44 && i > 0) parseTd3Line2(l.take(44), lines[i - 1])?.let { return it }
            // TD1: first line of 30 chars followed by a second line
            if (l.length >= 30 && i + 1 < lines.size && lines[i + 1].length >= 30) {
                parseTd1(l.take(30), lines[i + 1].take(30), lines.getOrNull(i + 2))?.let { return it }
            }
            // TD2
            if (l.length >= 36 && i > 0 && lines[i - 1].length >= 36) parseTd2Line2(l.take(36), lines[i - 1])?.let { return it }
        }
        return null
    }

    private fun digits(s: String) = s.map {
        when (it) { 'O', 'Q', 'D' -> '0'; 'I', 'L' -> '1'; 'Z' -> '2'; 'S' -> '5'; 'B' -> '8'; 'G' -> '6'; else -> it }
    }.joinToString("")

    private fun name(line1: String, from: Int): String =
        line1.drop(from).split("<<", limit = 2).joinToString(", ") { it.replace('<', ' ').trim() }.trim(',', ' ')

    private fun parseTd3Line2(l2: String, l1: String): MrzKey? {
        val doc = l2.substring(0, 9)
        val docCd = digits(l2.substring(9, 10))[0]
        val dob = digits(l2.substring(13, 19))
        val dobCd = digits(l2.substring(19, 20))[0]
        val exp = digits(l2.substring(21, 27))
        val expCd = digits(l2.substring(27, 28))[0]
        if (!valid(doc, docCd) || !valid(dob, dobCd) || !valid(exp, expCd)) return null
        return MrzKey(doc.replace("<", ""), dob, exp, "TD3", if (l1.startsWith("P")) name(l1.take(44), 5) else "")
    }

    private fun parseTd2Line2(l2: String, l1: String): MrzKey? {
        val doc = l2.substring(0, 9)
        val dob = digits(l2.substring(13, 19))
        val exp = digits(l2.substring(21, 27))
        if (!valid(doc, digits(l2.substring(9, 10))[0]) || !valid(dob, digits(l2.substring(19, 20))[0]) ||
            !valid(exp, digits(l2.substring(27, 28))[0])
        ) return null
        return MrzKey(doc.replace("<", ""), dob, exp, "TD2", name(l1.take(36), 5))
    }

    private fun parseTd1(l1: String, l2: String, l3: String?): MrzKey? {
        if (l1[0] !in "IAC") return null
        var doc = l1.substring(5, 14)
        var docCd = l1[14]
        if (docCd == '<') { // long document number continues in the optional field
            val overflow = l1.substring(15).substringBefore('<')
            if (overflow.isEmpty()) return null
            doc += overflow.dropLast(1)
            docCd = overflow.last()
        }
        val dob = digits(l2.substring(0, 6))
        val exp = digits(l2.substring(8, 14))
        if (!valid(doc, docCd) || !valid(dob, digits(l2.substring(6, 7))[0]) ||
            !valid(exp, digits(l2.substring(14, 15))[0])
        ) return null
        return MrzKey(doc.replace("<", ""), dob, exp, "TD1", l3?.let { name(it.take(30), 0) } ?: "")
    }
}
