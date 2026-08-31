@file:Suppress("unused")

package com.google.android.gms.mlkit.barcode

/**
 * Compatibility aliases for the supermarket screen.
 *
 * play-services-code-scanner exposes these classes from
 * com.google.mlkit.vision.codescanner. Keeping the aliases here lets the UI stay focused on
 * presentation while still compiling against Google's official Code Scanner implementation.
 */
typealias GmsBarcodeScanning = com.google.mlkit.vision.codescanner.GmsBarcodeScanning
typealias GmsBarcodeScannerOptions = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
