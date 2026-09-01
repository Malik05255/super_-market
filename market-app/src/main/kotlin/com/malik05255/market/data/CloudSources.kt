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
import kotlinx.serialization.SerialName
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

/**
 * UPC-A is the 12 digit representation of a GTIN-12. The same item is commonly stored
 * by catalog APIs as EAN-13 with one leading zero. These two representations are safe
 * aliases of the same GTIN value; no other packaging-level GTIN transformations are made.
 */
internal fun equivalentGtins(barcode: String): List<String> = buildList {
    add(barcode)
    when {
        barcode.length == 12 -> add("0$barcode")
        barcode.length == 13 && barcode.startsWith('0') -> add(barcode.drop(1))
    }
}.distinct()

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

@Serializable
private data class FactsResponse(
    val product: FactsProduct? = null
)

@Serializable
private data class FactsProduct(
    val code: String? = null,
    @SerialName("product_name_ar") val nameAr: String? = null,
    @SerialName("product_name_en") val nameEn: String? = null,
    @SerialName("product_name") val name: String? = null,
    val brands: String? = null,
    val quantity: String? = null,
    @SerialName("image_front_url") val imageFrontUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null
)

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun productTitle(product: FactsProduct): Pair<String?, String?> {
    val ar = product.nameAr.clean()
    val baseEn = product.nameEn.clean() ?: product.name.clean()
    val extras = listOfNotNull(product.brands.clean(), product.quantity.clean())
        .filter { extra -> baseEn?.contains(extra, ignoreCase = true) != true }
    val en = listOfNotNull(baseEn).plus(extras).joinToString(" • ").ifBlank { null }
    return ar to en
}

class SupabaseSource(private val url: String, private val key: String) : CloudSource {
    override val id = "supabase"
    override val configured = url.isNotBlank() && key.isNotBlank()
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

        for (candidate in equivalentGtins(barcode)) {
            val direct = MarketHttp.get("${url.trimEnd('/')}/rest/v1/product_snapshots") {
                parameter("select", "payload")
                parameter("barcode", "eq.$candidate")
                parameter("limit", "1")
                applySupabaseHeaders()
            }
            if (direct.status.value !in 200..299) continue
            direct.body<List<SnapshotRow>>().firstOrNull()?.payload?.let { payload ->
                return payload.copy(
                    barcode = barcode,
                    matchedBarcodes = (payload.matchedBarcodes + candidate)
                        .filterNot { it == barcode }
                        .distinct(),
                    cloudSource = id
                )
            }
        }

        for (candidate in equivalentGtins(barcode)) {
            val resolved = MarketHttp.post("${url.trimEnd('/')}/functions/v1/resolve-barcode") {
                contentType(ContentType.Application.Json)
                applySupabaseHeaders()
                setBody(ResolveBarcodeRequest(candidate))
            }
            if (resolved.status == HttpStatusCode.TooManyRequests || resolved.status.value !in 200..299) {
                continue
            }
            resolved.body<ResolveBarcodeResponse>().payload?.let { payload ->
                return payload.copy(
                    barcode = barcode,
                    matchedBarcodes = (payload.matchedBarcodes + candidate)
                        .filterNot { it == barcode }
                        .distinct(),
                    cloudSource = id
                )
            }
        }
        return null
    }

    override suspend fun lookupByText(barcode: String, text: String): ProductSnapshot? {
        if (!configured || text.isBlank()) return null
        val request = ResolveTextRequest(barcode = barcode, text = text.take(4_000))

        val retailerMatch = MarketHttp.post("${url.trimEnd('/')}/functions/v1/resolve-product-text") {
            contentType(ContentType.Application.Json)
            applySupabaseHeaders()
            setBody(request)
        }
        if (retailerMatch.status.value in 200..299) {
            retailerMatch.body<ResolveTextResponse>().payload?.let {
                return it.copy(cloudSource = "${id}_visual_retailer")
            }
        }

        val globalMatch = MarketHttp.post("${url.trimEnd('/')}/functions/v1/resolve-package-text") {
            contentType(ContentType.Application.Json)
            applySupabaseHeaders()
            setBody(request)
        }
        if (globalMatch.status.value !in 200..299) return null
        return globalMatch.body<ResolveTextResponse>().payload?.copy(cloudSource = "${id}_visual_global")
    }
}

/**
 * Independent, metadata-only fallback. It never supplies supermarket prices: its only
 * job is to identify a scanned barcode when the private snapshot/resolver cannot.
 * Product media remains subject to the app's deferred-media policy in the UI layer.
 */
class OpenFactsIdentitySource : CloudSource {
    override val id = "open_facts_direct"
    override val configured = true
    override val lookupTimeoutMs = 10_000L

    private val endpoints = listOf(
        "https://world.openfoodfacts.org/api/v3/product/",
        "https://world.openproductsfacts.org/api/v3/product/",
        "https://world.openbeautyfacts.org/api/v3/product/",
        "https://world.openpetfoodfacts.org/api/v3/product/"
    )

    override suspend fun health(): Boolean = true

    override suspend fun lookup(barcode: String): ProductSnapshot? {
        val fields = listOf(
            "code",
            "product_name",
            "product_name_en",
            "product_name_ar",
            "brands",
            "quantity",
            "image_front_url",
            "image_url"
        ).joinToString(",")

        for (candidate in equivalentGtins(barcode)) {
            for (endpoint in endpoints) {
                val response = runCatching {
                    MarketHttp.get("$endpoint$candidate") {
                        parameter("fields", fields)
                        header(HttpHeaders.UserAgent, "MoqarinAlasaar/2.2 (github.com/Malik05255/super_-market)")
                        header(HttpHeaders.Accept, "application/json")
                    }
                }.getOrNull() ?: continue

                if (response.status.value !in 200..299) continue
                val facts = runCatching { response.body<FactsResponse>() }.getOrNull() ?: continue
                val product = facts.product ?: continue
                val returned = product.code.clean()
                if (returned != null && returned !in equivalentGtins(barcode)) continue

                val (nameAr, nameEn) = productTitle(product)
                if (nameAr == null && nameEn == null) continue

                return ProductSnapshot(
                    barcode = barcode,
                    matchedBarcodes = listOf(candidate).filterNot { it == barcode },
                    nameAr = nameAr,
                    nameEn = nameEn,
                    imageUrl = product.imageFrontUrl.clean() ?: product.imageUrl.clean(),
                    sourceCount = 1,
                    confidence = 0.98,
                    exactBarcodeMatch = false,
                    headlineMatchMethod = "public_barcode_identity",
                    cloudSource = id
                )
            }
        }
        return null
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
        for (candidate in equivalentGtins(barcode)) {
            val r = MarketHttp.get("${url.trimEnd('/')}/v1/products/$candidate")
            if (r.status == HttpStatusCode.NotFound || r.status.value !in 200..299) continue
            return r.body<ProductSnapshot>().copy(barcode = barcode, cloudSource = id)
        }
        return null
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
        for (candidate in equivalentGtins(barcode)) {
            val r = MarketHttp.get("${url.trimEnd('/')}/product_snapshots/$candidate.json")
            if (r.status.value !in 200..299) continue
            r.body<ProductSnapshot?>()?.let { return it.copy(barcode = barcode, cloudSource = id) }
        }
        return null
    }
}
