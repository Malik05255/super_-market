package com.vibe.app.data.network

import com.vibe.app.data.model.ProductSnapshot
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

interface ProductCloudSource {
    val id: String
    val isConfigured: Boolean
    suspend fun lookup(barcode: String): ProductSnapshot?
    suspend fun healthCheck(): Boolean
}

object ProductCloudHttpClient {
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    val client: HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                retryOnConnectionFailure(true)
            }
        }
        install(ContentNegotiation) {
            json(jsonConfig)
        }
    }
}

@Serializable
private data class SupabaseSnapshotRow(
    val payload: ProductSnapshot
)

class SupabaseProductSource(
    private val baseUrl: String,
    private val anonKey: String,
    private val client: HttpClient = ProductCloudHttpClient.client
) : ProductCloudSource {
    override val id: String = "supabase"
    override val isConfigured: Boolean = baseUrl.isNotBlank() && anonKey.isNotBlank()

    override suspend fun healthCheck(): Boolean {
        if (!isConfigured) return false
        return runCatching {
            val response = client.get("${baseUrl.trimEnd('/')}/rest/v1/product_snapshots") {
                parameter("select", "barcode")
                parameter("limit", "1")
                header("apikey", anonKey)
                header(HttpHeaders.Authorization, "Bearer $anonKey")
            }
            response.status.value in 200..299
        }.getOrDefault(false)
    }

    override suspend fun lookup(barcode: String): ProductSnapshot? {
        if (!isConfigured) return null

        val response = client.get("${baseUrl.trimEnd('/')}/rest/v1/product_snapshots") {
            parameter("select", "payload")
            parameter("barcode", "eq.$barcode")
            parameter("limit", "1")
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $anonKey")
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status.value !in 200..299) error("Supabase lookup failed: ${response.status}")

        return response.body<List<SupabaseSnapshotRow>>()
            .firstOrNull()
            ?.payload
            ?.copy(cloudSource = id)
    }
}

class CloudflareProductSource(
    private val apiBaseUrl: String,
    private val client: HttpClient = ProductCloudHttpClient.client
) : ProductCloudSource {
    override val id: String = "cloudflare_d1"
    override val isConfigured: Boolean = apiBaseUrl.isNotBlank()

    override suspend fun healthCheck(): Boolean {
        if (!isConfigured) return false
        return runCatching {
            client.get("${apiBaseUrl.trimEnd('/')}/health").status.value in 200..299
        }.getOrDefault(false)
    }

    override suspend fun lookup(barcode: String): ProductSnapshot? {
        if (!isConfigured) return null

        val response = client.get("${apiBaseUrl.trimEnd('/')}/v1/products/$barcode")
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status.value !in 200..299) error("Cloudflare lookup failed: ${response.status}")

        return response.body<ProductSnapshot>().copy(cloudSource = id)
    }
}

class FirebaseRealtimeProductSource(
    private val databaseUrl: String,
    private val client: HttpClient = ProductCloudHttpClient.client
) : ProductCloudSource {
    override val id: String = "firebase_rtdb"
    override val isConfigured: Boolean = databaseUrl.isNotBlank()

    override suspend fun healthCheck(): Boolean {
        if (!isConfigured) return false
        return runCatching {
            // Keep health checks O(1): never request the potentially huge product_snapshots key set.
            // The replica sync always writes this tiny state document after a successful refresh.
            client.get("${databaseUrl.trimEnd('/')}/system_state/last_price_refresh.json")
                .status.value in 200..299
        }.getOrDefault(false)
    }

    override suspend fun lookup(barcode: String): ProductSnapshot? {
        if (!isConfigured) return null
        val response = client.get("${databaseUrl.trimEnd('/')}/product_snapshots/$barcode.json")
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status.value !in 200..299) error("Firebase lookup failed: ${response.status}")

        return response.body<ProductSnapshot?>()?.copy(cloudSource = id)
    }
}
