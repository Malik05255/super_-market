package com.vibe.supermarket.barcode

class SaudiMasterBarcodeResolver {

    suspend fun resolve(barcodeInput: String): BarcodeScanResult {
        val barcode = barcodeInput.filter { it.isDigit() }

        if (barcode.length < 8) {
            return BarcodeScanResult(
                barcode = barcode,
                source = "validation",
                found = false
            )
        }

        // Pipeline placeholder:
        // 1. Local cache
        // 2. Saudi Master Barcode DB
        // 3. SFDA live lookup
        // 4. Open sources
        return BarcodeScanResult(
            barcode = barcode,
            source = "saudi_master_pending",
            found = false
        )
    }
}
