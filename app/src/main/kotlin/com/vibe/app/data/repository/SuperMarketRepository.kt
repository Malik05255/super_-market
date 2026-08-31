package com.vibe.app.data.repository

import com.vibe.app.data.model.ProductSnapshot
import com.vibe.app.data.network.ProductCloudSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SuperMarketRepository(
    private val sources: List<ProductCloudSource>
) {
    val configuredSourceCount: Int
        get() = sources.count { it.isConfigured }

    fun lookup(barcode: String): Flow<ProductSnapshot> = channelFlow {
        sources.filter { it.isConfigured }.forEach { source ->
            launch {
                val result = withTimeoutOrNull(2_500) {
                    runCatching {
                        withContext(Dispatchers.IO) { source.lookup(barcode) }
                    }.getOrNull()
                }
                result?.let { send(it) }
            }
        }
    }.buffer(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
