package com.vibe.supermarket.barcode

/**
 * Presentation bridge between scanner UI and barcode controller.
 */
class BarcodeScanViewModel(
    private val controller: BarcodeScanController
) {
    var state: BarcodeScanState = BarcodeScanState.Idle
        private set

    suspend fun submitBarcode(value: String) {
        state = BarcodeScanState.Searching
        state = try {
            val product = controller.scan(value)
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
