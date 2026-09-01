package com.vibe.supermarket.barcode

/**
 * Resolution pipeline:
 * local cache -> Saudi master -> official sources.
 */
interface BarcodeResolver {
    suspend fun resolve(barcode: String): ProductIdentity?
}

class SaudiBarcodeResolver : BarcodeResolver {
    override suspend fun resolve(barcode: String): ProductIdentity? {
        if (barcode.isBlank()) return null

        // Network implementation is injected later.
        // Keeping the resolver independent from UI avoids scanner lock-in.
        return null
    }
}
