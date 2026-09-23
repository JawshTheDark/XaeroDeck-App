package dev.jawsh.labelscan.parse

/** A barcode ML Kit decoded from the photo. */
data class ScannedBarcode(val value: String, val isProductCode: Boolean)

/** Fields pulled off one warehouse case label. Empty string = not found. */
data class LabelData(
    val name: String = "",
    val category: String = "",
    val upc: String = "",
    val itemNo: String = "",
    val size: String = "",
    val dept: String = "",
    val slot: String = "",
    val door: String = "",
    val caseNo: Int? = null,
    val caseTotal: Int? = null,
    val caseId: String = "",
    val asg: String = "",
    val rawText: String = "",
) {
    val upcValid: Boolean get() = Gtin.isValid(upc)

    /** How much of the label was understood; used to pick the best OCR rotation. */
    fun score(): Int =
        (if (upcValid) 10 else if (upc.isNotEmpty()) 3 else 0) +
            (if (itemNo.isNotEmpty()) 3 else 0) +
            (if (name.isNotEmpty()) 3 else 0) +
            listOf(size, slot, door, asg, dept).count { it.isNotEmpty() } +
            (if (caseNo != null) 1 else 0)

    val isComplete: Boolean get() = upcValid && name.isNotEmpty() && itemNo.isNotEmpty()
}

/**
 * Turns OCR'd label text into [LabelData]. Tuned for grocery-DC case labels like:
 *
 *   C8-30-S   3 of 7   ACE BISTRO LOAF SOUR
 *   DOUGH 21 OZ        M012/Q001 09/22
 *   ITM870628 21 OZ    ASG#1029801153
 *   UPC#890497000030   BKY
 *   ...                A-MF-36-06-004
 *
 * Every field is optional; the review screen lets the user fix what's missing.
 */
object LabelParser {
    private const val DIGITISH = "[0-9OoIlSBZ|]"

    private val UPC = Regex("""U\s?P\s?C\s?[#:*]?\s?([0-9OoIlSBZ| ]{8,20})""")
    private val ITEM = Regex("""\bI\s?T\s?M\s?#?\s?($DIGITISH{4,10})""")
    private val ASG = Regex("""\bA\s?S\s?G\s?#?\s?($DIGITISH{6,14})""")
    private val SIZE = Regex(
        """\b(\d+(?:\.\d+)?)\s?(FL\s?[O0]Z|[O0]Z|LBS?|CT|PK|KG|ML|GAL|EA|G|L)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CASE = Regex("""\b(\d{1,3})\s+[oO0][fF]\s+(\d{1,3})\b""")
    private val SLOT = Regex("""\b([A-Z])\s?-\s?([A-Z0-9]{1,3})\s?-\s?(\d{1,3})\s?-\s?(\d{1,3})\s?-\s?(\d{1,4})\b""")
    private val DOOR = Regex("""\b([A-Z]\d{1,2}-\d{1,3}-[A-Z])\b""")
    private val DEPT = Regex("""^[A-Z]{3}$""")
    private val LOOSE_GTIN = Regex("""(?<!\d)(\d{12,13})(?!\d)""")
    private val NOT_DEPT = setOf("UPC", "ITM", "ASG", "LBS", "GAL")

    fun parse(lines: List<String>, barcodes: List<ScannedBarcode> = emptyList()): LabelData {
        val clean = lines.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        val text = clean.joinToString("\n")

        val asg = ASG.find(text)?.let { Gtin.digitize(it.groupValues[1]) }?.takeIf { '?' !in it } ?: ""
        val caseId = barcodes.firstOrNull { !it.isProductCode }?.value?.trim() ?: ""
        val case = CASE.find(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            val t = m.groupValues[2].toInt()
            if (n in 1..t) n to t else null
        }
        val slot = SLOT.find(text)?.groupValues?.drop(1)?.joinToString("-") ?: ""
        val door = DOOR.find(text)?.groupValues?.get(1) ?: ""

        return LabelData(
            name = findName(clean),
            category = findCategory(clean),
            upc = findUpc(clean, barcodes, exclude = setOf(asg, caseId)),
            itemNo = ITEM.find(text)?.let { Gtin.digitize(it.groupValues[1]) }?.takeIf { '?' !in it } ?: "",
            size = SIZE.find(text)?.let { formatSize(it) } ?: "",
            dept = clean.firstOrNull { DEPT.matches(it) && it !in NOT_DEPT } ?: "",
            slot = slot,
            door = door,
            caseNo = case?.first,
            caseTotal = case?.second,
            caseId = caseId,
            asg = asg,
            rawText = text,
        )
    }

    private fun formatSize(m: MatchResult): String {
        val unit = m.groupValues[2].uppercase().replace('0', 'O').replace(" ", "")
        return "${m.groupValues[1]} ${if (unit == "FLOZ") "FL OZ" else unit}"
    }

    private fun findUpc(lines: List<String>, barcodes: List<ScannedBarcode>, exclude: Set<String>): String {
        // 1. A retail barcode on the box is exact — trust it over OCR.
        barcodes.firstOrNull { it.isProductCode && Gtin.isValid(it.value) }?.let { return it.value }

        // 2. The "UPC#" field on the label.
        var fallback = ""
        for (line in lines) {
            val m = UPC.find(line) ?: continue
            val digits = Gtin.digitize(m.groupValues[1]).substringBefore('?')
            for (len in listOf(12, 13, 14, 8)) {
                if (digits.length >= len && Gtin.isValid(digits.take(len))) return digits.take(len)
            }
            if (fallback.isEmpty() && digits.length >= 11) fallback = digits.take(12)
        }
        if (fallback.isNotEmpty()) return fallback

        // 3. Any stray 12/13-digit run that checksums, e.g. when "UPC#" itself was misread.
        return lines.asSequence()
            .flatMap { LOOSE_GTIN.findAll(it).map { m -> m.groupValues[1] } }
            .firstOrNull { Gtin.isValid(it) && it !in exclude } ?: ""
    }

    /** Word-ish lines: mostly letters, at least two words, no field prefixes. */
    private fun textCandidates(lines: List<String>): List<String> = lines.mapNotNull { raw ->
        var s = raw.replace(CASE, " ").replace(DOOR, " ").replace(SLOT, " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        val compact = s.replace(" ", "")
        val letters = compact.count { it.isLetter() }
        when {
            s.length < 5 || s.split(' ').size < 2 -> null
            Regex("""^(UPC|ITM|ASG)""").containsMatchIn(s) -> null
            '#' in s || '/' in s -> null
            letters < compact.length * 0.7 -> null
            else -> s
        }
    }

    /** The product description: the longest candidate without digits, if any. */
    private fun findName(lines: List<String>): String {
        val candidates = textCandidates(lines)
        return candidates.filter { c -> c.none { it.isDigit() } }.maxByOrNull { it.length }
            ?: candidates.firstOrNull { !SIZE.containsMatchIn(it) }
            ?: ""
    }

    /** A secondary "DOUGH 21 OZ"-style line: words followed by a size. */
    private fun findCategory(lines: List<String>): String {
        val name = findName(lines)
        return textCandidates(lines).firstOrNull { it != name && SIZE.containsMatchIn(it) }
            ?.let { SIZE.replace(it, "").trim() } ?: ""
    }
}
