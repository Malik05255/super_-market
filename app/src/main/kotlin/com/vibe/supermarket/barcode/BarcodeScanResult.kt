package com.vibe.supermarket.barcode

/** Result returned after resolving a scanned barcode. */
data class BarcodeScanResult(
    val barcode: String,
    val productName: String? = null,
    val brand: String? = null,
    val source: String,
    val found: Boolean
)
