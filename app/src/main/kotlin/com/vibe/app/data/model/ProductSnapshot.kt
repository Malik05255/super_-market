package com.vibe.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProductSnapshot(
    val barcode: String,
    @SerialName("name_ar") val nameAr: String? = null,
    @SerialName("name_en") val nameEn: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("current_price") val currentPrice: Double? = null,
    val currency: String = "SAR",
    val retailer: String? = null,
    @SerialName("price_updated_at") val priceUpdatedAt: String? = null,
    @SerialName("min_30d") val min30d: Double? = null,
    @SerialName("max_30d") val max30d: Double? = null,
    @SerialName("source_count") val sourceCount: Int = 1,
    val confidence: Double? = null,
    @SerialName("cloud_source") val cloudSource: String? = null
) {
    val displayName: String
        get() = nameAr?.takeIf { it.isNotBlank() }
            ?: nameEn?.takeIf { it.isNotBlank() }
            ?: "منتج غير معروف"
}
