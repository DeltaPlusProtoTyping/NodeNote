package com.nodenote.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FormatNumberTest {

    @Test
    fun roundsToTwoDecimalsAndDropsTrailingZeros() {
        assertEquals("22.78", formatNumber(22.78f))
        assertEquals("1.5", formatNumber(1.5f))
        assertEquals("100", formatNumber(100f))
        assertEquals("0.05", formatNumber(0.05f))
        assertEquals("12.35", formatNumber(12.3456f))
        assertEquals("-2.25", formatNumber(-2.25f))
        assertEquals("12345.67", formatNumber(12345.67f))
        assertEquals("0.502", formatNumber(128 / 255.0, 3))
        assertEquals("0.48", formatNumber(0.48f, 6))
    }

    @Test
    fun neverWritesExponentsOrNegativeZero() {
        assertEquals("0", formatNumber(1.0E-4f))
        assertEquals("0", formatNumber(-0.001f))
        assertEquals("0", formatNumber(-0f))
        assertEquals("0", formatNumber(Float.NaN))
        assertEquals("12345678", formatNumber(1.2345678E7))
        for (v in listOf(1e-10, 3e-3, -7.5e-5, 1e9, 123456.789, 1e20, -1e-30)) {
            val s = formatNumber(v)
            assertFalse('E' in s || 'e' in s, "$v -> $s")
        }
    }
}
