package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A sellable size/variant of an item with its own selling price (e.g. restaurant portions). */
@Entity(tableName = "item_sizes")
data class ItemSize(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val name: String,
    val price: Double = 0.0
)

@Dao
interface ItemSizeDao {
    @Query("SELECT * FROM item_sizes WHERE itemId = :itemId ORDER BY id")
    suspend fun forItem(itemId: Long): List<ItemSize>

    @Query("SELECT * FROM item_sizes")
    fun observeAll(): Flow<List<ItemSize>>

    @Query("SELECT * FROM item_sizes")
    suspend fun all(): List<ItemSize>

    @Insert
    suspend fun insert(size: ItemSize): Long

    @Delete
    suspend fun delete(size: ItemSize)

    @Query("DELETE FROM item_sizes WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: Long)

    @Query("DELETE FROM item_sizes")
    suspend fun deleteAll()
}
