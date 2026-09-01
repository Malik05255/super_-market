package com.vibe.app.data.network

import com.vibe.app.data.model.ProductSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/**
 * Resolver path for barcodes missing from the local snapshot database.
 * Order should be: local DB -> Supabase snapshot -> this resolver.
 */
class SaudiMasterResolverSource(
    private val functionUrl: String,
    private val client: HttpClient = ProductCloudHttpClient.client
) {
    val isConfigured: Boolean = functionUrl.isNotBlank()

    @Serializable
    private data class Request(val barcode: String)

    @Serializable
    private data class Response(val payload: ProductSnapshot? = null)

    suspend fun lookup(barcode: String): ProductSnapshot? {
        if (!isConfigured) return null
        val response = client.post(functionUrl.trimEnd('/')) {
            contentType(ContentType.Application.Json)
            setBody(Request(barcode))
        }
        if (response.status.value !in 200..299) return null
        return response.body<Response>().payload?.copy(cloudSource = "saudi_master_release")
    }
}
