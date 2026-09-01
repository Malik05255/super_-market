package com.malik05255.market

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.malik05255.market.data.CloudflareSource
import com.malik05255.market.data.FirebaseSource
import com.malik05255.market.data.MarketRepository
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
    data class Found(val product: ProductSnapshot, val cloudResponses: Int, val servedFromCache: Boolean) : MarketUiState
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

class MarketViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MarketRepository(
        application,
        listOf(
            CloudflareSource(BuildConfig.CLOUDFLARE_PRODUCTS_URL),
            SupabaseSource(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY),
            FirebaseSource(BuildConfig.FIREBASE_DATABASE_URL)
        )
    )

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
        // Store/scale barcodes cannot be resolved globally. Send them to the same NotFound
        // state so the UI can offer the visual package fallback rather than stopping here.
        if (isRestrictedCirculationBarcode(barcode)) {
            _state.value = MarketUiState.NotFound(barcode)
            return
        }
        viewModelScope.launch {
            _state.value = MarketUiState.Loading(barcode)
            val results = mutableListOf<ProductSnapshot>()
            repository.lookup(barcode).collect { product ->
                results += product
                _state.value = MarketUiState.Found(
                    product = merge(barcode, results),
                    cloudResponses = results.mapNotNull { it.cloudSource }
                        .filterNot { it == MarketRepository.LOCAL_CACHE }.distinct().size,
                    servedFromCache = results.any { it.cloudSource == MarketRepository.LOCAL_CACHE }
                )
            }
            if (results.isEmpty()) _state.value = MarketUiState.NotFound(barcode)
        }
    }

    fun lookupByText(barcode: String, recognizedText: String) {
        val cleanBarcode = barcode.trim()
        val cleanText = recognizedText.trim()
        if (cleanText.length < 4) {
            _state.value = MarketUiState.Error("لم يظهر نص كافٍ للتعرف على المنتج")
            return
        }
        viewModelScope.launch {
            _state.value = MarketUiState.Loading(cleanBarcode)
            val product = repository.lookupByText(cleanBarcode, cleanText)
            _state.value = if (product != null) {
                MarketUiState.Found(
                    product = product.copy(barcode = cleanBarcode),
                    cloudResponses = 1,
                    servedFromCache = false
                )
            } else {
                MarketUiState.NotFound(cleanBarcode)
            }
        }
    }

    private fun merge(barcode: String, results: List<ProductSnapshot>): ProductSnapshot {
        val direct = results.filter { it.currentPrice != null }.maxByOrNull { score(it.priceUpdatedAt) }
        val base = direct ?: results.first()
        val metadata = results.firstOrNull { !it.nameAr.isNullOrBlank() || !it.nameEn.isNullOrBlank() || !it.imageUrl.isNullOrBlank() } ?: base
        val info: ProductInfo? = results.mapNotNull { it.productInfo }.firstOrNull { it.hasUsefulData }
        val offers = results.flatMap { snapshot ->
            if (snapshot.offers.isNotEmpty()) snapshot.offers
            else snapshot.currentPrice?.let { price ->
                listOf(RetailerOffer(snapshot.retailer ?: return@let emptyList<RetailerOffer>(), price, snapshot.currency, snapshot.priceUpdatedAt, barcode = barcode))
            } ?: emptyList()
        }.groupBy { "${it.retailer.lowercase()}|${it.branchKey.orEmpty().lowercase()}|${it.barcode.orEmpty()}" }
            .mapNotNull { (_, list) -> list.maxByOrNull { score(it.updatedAt) } }
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

    private fun score(value: String?): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}
