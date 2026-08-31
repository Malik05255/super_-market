package com.vibe.app.presentation.ui.supermarket

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibe.app.BuildConfig
import com.vibe.app.data.model.ProductSnapshot
import com.vibe.app.data.model.RetailerOffer
import com.vibe.app.data.network.CloudflareProductSource
import com.vibe.app.data.network.FirebaseRealtimeProductSource
import com.vibe.app.data.network.SupabaseProductSource
import com.vibe.app.data.repository.SuperMarketRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class SuperMarketViewModel : ViewModel() {

    private val repository = SuperMarketRepository(
        listOf(
            CloudflareProductSource(
                apiBaseUrl = BuildConfig.CLOUDFLARE_PRODUCTS_URL
            ),
            SupabaseProductSource(
                baseUrl = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY
            ),
            FirebaseRealtimeProductSource(
                databaseUrl = BuildConfig.FIREBASE_DATABASE_URL
            )
        )
    )

    private val _uiState = MutableStateFlow<SuperMarketUiState>(SuperMarketUiState.Idle)
    val uiState: StateFlow<SuperMarketUiState> = _uiState.asStateFlow()

    private val _healthyClouds = MutableStateFlow(0)
    val healthyClouds: StateFlow<Int> = _healthyClouds.asStateFlow()

    val configuredClouds: Int
        get() = repository.configuredSourceCount

    private var lookupJob: Job? = null

    init {
        viewModelScope.launch {
            _healthyClouds.value = repository.prewarm()
        }
    }

    fun lookupBarcode(rawValue: String?) {
        val barcode = rawValue
            ?.filter(Char::isDigit)
            ?.takeIf { it.length in setOf(8, 12, 13, 14) }

        if (barcode == null) {
            _uiState.value = SuperMarketUiState.Error("تعذر قراءة باركود منتج صالح. حاول مرة أخرى.")
            return
        }

        if (repository.configuredSourceCount == 0) {
            _uiState.value = SuperMarketUiState.ConfigurationMissing(barcode)
            return
        }

        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            _uiState.value = SuperMarketUiState.Loading(barcode)
            val results = mutableListOf<ProductSnapshot>()

            repository.lookup(barcode).collect { product ->
                results += product
                _uiState.value = SuperMarketUiState.Found(
                    product = mergeResults(barcode, results),
                    cloudResponses = results.mapNotNull { it.cloudSource }.distinct().size,
                    cloudTotal = repository.configuredSourceCount
                )
            }

            if (results.isEmpty()) {
                _uiState.value = SuperMarketUiState.NotFound(barcode)
            }
        }
    }

    fun scannerFailed(message: String?) {
        _uiState.value = SuperMarketUiState.Error(
            message?.takeIf { it.isNotBlank() } ?: "تعذر تشغيل قارئ الباركود."
        )
    }

    private fun mergeResults(barcode: String, results: List<ProductSnapshot>): ProductSnapshot {
        val priced = results.filter { it.currentPrice != null }
        val freshest = priced.maxByOrNull { it.priceUpdatedAt.toEpochScore() } ?: results.first()
        val metadata = results.firstOrNull {
            !it.nameAr.isNullOrBlank() || !it.nameEn.isNullOrBlank() || !it.imageUrl.isNullOrBlank()
        } ?: freshest

        val mergedOffers = results
            .flatMap { snapshot ->
                if (snapshot.offers.isNotEmpty()) {
                    snapshot.offers
                } else {
                    listOfNotNull(snapshot.toOfferOrNull())
                }
            }
            .groupBy { it.retailer.trim().lowercase() }
            .mapNotNull { (_, offers) -> offers.maxByOrNull { it.updatedAt.toEpochScore() } }
            .sortedBy { it.price }

        val currentOffer = mergedOffers.maxByOrNull { it.updatedAt.toEpochScore() }

        return freshest.copy(
            barcode = barcode,
            nameAr = metadata.nameAr ?: freshest.nameAr,
            nameEn = metadata.nameEn ?: freshest.nameEn,
            imageUrl = metadata.imageUrl ?: freshest.imageUrl,
            currentPrice = currentOffer?.price ?: freshest.currentPrice,
            currency = currentOffer?.currency ?: freshest.currency,
            retailer = currentOffer?.retailer ?: freshest.retailer,
            priceUpdatedAt = currentOffer?.updatedAt ?: freshest.priceUpdatedAt,
            min30d = results.mapNotNull { it.min30d }.minOrNull() ?: freshest.min30d,
            max30d = results.mapNotNull { it.max30d }.maxOrNull() ?: freshest.max30d,
            sourceCount = maxOf(
                mergedOffers.size,
                results.maxOfOrNull { it.sourceCount } ?: 0
            ),
            confidence = results.mapNotNull { it.confidence }.maxOrNull() ?: freshest.confidence,
            offers = mergedOffers
        )
    }
}

private fun ProductSnapshot.toOfferOrNull(): RetailerOffer? {
    val store = retailer?.takeIf { it.isNotBlank() } ?: return null
    val price = currentPrice ?: return null
    return RetailerOffer(
        retailer = store,
        price = price,
        currency = currency,
        updatedAt = priceUpdatedAt
    )
}

private fun String?.toEpochScore(): Long {
    if (this.isNullOrBlank()) return Long.MIN_VALUE
    return runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}

sealed interface SuperMarketUiState {
    data object Idle : SuperMarketUiState
    data class Loading(val barcode: String) : SuperMarketUiState
    data class Found(
        val product: ProductSnapshot,
        val cloudResponses: Int,
        val cloudTotal: Int
    ) : SuperMarketUiState
    data class NotFound(val barcode: String) : SuperMarketUiState
    data class ConfigurationMissing(val barcode: String) : SuperMarketUiState
    data class Error(val message: String) : SuperMarketUiState
}
