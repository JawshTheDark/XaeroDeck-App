package dev.jawsh.labelscan.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelParserTest {
    /** The ACE BISTRO LOAF SOUR label, one OCR line per printed line. */
    private val clean = listOf(
        "C8-30-S", "3 of 7", "ACE BISTRO LOAF SOUR", "DF804",
        "DOUGH 21 OZ", "M012/Q001 09/22", "ITM870628 21 OZ", "ASG#1029801153",
        "UPC#890497000030", "BKY", "8202230", "02326886 08/19",
        "K053", "AD", "A-MF-36-06-004", "135 5470932",
    )

    @Test fun parsesCleanLabel() {
        val d = LabelParser.parse(clean, listOf(ScannedBarcode("1355470932", isProductCode = false)))
        assertEquals("ACE BISTRO LOAF SOUR", d.name)
        assertEquals("DOUGH", d.category)
        assertEquals("890497000030", d.upc)
        assertTrue(d.upcValid)
        assertEquals("870628", d.itemNo)
        assertEquals("21 OZ", d.size)
        assertEquals("BKY", d.dept)
        assertEquals("A-MF-36-06-004", d.slot)
        assertEquals("C8-30-S", d.door)
        assertEquals(3, d.caseNo)
        assertEquals(7, d.caseTotal)
        assertEquals("1029801153", d.asg)
        assertEquals("1355470932", d.caseId)
        assertTrue(d.isComplete)
    }

    @Test fun handlesMergedLinesAndLetterDigitConfusion() {
        val noisy = listOf(
            "C8-30-S 3 of 7 ACE BISTRO LOAF SOUR",
            "DOUGH 21 0Z M012/Q001 09/22",
            "ITM87O628 21 OZ ASG#1O29801153",
            "UPC# 89O497OOOO3O BKY",
            "A - MF - 36 - 06 - 004",
        )
        val d = LabelParser.parse(noisy)
        assertEquals("ACE BISTRO LOAF SOUR", d.name)
        assertEquals("890497000030", d.upc)
        assertTrue(d.upcValid)
        assertEquals("870628", d.itemNo)
        assertEquals("21 OZ", d.size)
        assertEquals("1029801153", d.asg)
        assertEquals("A-MF-36-06-004", d.slot)
        assertEquals(3, d.caseNo)
    }

    @Test fun keepsMisreadUpcButFlagsIt() {
        val d = LabelParser.parse(listOf("UPC#890497000080"))
        assertEquals("890497000080", d.upc)
        assertFalse(d.upcValid)
    }

    @Test fun retailBarcodeBeatsOcr() {
        val d = LabelParser.parse(
            listOf("UPC#890497000080"),
            listOf(ScannedBarcode("890497000030", isProductCode = true)),
        )
        assertEquals("890497000030", d.upc)
    }

    @Test fun findsUnlabelledGtinButNotAsgOrCaseId() {
        val d = LabelParser.parse(listOf("ASG#102980115300", "SOME ITEM NAME", "890497000030"))
        assertEquals("890497000030", d.upc)
    }

    @Test fun emptyInputGivesEmptyData() {
        val d = LabelParser.parse(emptyList())
        assertEquals("", d.upc)
        assertNull(d.caseNo)
        assertEquals(0, d.score())
    }
}
