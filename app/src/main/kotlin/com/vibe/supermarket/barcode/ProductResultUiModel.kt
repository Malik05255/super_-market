package com.vibe.supermarket.barcode

/**
 * UI-ready product result model.
 */
data class ProductResultUiModel(
    val barcode: String,
    val name: String,
    val brand: String? = null,
    val status: String = "FOUND"
)
