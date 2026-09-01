package com.vibe.app.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "product_snapshot_cache")
data class ProductSnapshotCacheEntity(
    @PrimaryKey val barcode: String,
    val payload: String,
    val cachedAtEpochMs: Long
)
