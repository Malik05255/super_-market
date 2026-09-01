package com.vibe.supermarket.barcode

/**
 * Adapter used by camera scanner UI.
 * Keeps scanner implementation separate from product resolution.
 */
class BarcodeScannerAdapter(
    private val pipeline: BarcodePipeline
) {
    suspend fun onBarcodeDetected(value: String): ProductIdentity? {
        return pipeline.scan(value)
    }
}
