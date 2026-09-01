package com.vibe.supermarket.barcode

/**
 * Barcode resolution pipeline:
 * 1. Validate barcode
 * 2. Check local cache
 * 3. Query configured providers
 * 4. Return trusted product identity
 */
interface BarcodeResolver {
    suspend fun resolve(barcode: String): ProductIdentity?
}

class SaudiBarcodeResolver(
    private val localProducts: Map<String, ProductIdentity> = emptyMap()
) : BarcodeResolver {

    override suspend fun resolve(barcode: String): ProductIdentity? {
        val normalized = barcode.trim()

        if (!isValidBarcode(normalized)) {
            return null
        }

        // Fast local lookup before any network request.
        localProducts[normalized]?.let { return it }

        // Remote providers can be injected here later.
        return null
    }

    private fun isValidBarcode(value: String): Boolean {
        return value.length in 8..14 && value.all { it.isDigit() }
    }
}
