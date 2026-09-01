package com.malik05255.market

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.malik05255.market.data.CloudflareSource
import com.malik05255.market.data.FirebaseSource
import com.malik05255.market.data.MarketRepository
import com.malik05255.market.data.OpenFactsIdentitySource
import com.malik05255.market.data.SupabaseSource
import com.malik05255.market.model.ProductInfo
import com.malik05255.market.model.ProductSnapshot
import com.malik05255.market.model.RetailerOffer
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MarketUiState {
    data object Idle : MarketUiState
    data class Loading(val barcode: String) : MarketUiState
    data class Found(
        val product: ProductSnapshot,
        val cloudResponses: Int,
        val servedFromCache: Boolean,
        val allowProductMedia: Boolean,
        val storeMediaRetailers: Set<String>
    ) : MarketUiState
    data class NotFound(val barcode: String) : MarketUiState
    data class Error(val message: String) : MarketUiState
}

internal fun isRestrictedCirculationBarcode(barcode: String): Boolean {
    if (!barcode.all(Char::isDigit)) return false
    return when (barcode.length) {
        12 -> barcode.startsWith("2")
        13 -> barcode.take(2).toIntOrNull() in 20..29
        8 -> barcode.startsWith("2")
        else -> false
    }
}

internal fun normalizedRetailerKey(value: String): String = value.trim().lowercase()

class MarketViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MarketRepository(
        application,
        listOf(
            CloudflareSource(BuildConfig.CLOUDFLARE_PRODUCTS_URL),
            SupabaseSource(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY),
            OpenFactsIdentitySource(),
            FirebaseSource(BuildConfig.FIREBASE_DATABASE_URL)
        )
    )
    private val mediaDemand = MediaDemandTracker(application)

    private val _state = MutableStateFlow<MarketUiState>(MarketUiState.Idle)
    val state: StateFlow<MarketUiState> = _state.asStateFlow()

    private val _healthyClouds = MutableStateFlow(0)
    val healthyClouds: StateFlow<Int> = _healthyClouds.asStateFlow()
    val configuredClouds: Int get() = repository.configuredClouds

    init {
        viewModelScope.launch { _healthyClouds.value = repository.healthyClouds() }
    }

    fun scannerFailed(message: String?) {
        _state.value = MarketUiState.Error(message?.takeIf { it.isNotBlank() } ?: "تعذر تشغيل الكاميرا")
    }

    fun lookup(raw: String?) {
        val barcode = raw?.trim().orEmpty()
        if (!barcode.matches(Regex("^[0-9]{8,14}$"))) {
            _state.value = MarketUiState.Error("الباركود غير صالح")
            return
        }
        if (isRestrictedCirculationBarcode(barcode)) {
            _state.value = MarketUiState.NotFound(barcode)
            return
        }

        val allowProductMedia = mediaDemand.recordProductScan(barcode)
        viewModelScope.launch {
            _state.value = MarketUiState.Loading(barcode)
            val results = mutableListOf<ProductSnapshot>()
            val recordedRetailers = mutableSetOf<String>()

            repository.lookup(barcode).collect { product ->
                results += product
                val merged = merge(barcode, results)

                merged.offers.forEach { offer ->
                    val retailerKey = normalizedRetailerKey(offer.retailer)
                    if (recordedRetailers.add(retailerKey)) {
                        mediaDemand.recordRetailerSeen(offer.retailer, barcode)
                    }
                }

                val storeMediaRetailers = merged.offers
                    .asSequence()
                    .filter { mediaDemand.isRetailerMediaAllowed(it.retailer) }
                    .map { normalizedRetailerKey(it.retailer) }
                    .toSet()

                _state.value = MarketUiState.Found(
                    product = merged,
                    cloudResponses = results.mapNotNull { it.cloudSource }
                        .filterNot { it == MarketRepository.LOCAL_CACHE }
                        .distinct()
                        .size,
                    servedFromCache = results.any { it.cloudSource == MarketRepository.LOCAL_CACHE },
                    allowProductMedia = allowProductMedia || mediaDemand.isProductMediaAllowed(barcode),
                    storeMediaRetailers = storeMediaRetailers
                )
            }
            if (results.isEmpty()) _state.value = MarketUiState.NotFound(barcode)
        }
    }

    private fun merge(barcode: String, results: List<ProductSnapshot>): ProductSnapshot {
        val direct = results.filter { it.currentPrice != null }.maxByOrNull { score(it.priceUpdatedAt) }
        val base = direct ?: results.first()
        val metadata = results.firstOrNull {
            !it.nameAr.isNullOrBlank() || !it.nameEn.isNullOrBlank() || !it.imageUrl.isNullOrBlank()
        } ?: base
        val info: ProductInfo? = results.mapNotNull { it.productInfo }.firstOrNull { it.hasUsefulData }
        val offers = results.flatMap { snapshot ->
            if (snapshot.offers.isNotEmpty()) snapshot.offers
            else snapshot.currentPrice?.let { price ->
                listOf(
                    RetailerOffer(
                        retailer = snapshot.retailer ?: return@let emptyList<RetailerOffer>(),
                        price = price,
                        currency = snapshot.currency,
                        updatedAt = snapshot.priceUpdatedAt,
                        barcode = barcode
                    )
                )
            } ?: emptyList()
        }.groupBy {
            "${it.retailer.lowercase()}|${it.branchKey.orEmpty().lowercase()}|${it.barcode.orEmpty()}"
        }.mapNotNull { (_, list) -> list.maxByOrNull { score(it.updatedAt) } }
            .sortedBy { it.price }

        return base.copy(
            barcode = barcode,
            nameAr = metadata.nameAr ?: base.nameAr,
            nameEn = metadata.nameEn ?: base.nameEn,
            imageUrl = metadata.imageUrl ?: base.imageUrl,
            currentPrice = base.currentPrice,
            retailer = base.retailer,
            priceUpdatedAt = base.priceUpdatedAt,
            min30d = results.mapNotNull { it.min30d }.minOrNull() ?: base.min30d,
            max30d = results.mapNotNull { it.max30d }.maxOrNull() ?: base.max30d,
            offers = offers,
            matchedBarcodes = results.flatMap { it.matchedBarcodes }.distinct(),
            productInfo = info ?: base.productInfo
        )
    }

    private fun score(value: String?): Long =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}
