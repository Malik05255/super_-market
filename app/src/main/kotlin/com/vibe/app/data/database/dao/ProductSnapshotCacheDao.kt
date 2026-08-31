package com.vibe.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vibe.app.data.database.entity.ProductSnapshotCacheEntity

@Dao
interface ProductSnapshotCacheDao {
    @Query("SELECT * FROM product_snapshot_cache WHERE barcode = :barcode LIMIT 1")
    suspend fun get(barcode: String): ProductSnapshotCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProductSnapshotCacheEntity)

    @Query("DELETE FROM product_snapshot_cache WHERE cachedAtEpochMs < :cutoffEpochMs")
    suspend fun prune(cutoffEpochMs: Long)
}
