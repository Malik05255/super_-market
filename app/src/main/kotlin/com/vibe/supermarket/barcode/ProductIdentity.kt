package com.vibe.supermarket.barcode

/**
 * Canonical product identity returned by barcode resolution.
 */
data class ProductIdentity(
    val barcode: String,
    val nameAr: String? = null,
    val nameEn: String? = null,
    val brand: String? = null,
    val category: String? = null,
    val size: String? = null,
    val source: String,
    val confidence: Double
)
