package com.vibe.supermarket.barcode

/**
 * Unified barcode flow entry point.
 * Scanner UI should send the scanned value here.
 */
class BarcodePipeline(
    private val resolver: BarcodeResolver
) {
    suspend fun scan(rawBarcode: String): ProductIdentity? {
        val code = rawBarcode.trim()
        if (code.isBlank()) return null
        return resolver.resolve(code)
    }
}
