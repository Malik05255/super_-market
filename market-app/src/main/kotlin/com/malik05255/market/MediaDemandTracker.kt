package com.malik05255.market

import android.content.Context

/**
 * Keeps media off the critical scan path.
 *
 * Product images are allowed only after the same barcode is scanned in two
 * genuinely separated sessions. Retailer logos become eligible only after the
 * same retailer appears for two different product barcodes.
 */
internal class MediaDemandTracker(context: Context) {
    private val prefs = context.getSharedPreferences("market_media_demand", Context.MODE_PRIVATE)

    fun recordProductScan(barcode: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = barcode.trim()
        val countKey = "product_count::$key"
        val lastKey = "product_last::$key"
        var count = prefs.getInt(countKey, 0)
        val last = prefs.getLong(lastKey, 0L)

        if (count == 0) {
            count = 1
            prefs.edit().putInt(countKey, count).putLong(lastKey, nowMs).apply()
        } else if (nowMs - last >= PRODUCT_SCAN_GAP_MS) {
            count += 1
            prefs.edit().putInt(countKey, count).putLong(lastKey, nowMs).apply()
        }
        return count >= PRODUCT_IMAGE_SCAN_THRESHOLD
    }

    fun isProductMediaAllowed(barcode: String): Boolean =
        prefs.getInt("product_count::${barcode.trim()}", 0) >= PRODUCT_IMAGE_SCAN_THRESHOLD

    fun recordRetailerSeen(retailer: String, barcode: String): Boolean {
        val retailerKey = retailerKey(retailer)
        val countKey = "retailer_count::$retailerKey"
        val lastBarcodeKey = "retailer_last_barcode::$retailerKey"
        var count = prefs.getInt(countKey, 0)
        val lastBarcode = prefs.getString(lastBarcodeKey, null)

        if (lastBarcode != barcode) {
            count += 1
            prefs.edit()
                .putInt(countKey, count)
                .putString(lastBarcodeKey, barcode)
                .apply()
        }
        return count >= RETAILER_MEDIA_PRODUCT_THRESHOLD
    }

    fun isRetailerMediaAllowed(retailer: String): Boolean =
        prefs.getInt("retailer_count::${retailerKey(retailer)}", 0) >= RETAILER_MEDIA_PRODUCT_THRESHOLD

    private fun retailerKey(value: String): String = value.trim().lowercase()

    companion object {
        internal const val PRODUCT_SCAN_GAP_MS = 6L * 60L * 60L * 1000L
        internal const val PRODUCT_IMAGE_SCAN_THRESHOLD = 2
        internal const val RETAILER_MEDIA_PRODUCT_THRESHOLD = 2
    }
}
