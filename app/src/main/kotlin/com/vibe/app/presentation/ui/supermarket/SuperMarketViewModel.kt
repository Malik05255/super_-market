package com.vibe.app.presentation.ui.supermarket

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibe.app.BuildConfig
import com.vibe.app.data.model.ProductSnapshot
import com.vibe.app.data.network.CloudflareProductSource
import com.vibe.app.data.network.FirestoreProductSource
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
            SupabaseProductSource(
                baseUrl = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY
            ),
            CloudflareProductSource(
                apiBaseUrl = BuildConfig.CLOUDFLARE_PRODUCTS_URL
            ),
            FirestoreProductSource(
                projectId = BuildConfig.FIRESTORE_PROJECT_ID,
                apiKey = BuildConfig.FIRESTORE_API_KEY
            )
        )
    )

    private val _uiState = MutableStateFlow<SuperMarketUiState>(SuperMarketUiState.Idle)
    val uiState: StateFlow<SuperMarketUiState> = _uiState.asStateFlow()

    private var lookupJob: Job? = null

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

        return freshest.copy(
            barcode = barcode,
            nameAr = metadata.nameAr ?: freshest.nameAr,
            nameEn = metadata.nameEn ?: freshest.nameEn,
            imageUrl = metadata.imageUrl ?: freshest.imageUrl,
            min30d = results.mapNotNull { it.min30d }.minOrNull() ?: freshest.min30d,
            max30d = results.mapNotNull { it.max30d }.maxOrNull() ?: freshest.max30d,
            sourceCount = results.maxOfOrNull { it.sourceCount } ?: freshest.sourceCount,
            confidence = results.mapNotNull { it.confidence }.maxOrNull() ?: freshest.confidence
        )
    }
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
