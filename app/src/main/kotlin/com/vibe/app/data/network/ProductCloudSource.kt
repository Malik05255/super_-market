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
}

object ProductCloudHttpClient {
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    val client: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(jsonConfig)
        }
    }
}

class SupabaseProductSource(
    private val baseUrl: String,
    private val anonKey: String,
    private val client: HttpClient = ProductCloudHttpClient.client
) : ProductCloudSource {
    override val id: String = "supabase"
    override val isConfigured: Boolean = baseUrl.isNotBlank() && anonKey.isNotBlank()

    override suspend fun lookup(barcode: String): ProductSnapshot? {
        if (!isConfigured) return null

        val response = client.get("${baseUrl.trimEnd('/')}/rest/v1/product_price_snapshot") {
            parameter("barcode", "eq.$barcode")
            parameter("limit", "1")
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $anonKey")
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status.value !in 200..299) error("Supabase lookup failed: ${response.status}")

        return response.body<List<ProductSnapshot>>()
            .firstOrNull()
            ?.copy(cloudSource = id)
    }
}

class CloudflareProductSource(
    private val apiBaseUrl: String,
    private val client: HttpClient = ProductCloudHttpClient.client
) : ProductCloudSource {
    override val id: String = "cloudflare_d1"
    override val isConfigured: Boolean = apiBaseUrl.isNotBlank()

    override suspend fun lookup(barcode: String): ProductSnapshot? {
        if (!isConfigured) return null

        val response = client.get("${apiBaseUrl.trimEnd('/')}/v1/products/$barcode")
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status.value !in 200..299) error("Cloudflare lookup failed: ${response.status}")

        return response.body<ProductSnapshot>().copy(cloudSource = id)
    }
}

class FirestoreProductSource(
    private val projectId: String,
    private val apiKey: String,
    private val client: HttpClient = ProductCloudHttpClient.client
) : ProductCloudSource {
    override val id: String = "firestore"
    override val isConfigured: Boolean = projectId.isNotBlank() && apiKey.isNotBlank()

    override suspend fun lookup(barcode: String): ProductSnapshot? {
        if (!isConfigured) return null

        val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/product_snapshots/$barcode"
        val response = client.get(url) {
            parameter("key", apiKey)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status.value !in 200..299) error("Firestore lookup failed: ${response.status}")

        val doc = response.body<FirestoreDocument>()
        return doc.toProduct(barcode)?.copy(cloudSource = id)
    }
}

@Serializable
private data class FirestoreDocument(
    val fields: Map<String, FirestoreValue> = emptyMap()
) {
    fun toProduct(fallbackBarcode: String): ProductSnapshot? {
        if (fields.isEmpty()) return null
        return ProductSnapshot(
            barcode = fields.string("barcode") ?: fallbackBarcode,
            nameAr = fields.string("name_ar"),
            nameEn = fields.string("name_en"),
            imageUrl = fields.string("image_url"),
            currentPrice = fields.number("current_price"),
            currency = fields.string("currency") ?: "SAR",
            retailer = fields.string("retailer"),
            priceUpdatedAt = fields.string("price_updated_at") ?: fields.timestamp("price_updated_at"),
            min30d = fields.number("min_30d"),
            max30d = fields.number("max_30d"),
            sourceCount = fields.number("source_count")?.toInt() ?: 1,
            confidence = fields.number("confidence")
        )
    }
}

@Serializable
private data class FirestoreValue(
    val stringValue: String? = null,
    val integerValue: String? = null,
    val doubleValue: Double? = null,
    val timestampValue: String? = null
)

private fun Map<String, FirestoreValue>.string(key: String): String? = get(key)?.stringValue
private fun Map<String, FirestoreValue>.timestamp(key: String): String? = get(key)?.timestampValue
private fun Map<String, FirestoreValue>.number(key: String): Double? =
    get(key)?.doubleValue ?: get(key)?.integerValue?.toDoubleOrNull()
