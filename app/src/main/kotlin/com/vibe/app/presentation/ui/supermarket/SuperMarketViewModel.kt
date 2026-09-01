package com.vibe.app.presentation.ui.supermarket

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.vibe.app.BuildConfig
import com.vibe.app.data.database.ProductCacheDatabase
import com.vibe.app.data.model.ProductSnapshot
import com.vibe.app.data.model.RetailerOffer
import com.vibe.app.data.network.CloudflareProductSource
import com.vibe.app.data.network.FirebaseRealtimeProductSource
import com.vibe.app.data.network.SupabaseProductSource
import com.vibe.app.data.repository.SuperMarketRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class SuperMarketViewModel @Inject constructor(
    @ApplicationContext context: Context
) : ViewModel() {

    private val cacheDatabase = Room.databaseBuilder(
        context,
        ProductCacheDatabase::class.java,
        "supermarket-product-cache.db"
    ).build()

    private val repository = SuperMarketRepository(
        sources = listOf(
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
        ),
        cacheDao = cacheDatabase.productSnapshotCacheDao()
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

    override fun onCleared() {
        cacheDatabase.close()
        super.onCleared()
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
                    cloudResponses = results
                        .mapNotNull { it.cloudSource }
                        .filterNot { it == SuperMarketRepository.LOCAL_CACHE_SOURCE }
                        .distinct()
                        .size,
                    cloudTotal = repository.configuredSourceCount,
                    servedFromCache = results.any {
                        it.cloudSource == SuperMarketRepository.LOCAL_CACHE_SOURCE
                    }
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
        // Every cloud is queried by the scanned barcode. Its headline fields therefore represent
        // the exact-barcode fast path; canonical/alternate GTINs live only inside offers.
        val directPriced = results
            .filter { it.currentPrice != null }
            .maxByOrNull { it.priceUpdatedAt.toEpochScore() }
        val base = directPriced ?: results.first()

        val metadata = results.firstOrNull {
            it.cloudSource != SuperMarketRepository.LOCAL_CACHE_SOURCE &&
                (!it.nameAr.isNullOrBlank() || !it.nameEn.isNullOrBlank() || !it.imageUrl.isNullOrBlank())
        } ?: results.firstOrNull {
            !it.nameAr.isNullOrBlank() || !it.nameEn.isNullOrBlank() || !it.imageUrl.isNullOrBlank()
        } ?: base

        val mergedOffers = results
            .flatMap { snapshot ->
                if (snapshot.offers.isNotEmpty()) snapshot.offers
                else listOfNotNull(snapshot.toOfferOrNull(barcode))
            }
            .groupBy { offer ->
                listOf(
                    offer.retailer.trim().lowercase(),
                    offer.branchKey.orEmpty().trim().lowercase(),
                    offer.barcode.orEmpty()
                ).joinToString("|")
            }
            .mapNotNull { (_, offers) -> offers.maxByOrNull { it.updatedAt.toEpochScore() } }
            .sortedBy { it.price }

        val richestProductInfo = results
            .mapNotNull { it.productInfo }
            .filter { it.hasUsefulData }
            .maxByOrNull { it.completenessScore }

        val canonicalIdentity = results.firstNotNullOfOrNull { it.canonicalProductId }
        val aliases = results.flatMap { it.matchedBarcodes }.distinct()

        return base.copy(
            barcode = barcode,
            canonicalProductId = canonicalIdentity ?: base.canonicalProductId,
            matchedBarcodes = if (aliases.isNotEmpty()) aliases else base.matchedBarcodes,
            nameAr = metadata.nameAr ?: base.nameAr,
            nameEn = metadata.nameEn ?: base.nameEn,
            imageUrl = metadata.imageUrl ?: base.imageUrl,
            // Do not replace exact-barcode headline price with an alternate-barcode offer.
            currentPrice = base.currentPrice,
            currency = base.currency,
            retailer = base.retailer,
            priceUpdatedAt = base.priceUpdatedAt,
            min30d = results.mapNotNull { it.min30d }.minOrNull() ?: base.min30d,
            max30d = results.mapNotNull { it.max30d }.maxOrNull() ?: base.max30d,
            sourceCount = maxOf(
                mergedOffers.map { it.retailer.trim().lowercase() }.distinct().size,
                results.maxOfOrNull { it.sourceCount } ?: 0
            ),
            confidence = results.mapNotNull { it.confidence }.maxOrNull() ?: base.confidence,
            offers = mergedOffers,
            productInfo = richestProductInfo ?: base.productInfo
        )
    }
}

private fun ProductSnapshot.toOfferOrNull(scannedBarcode: String): RetailerOffer? {
    val store = retailer?.takeIf { it.isNotBlank() } ?: return null
    val price = currentPrice ?: return null
    return RetailerOffer(
        retailer = store,
        price = price,
        currency = currency,
        updatedAt = priceUpdatedAt,
        barcode = scannedBarcode
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
        val cloudTotal: Int,
        val servedFromCache: Boolean
    ) : SuperMarketUiState
    data class NotFound(val barcode: String) : SuperMarketUiState
    data class ConfigurationMissing(val barcode: String) : SuperMarketUiState
    data class Error(val message: String) : SuperMarketUiState
}
