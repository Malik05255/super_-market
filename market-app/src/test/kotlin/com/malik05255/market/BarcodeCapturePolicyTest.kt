package com.malik05255.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodeCapturePolicyTest {
    @Test
    fun requiresTwoNearbyReadsBeforeAccepting() {
        val gate = BarcodeCapturePolicy(requiredHits = 2, maxGapMs = 900)
        assertNull(gate.observe("4062139015078", 1_000))
        assertEquals("4062139015078", gate.observe("4062139015078", 1_240))
    }

    @Test
    fun resetsWhenCandidateChangesOrGapIsTooLong() {
        val gate = BarcodeCapturePolicy(requiredHits = 2, maxGapMs = 900)
        assertNull(gate.observe("4062139015078", 1_000))
        assertNull(gate.observe("3017620422003", 1_100))
        assertNull(gate.observe("3017620422003", 2_500))
        assertEquals("3017620422003", gate.observe("3017620422003", 2_700))
    }

    @Test
    fun rejectsMalformedOrWrongChecksumValues() {
        assertNull(normalizeRetailBarcode("ABC123"))
        assertNull(normalizeRetailBarcode("4062139015079"))
        assertNull(normalizeRetailBarcode("1234567890"))
    }
}
