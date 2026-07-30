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
 * A customer order — an exact copy of what was billed, kept separately so it never touches
 * stock. It exists to record what a customer asked for, with an optional remark, attachments
 * and a captured map location.
 */
@Entity(tableName = "cust_orders")
data class CustOrder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderNo: String,
    val dateMillis: Long,
    val customerId: Long,
    val customerName: String,
    val remark: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val grandTotal: Double
)

@Entity(tableName = "cust_order_items")
data class CustOrderItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
    val itemId: Long = 0,
    val name: String,
    val qty: Double,
    val price: Double,
    val lineTotal: Double,
    val unit: String = ""
)

@Entity(tableName = "cust_order_attachments")
data class CustOrderAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
    val path: String,
    val name: String,
    val mime: String
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

data class CustOrderWithItems(val order: CustOrder, val items: List<CustOrderItem>)

@Dao
interface CustOrderDao {
    @Query("SELECT COUNT(*) FROM cust_orders") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHeader(o: CustOrder): Long
    @Insert suspend fun insertLines(lines: List<CustOrderItem>)
    @Update suspend fun updateHeader(o: CustOrder)
    @Query("DELETE FROM cust_order_items WHERE orderId = :id") suspend fun deleteLines(id: Long)
    @Delete suspend fun deleteHeader(o: CustOrder)

    @Transaction
    suspend fun save(o: CustOrder, lines: List<CustOrderItem>): Long {
        val id = insertHeader(o); insertLines(lines.map { it.copy(id = 0, orderId = id) }); return id
    }
    @Transaction
    suspend fun update(o: CustOrder, lines: List<CustOrderItem>) {
        updateHeader(o); deleteLines(o.id); insertLines(lines.map { it.copy(id = 0, orderId = o.id) })
    }
    @Transaction
    suspend fun delete(o: CustOrder) { deleteLines(o.id); deleteHeader(o) }

    @Query("SELECT * FROM cust_orders ORDER BY dateMillis DESC") fun observeAll(): Flow<List<CustOrder>>
    @Query("SELECT * FROM cust_orders") suspend fun all(): List<CustOrder>
    @Query("SELECT * FROM cust_orders WHERE id = :id") suspend fun byId(id: Long): CustOrder?
    @Query("SELECT * FROM cust_order_items WHERE orderId = :id ORDER BY id ASC") suspend fun linesFor(id: Long): List<CustOrderItem>
    @Query("SELECT * FROM cust_order_items") suspend fun allLines(): List<CustOrderItem>
    @Query("SELECT * FROM cust_order_items") fun observeAllLines(): Flow<List<CustOrderItem>>

    @Insert suspend fun insertAttachment(a: CustOrderAttachment): Long
    @Query("SELECT * FROM cust_order_attachments WHERE orderId = :id") suspend fun attachmentsFor(id: Long): List<CustOrderAttachment>
    @Query("SELECT * FROM cust_order_attachments") suspend fun allAttachments(): List<CustOrderAttachment>
    @Query("DELETE FROM cust_order_attachments WHERE orderId = :id") suspend fun deleteAttachments(id: Long)
}

/** Files attached to a customer order, copied in so they survive the source going away. */
object OrderAttachmentStore {
    fun dir(context: android.content.Context): java.io.File =
        java.io.File(context.filesDir, "order_attachments").apply { mkdirs() }

    fun copyIn(context: android.content.Context, uri: android.net.Uri): CustOrderAttachment? = runCatching {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = displayName(context, uri) ?: ("attachment_" + System.currentTimeMillis())
        val safe = name.take(40).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = java.io.File(dir(context), "att_" + System.nanoTime() + "_" + safe)
        context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        CustOrderAttachment(orderId = 0, path = target.absolutePath, name = name, mime = mime)
    }.getOrNull()

    private fun displayName(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()
}
