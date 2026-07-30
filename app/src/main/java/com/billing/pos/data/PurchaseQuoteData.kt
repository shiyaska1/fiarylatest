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

/**
 * A purchase quotation — what a supplier is asking for goods, before any order is placed.
 *
 * Lines may name items that do not exist in the item master, because a quotation often
 * covers things never bought before. Those names live only on the quotation: nothing here
 * touches the item list or stock. It exists to be printed, shared or kept for comparison.
 */
@Entity(tableName = "purchase_quotes")
data class PurchaseQuote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val quoteNo: String,
    val dateMillis: Long,
    val supplierId: Long,
    val supplierName: String,
    val subTotal: Double,
    val taxTotal: Double,
    val additionalCharge: Double,
    val discount: Double,
    val grandTotal: Double,
    val remarks: String = ""
)

@Entity(tableName = "purchase_quote_items")
data class PurchaseQuoteItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val quoteId: Long,
    /** 0 for a name that was typed rather than picked from the item list. */
    val itemId: Long = 0,
    val name: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val lineTotal: Double,
    val unit: String = ""
)

@Dao
interface PurchaseQuoteDao {
    @Query("SELECT COUNT(*) FROM purchase_quotes") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(r: PurchaseQuote): Long
    @Insert suspend fun insertLines(lines: List<PurchaseQuoteItem>)
    @Update suspend fun updateHeader(r: PurchaseQuote)
    @Query("DELETE FROM purchase_quote_items WHERE quoteId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(r: PurchaseQuote)

    @Transaction
    suspend fun save(r: PurchaseQuote, lines: List<PurchaseQuoteItem>): Long {
        val id = insertHeader(r); insertLines(lines.map { it.copy(id = 0, quoteId = id) }); return id
    }

    @Transaction
    suspend fun update(r: PurchaseQuote, lines: List<PurchaseQuoteItem>) {
        updateHeader(r); deleteLines(r.id); insertLines(lines.map { it.copy(id = 0, quoteId = r.id) })
    }

    @Transaction
    suspend fun delete(r: PurchaseQuote) { deleteLines(r.id); deleteHeader(r) }

    @Query("SELECT * FROM purchase_quotes ORDER BY dateMillis DESC") fun observeAll(): Flow<List<PurchaseQuote>>
    @Query("SELECT * FROM purchase_quotes") suspend fun all(): List<PurchaseQuote>
    @Query("SELECT * FROM purchase_quotes WHERE id = :id") suspend fun byId(id: Long): PurchaseQuote?
    @Query("SELECT * FROM purchase_quote_items WHERE quoteId = :id ORDER BY id ASC")
    suspend fun linesFor(id: Long): List<PurchaseQuoteItem>
    @Query("SELECT * FROM purchase_quote_items") suspend fun allLines(): List<PurchaseQuoteItem>
}
