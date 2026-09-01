@file:Suppress("unused")

package com.google.android.gms.mlkit.barcode

import android.content.Context
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner

/**
 * Small compatibility wrapper around Google's official play-services-code-scanner API.
 *
 * The supermarket screen previously referenced the legacy-looking package name. Keeping this
 * bridge isolated avoids touching the large Compose screen while delegating every operation to
 * com.google.mlkit.vision.codescanner.
 */
class GmsBarcodeScannerOptions private constructor(
    internal val delegate: com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
) {
    class Builder {
        private val delegate = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()

        fun setBarcodeFormats(format: Int, vararg additionalFormats: Int): Builder = apply {
            delegate.setBarcodeFormats(format, *additionalFormats)
        }

        fun enableAutoZoom(): Builder = apply {
            delegate.enableAutoZoom()
        }

        fun build(): GmsBarcodeScannerOptions = GmsBarcodeScannerOptions(delegate.build())
    }
}

object GmsBarcodeScanning {
    fun getClient(context: Context, options: GmsBarcodeScannerOptions): GmsBarcodeScanner =
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options.delegate)
}
