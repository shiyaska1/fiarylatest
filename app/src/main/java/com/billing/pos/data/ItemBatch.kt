package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A stock batch/lot for an item, with a batch number, expiry date and quantity. */
@Entity(tableName = "item_batches")
data class ItemBatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val batchNo: String,
    /** Expiry date in millis; 0 = no expiry. */
    val expiryMillis: Long = 0,
    val quantity: Double = 0.0
)

@Dao
interface ItemBatchDao {
    @Query("SELECT * FROM item_batches WHERE itemId = :itemId ORDER BY expiryMillis ASC")
    suspend fun forItem(itemId: Long): List<ItemBatch>

    @Query("SELECT * FROM item_batches")
    fun observeAll(): Flow<List<ItemBatch>>

    @Query("SELECT * FROM item_batches")
    suspend fun all(): List<ItemBatch>

    @Insert
    suspend fun insert(batch: ItemBatch): Long

    @Delete
    suspend fun delete(batch: ItemBatch)

    @Query("DELETE FROM item_batches WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: Long)

    @Query("DELETE FROM item_batches")
    suspend fun deleteAll()

    @Query("SELECT * FROM item_batches WHERE itemId = :itemId AND batchNo = :batchNo LIMIT 1")
    suspend fun byItemAndNo(itemId: Long, batchNo: String): ItemBatch?

    @Query("UPDATE item_batches SET quantity = quantity - :qty WHERE itemId = :itemId AND batchNo = :batchNo")
    suspend fun deductQty(itemId: Long, batchNo: String, qty: Double)

    @Query("UPDATE item_batches SET quantity = quantity + :qty WHERE itemId = :itemId AND batchNo = :batchNo")
    suspend fun addQty(itemId: Long, batchNo: String, qty: Double)
}
