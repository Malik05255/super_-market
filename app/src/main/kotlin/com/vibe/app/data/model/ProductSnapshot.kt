package com.vibe.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RetailerOffer(
    val retailer: String,
    val price: Double,
    val currency: String = "SAR",
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("branch_key") val branchKey: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val barcode: String? = null
)

@Serializable
data class ProductInfo(
    @SerialName("manufacturing_country") val manufacturingCountry: String? = null,
    @SerialName("manufacturing_places") val manufacturingPlaces: String? = null,
    val ingredients: String? = null,
    val allergens: List<String> = emptyList(),
    @SerialName("positive_notes") val positiveNotes: List<String> = emptyList(),
    @SerialName("caution_notes") val cautionNotes: List<String> = emptyList(),
    @SerialName("nutrition_grade") val nutritionGrade: String? = null,
    @SerialName("nova_group") val novaGroup: Int? = null,
    @SerialName("data_source") val dataSource: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    val hasUsefulData: Boolean
        get() = !manufacturingCountry.isNullOrBlank() ||
            !manufacturingPlaces.isNullOrBlank() ||
            !ingredients.isNullOrBlank() ||
            allergens.isNotEmpty() ||
            positiveNotes.isNotEmpty() ||
            cautionNotes.isNotEmpty() ||
            !nutritionGrade.isNullOrBlank() ||
            novaGroup != null

    val completenessScore: Int
        get() = listOf(
            !manufacturingCountry.isNullOrBlank(),
            !manufacturingPlaces.isNullOrBlank(),
            !ingredients.isNullOrBlank(),
            allergens.isNotEmpty(),
            positiveNotes.isNotEmpty(),
            cautionNotes.isNotEmpty(),
            !nutritionGrade.isNullOrBlank(),
            novaGroup != null
        ).count { it }
}

@Serializable
data class ProductSnapshot(
    val barcode: String,
    @SerialName("canonical_product_id") val canonicalProductId: Long? = null,
    @SerialName("matched_barcodes") val matchedBarcodes: List<String> = emptyList(),
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
    val offers: List<RetailerOffer> = emptyList(),
    @SerialName("product_info") val productInfo: ProductInfo? = null,
    @SerialName("cloud_source") val cloudSource: String? = null
) {
    val displayName: String
        get() = nameAr?.takeIf { it.isNotBlank() }
            ?: nameEn?.takeIf { it.isNotBlank() }
            ?: "منتج غير معروف"
}
