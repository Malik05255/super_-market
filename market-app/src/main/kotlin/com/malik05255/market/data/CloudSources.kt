package com.malik05255.market.data

import com.malik05255.market.model.ProductSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface CloudSource {
    val id: String
    val configured: Boolean
    val lookupTimeoutMs: Long get() = 2_200L
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

@Serializable
private data class ResolveBarcodeRequest(val barcode: String)

@Serializable
private data class ResolveBarcodeResponse(
    val status: String? = null,
    val payload: ProductSnapshot? = null
)

class SupabaseSource(private val url: String, private val key: String) : CloudSource {
    override val id = "supabase"
    override val configured = url.isNotBlank() && key.isNotBlank()
    // Known barcodes still return from the first indexed request. The longer ceiling is
    // used only when that request misses and the guarded unknown-barcode resolver runs.
    override val lookupTimeoutMs = 5_000L

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

        // Critical path: exact barcode indexed lookup first. No resolver/network metadata
        // request is made for a barcode that already has a hot snapshot.
        val direct = MarketHttp.get("${url.trimEnd('/')}/rest/v1/product_snapshots") {
            parameter("select", "payload")
            parameter("barcode", "eq.$barcode")
            parameter("limit", "1")
            applySupabaseHeaders()
        }
        if (direct.status.value !in 200..299) return null
        direct.body<List<SnapshotRow>>().firstOrNull()?.payload?.let {
            return it.copy(cloudSource = id)
        }

        // Exceptional miss path. The Edge function validates GTIN, rate-limits globally,
        // enforces a per-barcode cooldown and resolves metadata by exact barcode only.
        val resolved = MarketHttp.post("${url.trimEnd('/')}/functions/v1/resolve-barcode") {
            contentType(ContentType.Application.Json)
            applySupabaseHeaders()
            setBody(ResolveBarcodeRequest(barcode))
        }
        if (resolved.status == HttpStatusCode.TooManyRequests || resolved.status.value !in 200..299) {
            return null
        }
        return resolved.body<ResolveBarcodeResponse>().payload?.copy(cloudSource = id)
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
