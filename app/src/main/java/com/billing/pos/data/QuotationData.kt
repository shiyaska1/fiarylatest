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

/** A sales quotation header (like a bill, but no stock/payment impact). */
@Entity(tableName = "quotations")
data class Quotation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val quotationNo: String,
    val dateMillis: Long,
    val customerId: Long,
    val customerName: String,
    val subTotal: Double,
    val taxTotal: Double,
    val additionalCharge: Double,
    val discount: Double,
    val grandTotal: Double,
    val remarks: String = "",
    /** Terms and conditions printed at the foot of the quotation. */
    val terms: String = ""
)

@Entity(tableName = "quotation_items")
data class QuotationItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val quotationId: Long,
    val name: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val lineTotal: Double,
    /** Unit this line is quoted in (blank = the item's primary unit). */
    val unit: String = "",
    /** Long description printed under the item name on the quotation. */
    val note: String = ""
)

data class QuotationWithItems(val quotation: Quotation, val lines: List<QuotationItem>)

@Dao
interface QuotationDao {
    @Query("SELECT COUNT(*) FROM quotations") suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(q: Quotation): Long
    @Insert suspend fun insertLines(lines: List<QuotationItem>)
    @Update suspend fun updateHeader(q: Quotation)
    @Query("DELETE FROM quotation_items WHERE quotationId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(q: Quotation)

    @Transaction
    suspend fun save(q: Quotation, lines: List<QuotationItem>): Long {
        val id = insertHeader(q)
        insertLines(lines.map { it.copy(id = 0, quotationId = id) })
        return id
    }

    @Transaction
    suspend fun update(q: Quotation, lines: List<QuotationItem>) {
        updateHeader(q)
        deleteLines(q.id)
        insertLines(lines.map { it.copy(id = 0, quotationId = q.id) })
    }

    @Transaction
    suspend fun delete(q: Quotation) {
        deleteLines(q.id)
        deleteHeader(q)
    }

    @Query("SELECT * FROM quotations ORDER BY dateMillis DESC") fun observeAll(): Flow<List<Quotation>>
    @Query("SELECT * FROM quotations") suspend fun all(): List<Quotation>
    @Query("SELECT * FROM quotations WHERE id = :id LIMIT 1") suspend fun byId(id: Long): Quotation?
    @Query("SELECT * FROM quotation_items WHERE quotationId = :id") suspend fun linesFor(id: Long): List<QuotationItem>
    @Query("SELECT * FROM quotation_items") suspend fun allLines(): List<QuotationItem>
}
