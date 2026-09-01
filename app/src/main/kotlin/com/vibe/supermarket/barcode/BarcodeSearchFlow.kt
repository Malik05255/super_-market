package com.vibe.supermarket.barcode

/**
 * Final orchestration layer before connecting the real camera UI.
 */
class BarcodeSearchFlow(
    private val scannerAdapter: BarcodeScannerAdapter
) {
    suspend fun execute(scannedValue: String): ProductIdentity? {
        return scannerAdapter.onBarcodeDetected(scannedValue)
    }
}
