package com.vibe.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vibe.app.data.database.dao.ProductSnapshotCacheDao
import com.vibe.app.data.database.entity.ProductSnapshotCacheEntity

@Database(
    entities = [ProductSnapshotCacheEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ProductCacheDatabase : RoomDatabase() {
    abstract fun productSnapshotCacheDao(): ProductSnapshotCacheDao
}
