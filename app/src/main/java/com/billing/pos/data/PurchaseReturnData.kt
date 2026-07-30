package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A purchase return / debit (goods sent back to a supplier). */
@Entity(tableName = "purchase_returns")
data class PurchaseReturn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val returnNo: String,
    val dateMillis: Long,
    val supplierId: Long,
    val supplierName: String,
    val billNo: String = "",
    val subTotal: Double,
    val taxTotal: Double,
    val additionalCharge: Double,
    val discount: Double,
    val grandTotal: Double,
    val remarks: String = ""
)

@Entity(tableName = "purchase_return_items")
data class PurchaseReturnItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val returnId: Long,
    val itemId: Long = 0,
    val name: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val lineTotal: Double,
    val batchNo: String = "",
    /** Unit this line was returned in (blank = the item's primary unit). */
    val unit: String = "",
    /** [qty] converted to the item's primary unit, for stock math. 0 = legacy row, use [qty]. */
    val primaryQty: Double = 0.0
)

data class PurchaseReturnWithItems(val ret: PurchaseReturn, val lines: List<PurchaseReturnItem>)

@Dao
interface PurchaseReturnDao {
    @Query("SELECT COUNT(*) FROM purchase_returns") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(r: PurchaseReturn): Long
    @Insert suspend fun insertLines(lines: List<PurchaseReturnItem>)
    @Update suspend fun updateHeader(r: PurchaseReturn)
    @Query("DELETE FROM purchase_return_items WHERE returnId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(r: PurchaseReturn)

    @Transaction
    suspend fun save(r: PurchaseReturn, lines: List<PurchaseReturnItem>): Long {
        val id = insertHeader(r); insertLines(lines.map { it.copy(id = 0, returnId = id) }); return id
    }
    @Transaction
    suspend fun update(r: PurchaseReturn, lines: List<PurchaseReturnItem>) {
        updateHeader(r); deleteLines(r.id); insertLines(lines.map { it.copy(id = 0, returnId = r.id) })
    }
    @Transaction
    suspend fun delete(r: PurchaseReturn) { deleteLines(r.id); deleteHeader(r) }

    @Query("SELECT * FROM purchase_returns ORDER BY dateMillis DESC") fun observeAll(): Flow<List<PurchaseReturn>>
    @Query("SELECT * FROM purchase_returns") suspend fun all(): List<PurchaseReturn>
    @Query("SELECT * FROM purchase_returns WHERE id = :id LIMIT 1") suspend fun byId(id: Long): PurchaseReturn?
    @Query("SELECT * FROM purchase_return_items WHERE returnId = :id") suspend fun linesFor(id: Long): List<PurchaseReturnItem>
    @Query("SELECT * FROM purchase_return_items") suspend fun allLines(): List<PurchaseReturnItem>
}
