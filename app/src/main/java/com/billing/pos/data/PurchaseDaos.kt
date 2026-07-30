package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A purchase line joined with its purchase date, for stock/last-rate calc. */
data class PurchaseLineInfo(val name: String, val price: Double, val qty: Double, val dateMillis: Long)

/** A purchased batch's rate and the voucher it came from (for expiry costing + drill-down). */
data class BatchCostRow(
    val name: String,
    val batchNo: String,
    val price: Double,
    val purchaseId: Long,
    val purchaseNo: String,
    val dateMillis: Long
)

/** A purchase line joined with its supplier + date, for "last supplier" per item. */
data class PurchaseLineParty(val name: String, val dateMillis: Long, val supplierName: String)

/** Aggregated quantity by item name. */
data class NameQty(val name: String, val qty: Double)

/** A tax line (taxable value, tax, rate) with its document date, for VAT reports. */
data class TaxLineInfo(val taxable: Double, val tax: Double, val rate: Double, val dateMillis: Long)

@Dao
interface SupplierDao {
    @Query("SELECT * FROM suppliers ORDER BY isDefault DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Supplier>>

    @Query("SELECT * FROM suppliers")
    suspend fun all(): List<Supplier>

    @Query("SELECT * FROM suppliers WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): Supplier?

    @Query("SELECT COUNT(*) FROM suppliers")
    suspend fun count(): Int

    @Insert
    suspend fun insert(supplier: Supplier): Long

    @Update
    suspend fun update(supplier: Supplier)

    @Delete
    suspend fun delete(supplier: Supplier)
}

@Dao
interface PurchaseDao {
    @Query("SELECT COUNT(*) FROM purchases WHERE source = ''")
    suspend fun localCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPurchase(purchase: Purchase): Long

    @Insert
    suspend fun insertLines(lines: List<PurchaseItem>)

    @Update
    suspend fun updateHeader(purchase: Purchase)

    @Query("DELETE FROM purchase_items WHERE purchaseId = :purchaseId")
    suspend fun deleteLines(purchaseId: Long)

    @Delete
    suspend fun deleteHeader(purchase: Purchase)

    @Transaction
    suspend fun savePurchase(purchase: Purchase, lines: List<PurchaseItem>): Long {
        val id = insertPurchase(purchase)
        insertLines(lines.map { it.copy(purchaseId = id) })
        return id
    }

    @Transaction
    suspend fun updatePurchase(purchase: Purchase, lines: List<PurchaseItem>) {
        updateHeader(purchase)
        deleteLines(purchase.id)
        insertLines(lines.map { it.copy(id = 0, purchaseId = purchase.id) })
    }

    @Transaction
    suspend fun deletePurchase(purchase: Purchase) {
        deleteLines(purchase.id)
        deleteHeader(purchase)
    }

    @Query("SELECT * FROM purchases ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<Purchase>>

    @Query("SELECT * FROM purchases")
    suspend fun all(): List<Purchase>

    @Query(
        "SELECT (pi.qty*pi.price) AS taxable, (pi.qty*pi.price*pi.taxPercent/100.0) AS tax, " +
            "pi.taxPercent AS rate, p.dateMillis AS dateMillis " +
            "FROM purchase_items pi JOIN purchases p ON pi.purchaseId = p.id"
    )
    suspend fun taxLines(): List<TaxLineInfo>

    @Query("SELECT * FROM purchases WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): Purchase?

    @Query("SELECT COUNT(*) FROM purchases WHERE supplierId = :supplierId OR supplierName = :name")
    suspend fun countForSupplier(supplierId: Long, name: String): Int

    @Query("SELECT * FROM purchase_items WHERE purchaseId = :purchaseId")
    suspend fun linesFor(purchaseId: Long): List<PurchaseItem>

    @Query("SELECT * FROM purchase_items")
    suspend fun allLines(): List<PurchaseItem>

    /** [PurchaseLineInfo.qty] is the PRIMARY-unit quantity, so stock math stays unit-correct. */
    @Query(
        "SELECT pi.name AS name, pi.price AS price, " +
            "CASE WHEN pi.primaryQty > 0 THEN pi.primaryQty ELSE pi.qty END AS qty, " +
            "p.dateMillis AS dateMillis " +
            "FROM purchase_items pi JOIN purchases p ON pi.purchaseId = p.id"
    )
    fun observePurchaseLines(): Flow<List<PurchaseLineInfo>>

    /** Purchased quantity per item name for STOCK — excludes purchases whose stock was already
     *  received via a Material Receipt Note (stockReceived = 0). Primary-unit quantity. */
    @Query(
        "SELECT pi.name AS name, " +
            "SUM(CASE WHEN pi.primaryQty > 0 THEN pi.primaryQty ELSE pi.qty END) AS qty " +
            "FROM purchase_items pi JOIN purchases p ON pi.purchaseId = p.id " +
            "WHERE p.stockReceived = 1 GROUP BY pi.name COLLATE NOCASE"
    )
    fun observePurchaseStockQty(): Flow<List<NameQty>>

    /** Returned-to-supplier quantity per item name for one supplier (for the LPO report). */
    @Query(
        "SELECT ri.name AS name, SUM(CASE WHEN ri.primaryQty > 0 THEN ri.primaryQty ELSE ri.qty END) AS qty " +
            "FROM purchase_return_items ri JOIN purchase_returns r ON ri.returnId = r.id " +
            "WHERE r.supplierId = :supplierId GROUP BY ri.name COLLATE NOCASE"
    )
    suspend fun returnedQtyForSupplier(supplierId: Long): List<NameQty>

    /** Per-line purchase movements for the item-movement report (primary-unit quantity). */
    @Query(
        "SELECT pi.name AS name, (CASE WHEN pi.primaryQty > 0 THEN pi.primaryQty ELSE pi.qty END) AS qty, " +
            "p.dateMillis AS dateMillis, p.id AS voucherId, p.purchaseNo AS voucherNo " +
            "FROM purchase_items pi JOIN purchases p ON pi.purchaseId = p.id"
    )
    fun observePurchaseMovements(): Flow<List<MoveRow>>

    /** Batch-wise purchase rate + the voucher it came from, for expiry costing / drill-down. */
    @Query(
        "SELECT pi.name AS name, pi.batchNo AS batchNo, pi.price AS price, " +
            "p.id AS purchaseId, p.purchaseNo AS purchaseNo, p.dateMillis AS dateMillis " +
            "FROM purchase_items pi JOIN purchases p ON pi.purchaseId = p.id " +
            "WHERE pi.batchNo <> ''"
    )
    fun observeBatchCosts(): Flow<List<BatchCostRow>>

    /** One-shot purchase lines (name, price, primary-unit qty, date), for last-rate lookups. */
    @Query(
        "SELECT pi.name AS name, pi.price AS price, " +
            "CASE WHEN pi.primaryQty > 0 THEN pi.primaryQty ELSE pi.qty END AS qty, " +
            "p.dateMillis AS dateMillis " +
            "FROM purchase_items pi JOIN purchases p ON pi.purchaseId = p.id"
    )
    suspend fun purchaseLinesOnce(): List<PurchaseLineInfo>

    @Query(
        "SELECT pi.name AS name, p.dateMillis AS dateMillis, p.supplierName AS supplierName " +
            "FROM purchase_items pi JOIN purchases p ON pi.purchaseId = p.id"
    )
    fun observePurchaseLineParties(): Flow<List<PurchaseLineParty>>
}
