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
 * Material Receipt Note (MRN / GRN) — records goods physically received, usually against a
 * purchase order (LPO). Receiving INCREASES stock. A later purchase entry booked against the
 * same LPO records the bill for VAT without adding stock again.
 */
@Entity(tableName = "material_receipts")
data class MaterialReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptNo: String,
    val dateMillis: Long,
    val supplierId: Long,
    val supplierName: String,
    /** The purchase order this receipt is against; 0 = none. */
    val lpoId: Long = 0,
    val lpoNo: String = "",
    val remarks: String = ""
)

@Entity(tableName = "material_receipt_items")
data class MaterialReceiptItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val itemId: Long = 0,
    val name: String,
    /** Quantity received, in the item's PRIMARY unit (for stock math). */
    val qty: Double,
    val price: Double,
    val taxPercent: Double = 0.0,
    val lineTotal: Double = 0.0,
    val batchNo: String = "",
    val unit: String = ""
)

data class MaterialReceiptWithItems(val receipt: MaterialReceipt, val lines: List<MaterialReceiptItem>)

/** Received quantity for an LPO line: keyed by the LPO id and the item name. */
data class LpoReceivedRow(val lpoId: Long, val name: String, val qty: Double)

@Dao
interface MaterialReceiptDao {
    @Query("SELECT COUNT(*) FROM material_receipts") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(m: MaterialReceipt): Long
    @Insert suspend fun insertLines(lines: List<MaterialReceiptItem>)
    @Update suspend fun updateHeader(m: MaterialReceipt)
    @Query("DELETE FROM material_receipt_items WHERE receiptId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(m: MaterialReceipt)

    @Transaction
    suspend fun save(m: MaterialReceipt, lines: List<MaterialReceiptItem>): Long {
        val id = insertHeader(m); insertLines(lines.map { it.copy(id = 0, receiptId = id) }); return id
    }
    @Transaction
    suspend fun update(m: MaterialReceipt, lines: List<MaterialReceiptItem>) {
        updateHeader(m); deleteLines(m.id); insertLines(lines.map { it.copy(id = 0, receiptId = m.id) })
    }
    @Transaction
    suspend fun delete(m: MaterialReceipt) { deleteLines(m.id); deleteHeader(m) }

    @Query("SELECT * FROM material_receipts ORDER BY dateMillis DESC") fun observeAll(): Flow<List<MaterialReceipt>>
    @Query("SELECT * FROM material_receipts") suspend fun all(): List<MaterialReceipt>
    @Query("SELECT * FROM material_receipts WHERE id = :id LIMIT 1") suspend fun byId(id: Long): MaterialReceipt?
    @Query("SELECT * FROM material_receipt_items WHERE receiptId = :id") suspend fun linesFor(id: Long): List<MaterialReceiptItem>
    @Query("SELECT * FROM material_receipt_items") suspend fun allLines(): List<MaterialReceiptItem>

    /** Total received quantity per item name — a stock-IN source. */
    @Query("SELECT name AS name, SUM(qty) AS qty FROM material_receipt_items GROUP BY name COLLATE NOCASE")
    fun observeReceivedByItem(): Flow<List<NameQty>>

    /** Received quantity grouped by the LPO it was received against + item name (for the LPO report). */
    @Query(
        "SELECT r.lpoId AS lpoId, ri.name AS name, SUM(ri.qty) AS qty " +
            "FROM material_receipt_items ri JOIN material_receipts r ON ri.receiptId = r.id " +
            "WHERE r.lpoId > 0 GROUP BY r.lpoId, ri.name COLLATE NOCASE"
    )
    fun observeReceivedByLpo(): Flow<List<LpoReceivedRow>>
}
