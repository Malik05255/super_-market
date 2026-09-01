package com.vibe.supermarket.barcode

class BarcodeScanController(
    private val scanner: BarcodeScannerAdapter
) {
    suspend fun scan(value: String): BarcodeScanState {
        if (value.isBlank()) return BarcodeScanState.Error("Empty barcode")

        return try {
            val product = scanner.onBarcodeDetected(value)
            if (product != null) {
                BarcodeScanState.Found(product)
            } else {
                BarcodeScanState.NotFound
            }
        } catch (e: Exception) {
            BarcodeScanState.Error(e.message ?: "Unknown error")
        }
    }
}
