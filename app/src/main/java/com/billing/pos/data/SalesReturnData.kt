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

/** A sales return / credit (goods a customer sent back). */
@Entity(tableName = "sales_returns")
data class SalesReturn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val returnNo: String,
    val dateMillis: Long,
    val customerId: Long,
    val customerName: String,
    val billNo: String = "",
    val subTotal: Double,
    val taxTotal: Double,
    val additionalCharge: Double,
    val discount: Double,
    val grandTotal: Double,
    val remarks: String = ""
)

@Entity(tableName = "sales_return_items")
data class SalesReturnItem(
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

data class SalesReturnWithItems(val ret: SalesReturn, val lines: List<SalesReturnItem>)

@Dao
interface SalesReturnDao {
    @Query("SELECT COUNT(*) FROM sales_returns") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(r: SalesReturn): Long
    @Insert suspend fun insertLines(lines: List<SalesReturnItem>)
    @Update suspend fun updateHeader(r: SalesReturn)
    @Query("DELETE FROM sales_return_items WHERE returnId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(r: SalesReturn)

    @Transaction
    suspend fun save(r: SalesReturn, lines: List<SalesReturnItem>): Long {
        val id = insertHeader(r); insertLines(lines.map { it.copy(id = 0, returnId = id) }); return id
    }
    @Transaction
    suspend fun update(r: SalesReturn, lines: List<SalesReturnItem>) {
        updateHeader(r); deleteLines(r.id); insertLines(lines.map { it.copy(id = 0, returnId = r.id) })
    }
    @Transaction
    suspend fun delete(r: SalesReturn) { deleteLines(r.id); deleteHeader(r) }

    @Query("SELECT * FROM sales_returns ORDER BY dateMillis DESC") fun observeAll(): Flow<List<SalesReturn>>
    @Query("SELECT * FROM sales_returns") suspend fun all(): List<SalesReturn>
    @Query("SELECT * FROM sales_returns WHERE id = :id LIMIT 1") suspend fun byId(id: Long): SalesReturn?
    @Query("SELECT * FROM sales_return_items WHERE returnId = :id") suspend fun linesFor(id: Long): List<SalesReturnItem>
    @Query("SELECT * FROM sales_return_items") suspend fun allLines(): List<SalesReturnItem>
}
