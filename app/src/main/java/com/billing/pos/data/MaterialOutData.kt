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

/** A material-out (consumption / issue) voucher: items taken out of stock, optionally for lab results. */
@Entity(tableName = "material_outs")
data class MaterialOut(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherNo: String,
    val dateMillis: Long,
    /** Comma-joined result invoice numbers this consumption is against (blank = standalone). */
    val resultRef: String = "",
    /** Comma-joined test names of the linked results, for the test filter. */
    val resultTests: String = "",
    val remarks: String = ""
)

@Entity(tableName = "material_out_items")
data class MaterialOutItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val outId: Long,
    val itemId: Long = 0,
    val name: String,
    val qty: Double,
    val unit: String = ""
)

data class MaterialOutWithItems(val out: MaterialOut, val lines: List<MaterialOutItem>)

/** A single stock movement (for the item-movement report). */
data class MoveRow(val name: String, val qty: Double, val dateMillis: Long, val voucherId: Long, val voucherNo: String)

@Dao
interface MaterialOutDao {
    @Query("SELECT COUNT(*) FROM material_outs") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(m: MaterialOut): Long
    @Insert suspend fun insertLines(lines: List<MaterialOutItem>)
    @Update suspend fun updateHeader(m: MaterialOut)
    @Query("DELETE FROM material_out_items WHERE outId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(m: MaterialOut)

    @Transaction
    suspend fun save(m: MaterialOut, lines: List<MaterialOutItem>): Long {
        val id = insertHeader(m); insertLines(lines.map { it.copy(id = 0, outId = id) }); return id
    }
    @Transaction
    suspend fun update(m: MaterialOut, lines: List<MaterialOutItem>) {
        updateHeader(m); deleteLines(m.id); insertLines(lines.map { it.copy(id = 0, outId = m.id) })
    }
    @Transaction
    suspend fun delete(m: MaterialOut) { deleteLines(m.id); deleteHeader(m) }

    @Query("SELECT * FROM material_outs ORDER BY dateMillis DESC") fun observeAll(): Flow<List<MaterialOut>>
    @Query("SELECT * FROM material_outs") suspend fun all(): List<MaterialOut>
    @Query("SELECT * FROM material_outs WHERE id = :id LIMIT 1") suspend fun byId(id: Long): MaterialOut?
    @Query("SELECT * FROM material_out_items WHERE outId = :id") suspend fun linesFor(id: Long): List<MaterialOutItem>
    @Query("SELECT * FROM material_out_items") suspend fun allLines(): List<MaterialOutItem>

    /** Total quantity taken out per item name (deducted from stock). */
    @Query("SELECT name AS name, SUM(qty) AS qty FROM material_out_items GROUP BY name COLLATE NOCASE")
    fun observeOutByItem(): Flow<List<NameQty>>

    /** Per-line movements for the item-movement report. */
    @Query(
        "SELECT mi.name AS name, mi.qty AS qty, m.dateMillis AS dateMillis, m.id AS voucherId, m.voucherNo AS voucherNo " +
            "FROM material_out_items mi JOIN material_outs m ON mi.outId = m.id"
    )
    fun observeMovements(): Flow<List<MoveRow>>
}
