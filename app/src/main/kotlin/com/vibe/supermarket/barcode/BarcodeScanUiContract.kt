package com.vibe.supermarket.barcode

sealed class BarcodeScanUiContract {
    data object Loading : BarcodeScanUiContract()
    data class ProductFound(val product: ProductIdentity) : BarcodeScanUiContract()
    data object Empty : BarcodeScanUiContract()
    data class Failed(val message: String) : BarcodeScanUiContract()
}
