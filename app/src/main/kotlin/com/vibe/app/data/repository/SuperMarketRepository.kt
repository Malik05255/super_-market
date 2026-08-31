package com.vibe.app.data.repository

import com.vibe.app.data.database.dao.ProductSnapshotCacheDao
import com.vibe.app.data.database.entity.ProductSnapshotCacheEntity
import com.vibe.app.data.model.ProductSnapshot
import com.vibe.app.data.network.ProductCloudSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class SuperMarketRepository(
    private val sources: List<ProductCloudSource>,
    private val cacheDao: ProductSnapshotCacheDao
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    val configuredSourceCount: Int
        get() = sources.count { it.isConfigured }

    suspend fun prewarm(): Int = coroutineScope {
        launch(Dispatchers.IO) {
            runCatching {
                cacheDao.prune(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90))
            }
        }
        sources
            .filter { it.isConfigured }
            .map { source ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(1_800) { source.healthCheck() } == true
                }
            }
            .awaitAll()
            .count { it }
    }

    fun lookup(barcode: String): Flow<ProductSnapshot> = channelFlow {
        launch(Dispatchers.IO) {
            val cached = runCatching { cacheDao.get(barcode) }.getOrNull()
            val snapshot = cached?.let {
                runCatching { json.decodeFromString<ProductSnapshot>(it.payload) }.getOrNull()
            }
            snapshot?.let { send(it.copy(cloudSource = LOCAL_CACHE_SOURCE)) }
        }

        sources.filter { it.isConfigured }.forEach { source ->
            launch {
                val result = withTimeoutOrNull(2_500) {
                    runCatching {
                        withContext(Dispatchers.IO) { source.lookup(barcode) }
                    }.getOrNull()
                }
                result?.let { product ->
                    send(product)
                    launch(Dispatchers.IO) { cache(product) }
                }
            }
        }
    }.buffer(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private suspend fun cache(product: ProductSnapshot) {
        val existing = runCatching { cacheDao.get(product.barcode) }.getOrNull()
        if (existing != null && product.currentPrice == null && product.offers.isEmpty()) {
            // Never replace a full priced snapshot with a metadata-only fallback.
            return
        }
        runCatching {
            cacheDao.upsert(
                ProductSnapshotCacheEntity(
                    barcode = product.barcode,
                    payload = json.encodeToString(ProductSnapshot.serializer(), product.copy(cloudSource = null)),
                    cachedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    companion object {
        const val LOCAL_CACHE_SOURCE = "local_cache"
    }
}
