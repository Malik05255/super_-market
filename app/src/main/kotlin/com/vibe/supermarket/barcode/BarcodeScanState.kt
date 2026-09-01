package com.vibe.supermarket.barcode

sealed class BarcodeScanState {
    data object Idle : BarcodeScanState()
    data object Searching : BarcodeScanState()
    data class Found(val product: ProductIdentity) : BarcodeScanState()
    data object NotFound : BarcodeScanState()
    data class Error(val message: String) : BarcodeScanState()
}
