package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A cached image fingerprint for one item photo, so visual search does not have to re-run
 * the model over every photo on each search. Keyed by file path; [stamp] is the file's last
 * modified time, so a replaced photo is re-fingerprinted automatically.
 */
@Entity(tableName = "item_photo_vectors")
data class ItemPhotoVector(
    @PrimaryKey val path: String,
    val itemId: Long,
    /** The embedding, stored as raw little-endian floats. */
    val vec: ByteArray,
    val stamp: Long
) {
    // ByteArray needs these by hand; the data-class versions compare references.
    override fun equals(other: Any?): Boolean =
        this === other || (other is ItemPhotoVector && path == other.path && stamp == other.stamp)

    override fun hashCode(): Int = path.hashCode() * 31 + stamp.hashCode()
}

@Dao
interface ItemPhotoVectorDao {
    @Query("SELECT * FROM item_photo_vectors") suspend fun all(): List<ItemPhotoVector>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(v: ItemPhotoVector)
    @Query("DELETE FROM item_photo_vectors WHERE path = :path") suspend fun deleteByPath(path: String)
    @Query("DELETE FROM item_photo_vectors WHERE itemId = :itemId") suspend fun deleteForItem(itemId: Long)
}
