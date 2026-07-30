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

/** A purchase quotation / LPO (Local Purchase Order) — a request/order sent to a supplier. Document only, no stock movement. */
@Entity(tableName = "purchase_quotations")
data class PurchaseQuotation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lpoNo: String,
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

@Entity(tableName = "purchase_quotation_items")
data class PurchaseQuotationItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lpoId: Long,
    val itemId: Long = 0,
    val name: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val lineTotal: Double,
    /** Unit this line is ordered in (blank = the item's primary unit). */
    val unit: String = ""
)

data class PurchaseQuotationWithItems(val lpo: PurchaseQuotation, val lines: List<PurchaseQuotationItem>)

@Dao
interface PurchaseQuotationDao {
    @Query("SELECT COUNT(*) FROM purchase_quotations") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(r: PurchaseQuotation): Long
    @Insert suspend fun insertLines(lines: List<PurchaseQuotationItem>)
    @Update suspend fun updateHeader(r: PurchaseQuotation)
    @Query("DELETE FROM purchase_quotation_items WHERE lpoId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(r: PurchaseQuotation)

    @Transaction
    suspend fun save(r: PurchaseQuotation, lines: List<PurchaseQuotationItem>): Long {
        val id = insertHeader(r); insertLines(lines.map { it.copy(id = 0, lpoId = id) }); return id
    }
    @Transaction
    suspend fun update(r: PurchaseQuotation, lines: List<PurchaseQuotationItem>) {
        updateHeader(r); deleteLines(r.id); insertLines(lines.map { it.copy(id = 0, lpoId = r.id) })
    }
    @Transaction
    suspend fun delete(r: PurchaseQuotation) { deleteLines(r.id); deleteHeader(r) }

    @Query("SELECT * FROM purchase_quotations ORDER BY dateMillis DESC") fun observeAll(): Flow<List<PurchaseQuotation>>
    @Query("SELECT * FROM purchase_quotations") suspend fun all(): List<PurchaseQuotation>
    @Query("SELECT * FROM purchase_quotations WHERE id = :id LIMIT 1") suspend fun byId(id: Long): PurchaseQuotation?
    @Query("SELECT * FROM purchase_quotation_items WHERE lpoId = :id") suspend fun linesFor(id: Long): List<PurchaseQuotationItem>
    @Query("SELECT * FROM purchase_quotation_items") suspend fun allLines(): List<PurchaseQuotationItem>
    @Query("SELECT * FROM purchase_quotation_items") fun observeAllLines(): Flow<List<PurchaseQuotationItem>>
}
