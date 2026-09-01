package com.vibe.supermarket.barcode

/**
 * Converts barcode domain results into UI friendly data.
 */
object BarcodeResultMapper {
    fun title(result: ProductIdentity?): String {
        return result?.name ?: "Product not found"
    }

    fun subtitle(result: ProductIdentity?): String {
        return result?.brand ?: ""
    }
}
