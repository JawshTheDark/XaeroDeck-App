package dev.jawsh.labelscan.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GtinTest {
    @Test fun validates() {
        assertTrue(Gtin.isValid("890497000030"))
        assertTrue(Gtin.isValid("036000291452"))
        assertTrue(Gtin.isValid("4006381333931"))
        assertFalse(Gtin.isValid("890497000031"))
        assertFalse(Gtin.isValid("89049700003"))
    }

    @Test fun restoresSpreadsheetMangledZeros() {
        assertEquals("036000291452", Gtin.restoreLeadingZeros("36000291452"))
        assertEquals("890497000030", Gtin.restoreLeadingZeros("890497000030"))
    }
}
