package com.malik05255.market.data

import com.malik05255.market.model.ProductSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface CloudSource {
    val id: String
    val configured: Boolean
    suspend fun lookup(barcode: String): ProductSnapshot?
    suspend fun health(): Boolean
}

private val wireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

internal val MarketHttp = HttpClient(OkHttp) {
    engine { config { retryOnConnectionFailure(true) } }
    install(ContentNegotiation) { json(wireJson) }
}

@Serializable
private data class SnapshotRow(val payload: ProductSnapshot)

class SupabaseSource(private val url: String, private val key: String) : CloudSource {
    override val id = "supabase"
    override val configured = url.isNotBlank() && key.isNotBlank()

    private fun io.ktor.client.request.HttpRequestBuilder.applySupabaseHeaders() {
        header("apikey", key)
        // Legacy anon keys are JWTs and can be used as Bearer tokens. Modern
        // sb_publishable_* keys are API keys, not JWTs, so sending them as Bearer is invalid.
        if (key.startsWith("eyJ")) {
            header(HttpHeaders.Authorization, "Bearer $key")
        }
    }

    override suspend fun health(): Boolean = runCatching {
        if (!configured) return false
        MarketHttp.get("${url.trimEnd('/')}/rest/v1/product_snapshots") {
            parameter("select", "barcode")
            parameter("limit", "1")
            applySupabaseHeaders()
        }.status.value in 200..299
    }.getOrDefault(false)

    override suspend fun lookup(barcode: String): ProductSnapshot? {
        if (!configured) return null
        val r = MarketHttp.get("${url.trimEnd('/')}/rest/v1/product_snapshots") {
            parameter("select", "payload")
            parameter("barcode", "eq.$barcode")
            parameter("limit", "1")
            applySupabaseHeaders()
        }
        if (r.status.value !in 200..299) return null
        return r.body<List<SnapshotRow>>().firstOrNull()?.payload?.copy(cloudSource = id)
    }
}

class CloudflareSource(private val url: String) : CloudSource {
    override val id = "cloudflare_d1"
    override val configured = url.isNotBlank()

    override suspend fun health(): Boolean = runCatching {
        configured && MarketHttp.get("${url.trimEnd('/')}/health").status.value in 200..299
    }.getOrDefault(false)

    override suspend fun lookup(barcode: String): ProductSnapshot? {
        if (!configured) return null
        val r = MarketHttp.get("${url.trimEnd('/')}/v1/products/$barcode")
        if (r.status == HttpStatusCode.NotFound || r.status.value !in 200..299) return null
        return r.body<ProductSnapshot>().copy(cloudSource = id)
    }
}

class FirebaseSource(private val url: String) : CloudSource {
    override val id = "firebase_rtdb"
    override val configured = url.isNotBlank()

    override suspend fun health(): Boolean = runCatching {
        configured && MarketHttp.get("${url.trimEnd('/')}/system_state/last_price_refresh.json")
            .status.value in 200..299
    }.getOrDefault(false)

    override suspend fun lookup(barcode: String): ProductSnapshot? {
        if (!configured) return null
        val r = MarketHttp.get("${url.trimEnd('/')}/product_snapshots/$barcode.json")
        if (r.status.value !in 200..299) return null
        return r.body<ProductSnapshot?>()?.copy(cloudSource = id)
    }
}
