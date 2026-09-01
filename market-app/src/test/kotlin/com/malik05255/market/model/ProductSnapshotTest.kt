package com.malik05255.market.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductSnapshotTest {
    @Test
    fun exactBarcodePriceUsesExactLabel() {
        val snapshot = ProductSnapshot(
            barcode = "4062139015078",
            currentPrice = 2.60,
            exactBarcodeMatch = true,
            headlineMatchMethod = "exact_barcode"
        )

        assertEquals("السعر الحالي لنفس الباركود", snapshot.currentPriceLabel)
    }

    @Test
    fun canonicalIdentityPriceDoesNotClaimExactBarcode() {
        val snapshot = ProductSnapshot(
            barcode = "4062139015078",
            currentPrice = 2.60,
            exactBarcodeMatch = false,
            headlineMatchMethod = "canonical_identity"
        )

        assertEquals("السعر الحالي لنفس المنتج", snapshot.currentPriceLabel)
    }

    @Test
    fun visualTextMatchIsClearlyLabeled() {
        val snapshot = ProductSnapshot(
            barcode = "6281100642428",
            currentPrice = 2.60,
            exactBarcodeMatch = false,
            headlineMatchMethod = "visual_text_identity"
        )

        assertEquals("السعر الحالي لتطابق بصري لنفس المنتج", snapshot.currentPriceLabel)
    }

    @Test
    fun unknownProvenanceUsesNeutralLabel() {
        val snapshot = ProductSnapshot(
            barcode = "4062139015078",
            currentPrice = 2.60
        )

        assertEquals("السعر الحالي", snapshot.currentPriceLabel)
    }
}
