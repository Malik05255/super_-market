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
    suspend fun lookupByText(barcode: String, text: String): ProductSnapshot? = null
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

@Serializable
private data class ResolveTextRequest(
    val barcode: String,
    val text: String
)

@Serializable
private data class ResolveTextResponse(
    val status: String? = null,
    val confidence: Double? = null,
    val payload: ProductSnapshot? = null
)

class SupabaseSource(private val url: String, private val key: String) : CloudSource {
    override val id = "supabase"
    override val configured = url.isNotBlank() && key.isNotBlank()
    // Known barcodes still return from the indexed snapshot request in milliseconds.
    // Unknown GTINs may fan out through several free identity databases, so the ceiling
    // is intentionally longer. This affects only the exceptional miss path.
    override val lookupTimeoutMs = 28_000L

    private fun io.ktor.client.request.HttpRequestBuilder.applySupabaseHeaders() {
        header("apikey", key)
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

    override suspend fun lookupByText(barcode: String, text: String): ProductSnapshot? {
        if (!configured || text.isBlank()) return null
        val resolved = MarketHttp.post("${url.trimEnd('/')}/functions/v1/resolve-product-text") {
            contentType(ContentType.Application.Json)
            applySupabaseHeaders()
            setBody(ResolveTextRequest(barcode = barcode, text = text.take(4_000)))
        }
        if (resolved.status.value !in 200..299) return null
        return resolved.body<ResolveTextResponse>().payload?.copy(cloudSource = "${id}_visual")
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
