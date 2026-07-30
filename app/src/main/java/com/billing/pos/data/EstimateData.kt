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
 * An estimate: the same document as a sales invoice, but it commits to nothing.
 *
 * It lives in its own table on purpose. Stock is summed from `bill_items` only
 * (see [Repository.stockByName]), as are the VAT return, the sales reports and the
 * profit report — so keeping estimates out of that table makes them stock-neutral and
 * report-neutral by construction, rather than relying on every one of those queries
 * remembering to filter a flag out.
 *
 * Fields mirror [Bill] so the sales-entry screen can save either kind unchanged.
 */
@Entity(tableName = "estimates")
data class Estimate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val estimateNo: String,
    val dateMillis: Long,
    val customerId: Long,
    val customerName: String,
    val paymentMethod: String,
    val subTotal: Double,
    val taxTotal: Double,
    val additionalCharge: Double,
    val discount: Double,
    val grandTotal: Double,
    val customerGstin: String = "",
    val remarks: String = ""
)

/** A single line on an estimate. No batch or primary-qty: nothing here moves stock. */
@Entity(tableName = "estimate_items")
data class EstimateItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val estimateId: Long,
    val name: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val lineTotal: Double,
    val unit: String = ""
)

data class EstimateWithItems(val estimate: Estimate, val lines: List<EstimateItem>)

@Dao
interface EstimateDao {
    @Query("SELECT COUNT(*) FROM estimates") suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(e: Estimate): Long
    @Insert suspend fun insertLines(lines: List<EstimateItem>)
    @Update suspend fun updateHeader(e: Estimate)
    @Query("DELETE FROM estimate_items WHERE estimateId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(e: Estimate)

    @Transaction
    suspend fun save(e: Estimate, lines: List<EstimateItem>): Long {
        val id = insertHeader(e)
        insertLines(lines.map { it.copy(id = 0, estimateId = id) })
        return id
    }

    @Transaction
    suspend fun update(e: Estimate, lines: List<EstimateItem>) {
        updateHeader(e)
        deleteLines(e.id)
        insertLines(lines.map { it.copy(id = 0, estimateId = e.id) })
    }

    @Transaction
    suspend fun delete(e: Estimate) {
        deleteLines(e.id)
        deleteHeader(e)
    }

    @Query("SELECT * FROM estimates ORDER BY dateMillis DESC") fun observeAll(): Flow<List<Estimate>>
    @Query("SELECT * FROM estimates") suspend fun all(): List<Estimate>
    @Query("SELECT * FROM estimates WHERE id = :id LIMIT 1") suspend fun byId(id: Long): Estimate?
    @Query("SELECT * FROM estimate_items WHERE estimateId = :id") suspend fun linesFor(id: Long): List<EstimateItem>
    @Query("SELECT * FROM estimate_items") suspend fun allLines(): List<EstimateItem>
}
