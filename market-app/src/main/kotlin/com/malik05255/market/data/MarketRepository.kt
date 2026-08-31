package com.malik05255.market.data

import android.content.Context
import com.malik05255.market.model.ProductSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MarketRepository(
    context: Context,
    private val sources: List<CloudSource>
) {
    private val prefs = context.getSharedPreferences("market_snapshot_cache", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    val configuredClouds: Int get() = sources.count { it.configured }

    suspend fun healthyClouds(): Int = coroutineScope {
        sources.filter { it.configured }.map { source ->
            async(Dispatchers.IO) { withTimeoutOrNull(1_500) { source.health() } == true }
        }.awaitAll().count { it }
    }

    fun lookup(barcode: String): Flow<ProductSnapshot> = channelFlow {
        cached(barcode)?.let { send(it.copy(cloudSource = LOCAL_CACHE)) }

        sources.filter { it.configured }.forEach { source ->
            launch(Dispatchers.IO) {
                val product = withTimeoutOrNull(source.lookupTimeoutMs) {
                    runCatching { source.lookup(barcode) }.getOrNull()
                }
                if (product != null) {
                    send(product)
                    cache(product)
                }
            }
        }
    }

    private suspend fun cached(barcode: String): ProductSnapshot? = withContext(Dispatchers.IO) {
        prefs.getString(barcode, null)?.let { raw ->
            runCatching { json.decodeFromString<ProductSnapshot>(raw) }.getOrNull()
        }
    }

    private fun cache(product: ProductSnapshot) {
        val existing = prefs.getString(product.barcode, null)?.let { raw ->
            runCatching { json.decodeFromString<ProductSnapshot>(raw) }.getOrNull()
        }
        if (existing != null && product.currentPrice == null && product.offers.isEmpty()) return
        prefs.edit()
            .putString(product.barcode, json.encodeToString(product.copy(cloudSource = null)))
            .apply()
    }

    companion object {
        const val LOCAL_CACHE = "local_cache"
    }
}
