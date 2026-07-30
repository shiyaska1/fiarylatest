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

/* -------------------- Hire (rental) invoice -------------------- */

/** A rental / hire invoice: items given out on rent between [startDateMillis] and [endDateMillis]. */
@Entity(tableName = "hire_invoices")
data class HireInvoice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hireNo: String,
    val dateMillis: Long,
    val startDateMillis: Long,
    val endDateMillis: Long,
    val customerId: Long,
    val customerName: String,
    val subTotal: Double,
    val taxTotal: Double,
    val additionalCharge: Double,
    val discount: Double,
    val grandTotal: Double,
    val remarks: String = ""
)

@Entity(tableName = "hire_invoice_items")
data class HireInvoiceItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hireId: Long,
    val itemId: Long = 0,
    val name: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val lineTotal: Double,
    val unit: String = ""
)

data class HireInvoiceWithItems(val hire: HireInvoice, val lines: List<HireInvoiceItem>)

/** name + summed qty, for the item-wise hire report. */
data class HireNameQty(val name: String, val qty: Double)

/** hire invoice id + summed qty, for the fully-returned check. */
data class HireIdQty(val id: Long, val qty: Double)

@Dao
interface HireInvoiceDao {
    @Query("SELECT COUNT(*) FROM hire_invoices") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(h: HireInvoice): Long
    @Insert suspend fun insertLines(lines: List<HireInvoiceItem>)
    @Update suspend fun updateHeader(h: HireInvoice)
    @Query("DELETE FROM hire_invoice_items WHERE hireId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(h: HireInvoice)

    @Transaction
    suspend fun save(h: HireInvoice, lines: List<HireInvoiceItem>): Long {
        val id = insertHeader(h); insertLines(lines.map { it.copy(id = 0, hireId = id) }); return id
    }
    @Transaction
    suspend fun update(h: HireInvoice, lines: List<HireInvoiceItem>) {
        updateHeader(h); deleteLines(h.id); insertLines(lines.map { it.copy(id = 0, hireId = h.id) })
    }
    @Transaction
    suspend fun delete(h: HireInvoice) { deleteLines(h.id); deleteHeader(h) }

    @Query("SELECT * FROM hire_invoices ORDER BY dateMillis DESC") fun observeAll(): Flow<List<HireInvoice>>
    @Query("SELECT * FROM hire_invoices") suspend fun all(): List<HireInvoice>
    @Query("SELECT * FROM hire_invoices WHERE id = :id LIMIT 1") suspend fun byId(id: Long): HireInvoice?
    @Query("SELECT * FROM hire_invoice_items WHERE hireId = :id") suspend fun linesFor(id: Long): List<HireInvoiceItem>
    @Query("SELECT * FROM hire_invoice_items") suspend fun allLines(): List<HireInvoiceItem>
    @Query("SELECT * FROM hire_invoice_items") fun observeAllLines(): Flow<List<HireInvoiceItem>>

    /** Total quantity given out per item name across all hire invoices. */
    @Query("SELECT name AS name, SUM(qty) AS qty FROM hire_invoice_items GROUP BY name COLLATE NOCASE")
    fun observeOutByItem(): Flow<List<HireNameQty>>
}

/* -------------------- Hire return -------------------- */

/** A hire return: items brought back against a hire invoice. Partial returns allowed. */
@Entity(tableName = "hire_returns")
data class HireReturn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val returnNo: String,
    val dateMillis: Long,
    val hireId: Long,
    val hireNo: String,
    val customerId: Long,
    val customerName: String,
    val remarks: String = ""
)

@Entity(tableName = "hire_return_items")
data class HireReturnItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val returnId: Long,
    val itemId: Long = 0,
    val name: String,
    val qty: Double,
    val unit: String = ""
)

data class HireReturnWithItems(val ret: HireReturn, val lines: List<HireReturnItem>)

@Dao
interface HireReturnDao {
    @Query("SELECT COUNT(*) FROM hire_returns") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(r: HireReturn): Long
    @Insert suspend fun insertLines(lines: List<HireReturnItem>)
    @Update suspend fun updateHeader(r: HireReturn)
    @Query("DELETE FROM hire_return_items WHERE returnId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(r: HireReturn)

    @Transaction
    suspend fun save(r: HireReturn, lines: List<HireReturnItem>): Long {
        val id = insertHeader(r); insertLines(lines.map { it.copy(id = 0, returnId = id) }); return id
    }
    @Transaction
    suspend fun update(r: HireReturn, lines: List<HireReturnItem>) {
        updateHeader(r); deleteLines(r.id); insertLines(lines.map { it.copy(id = 0, returnId = r.id) })
    }
    @Transaction
    suspend fun delete(r: HireReturn) { deleteLines(r.id); deleteHeader(r) }

    @Query("SELECT * FROM hire_returns ORDER BY dateMillis DESC") fun observeAll(): Flow<List<HireReturn>>
    @Query("SELECT * FROM hire_returns") suspend fun all(): List<HireReturn>
    @Query("SELECT * FROM hire_returns WHERE id = :id LIMIT 1") suspend fun byId(id: Long): HireReturn?
    @Query("SELECT * FROM hire_return_items WHERE returnId = :id") suspend fun linesFor(id: Long): List<HireReturnItem>
    @Query("SELECT * FROM hire_return_items") suspend fun allLines(): List<HireReturnItem>
    @Query("SELECT SUM(qty) FROM hire_return_items WHERE returnId IN (SELECT id FROM hire_returns WHERE hireId = :hireId)")
    suspend fun returnedQtyForHire(hireId: Long): Double?

    /** Total quantity returned per item name across all hire returns. */
    @Query("SELECT name AS name, SUM(qty) AS qty FROM hire_return_items GROUP BY name COLLATE NOCASE")
    fun observeReturnedByItem(): Flow<List<HireNameQty>>

    /** Total quantity returned per hire invoice, for the "fully returned" check. */
    @Query(
        "SELECT r.hireId AS id, SUM(i.qty) AS qty FROM hire_return_items i " +
            "JOIN hire_returns r ON i.returnId = r.id GROUP BY r.hireId"
    )
    fun observeReturnedByHire(): Flow<List<HireIdQty>>
}
