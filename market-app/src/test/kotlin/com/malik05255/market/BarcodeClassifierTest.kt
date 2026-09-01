package com.malik05255.market

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeClassifierTest {
    @Test
    fun upcPrefix2IsRestrictedCirculation() {
        assertTrue(isRestrictedCirculationBarcode("288845077528"))
        assertTrue(isRestrictedCirculationBarcode("222455077318"))
    }

    @Test
    fun ean13Prefixes20To29AreRestrictedCirculation() {
        assertTrue(isRestrictedCirculationBarcode("2012345678901"))
        assertTrue(isRestrictedCirculationBarcode("2912345678901"))
    }

    @Test
    fun ordinaryGlobalBarcodesAreNotClassifiedAsRestricted() {
        assertFalse(isRestrictedCirculationBarcode("4062139015078"))
        assertFalse(isRestrictedCirculationBarcode("3017620422003"))
        assertFalse(isRestrictedCirculationBarcode("50254156"))
    }
}
