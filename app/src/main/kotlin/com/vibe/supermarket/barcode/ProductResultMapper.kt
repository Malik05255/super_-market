package com.vibe.supermarket.barcode

object ProductResultMapper {
    fun map(identity: ProductIdentity): ProductResultUiModel {
        return ProductResultUiModel(
            barcode = identity.barcode,
            name = identity.name,
            brand = identity.brand
        )
    }
}
