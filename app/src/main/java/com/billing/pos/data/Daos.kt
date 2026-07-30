package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY isDefault DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Customer>>

    @Query("SELECT * FROM customers")
    suspend fun all(): List<Customer>

    @Query("SELECT * FROM customers WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): Customer?

    @Query("SELECT * FROM customers WHERE phone = :phone LIMIT 1")
    suspend fun byPhone(phone: String): Customer?

    @Query("SELECT COUNT(*) FROM customers")
    suspend fun count(): Int

    @Insert
    suspend fun insert(customer: Customer): Long

    @Update
    suspend fun update(customer: Customer)

    @Delete
    suspend fun delete(customer: Customer)
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Item>>

    @Query("SELECT * FROM items")
    suspend fun all(): List<Item>

    @Query("SELECT * FROM items WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): Item?

    @Query("SELECT * FROM items WHERE barcode = :barcode AND barcode != '' LIMIT 1")
    suspend fun byBarcode(barcode: String): Item?

    @Insert
    suspend fun insert(item: Item): Long

    @Update
    suspend fun update(item: Item)

    @Delete
    suspend fun delete(item: Item)

    @Query("DELETE FROM items")
    suspend fun deleteAll()
}

@Dao
interface BillAttachmentDao {
    @Query("SELECT * FROM bill_attachments")
    fun observeAll(): Flow<List<BillAttachment>>

    @Query("SELECT * FROM bill_attachments")
    suspend fun all(): List<BillAttachment>

    @Query("SELECT * FROM bill_attachments WHERE billId = :billId ORDER BY id")
    suspend fun forBill(billId: Long): List<BillAttachment>

    @Insert
    suspend fun insert(attachment: BillAttachment): Long

    @Delete
    suspend fun delete(attachment: BillAttachment)

    @Query("DELETE FROM bill_attachments WHERE billId = :billId")
    suspend fun deleteForBill(billId: Long)
}

@Dao
interface ItemAttachmentDao {
    @Query("SELECT * FROM item_attachments")
    fun observeAll(): Flow<List<ItemAttachment>>

    @Query("SELECT * FROM item_attachments")
    suspend fun all(): List<ItemAttachment>

    @Query("SELECT * FROM item_attachments WHERE itemId = :itemId ORDER BY id")
    suspend fun forItem(itemId: Long): List<ItemAttachment>

    @Insert
    suspend fun insert(attachment: ItemAttachment): Long

    @Delete
    suspend fun delete(attachment: ItemAttachment)

    @Query("DELETE FROM item_attachments WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: Long)

    @Query("DELETE FROM item_attachments")
    suspend fun deleteAll()
}

@Dao
interface BillDao {
    @Query("SELECT COUNT(*) FROM bills WHERE source = ''")
    suspend fun localCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBill(bill: Bill): Long

    @Insert
    suspend fun insertLines(lines: List<BillItem>)

    @Update
    suspend fun updateBillHeader(bill: Bill)

    @Query("DELETE FROM bill_items WHERE billId = :billId")
    suspend fun deleteLines(billId: Long)

    @Delete
    suspend fun deleteBillHeader(bill: Bill)

    @Transaction
    suspend fun saveBill(bill: Bill, lines: List<BillItem>): Long {
        val billId = insertBill(bill)
        insertLines(lines.map { it.copy(billId = billId) })
        return billId
    }

    @Transaction
    suspend fun updateBill(bill: Bill, lines: List<BillItem>) {
        updateBillHeader(bill)
        deleteLines(bill.id)
        insertLines(lines.map { it.copy(id = 0, billId = bill.id) })
    }

    @Transaction
    suspend fun deleteBill(bill: Bill) {
        deleteLines(bill.id)
        deleteBillHeader(bill)
    }

    @Query("SELECT * FROM bills WHERE dateMillis BETWEEN :from AND :to ORDER BY dateMillis DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<Bill>>

    @Query("SELECT * FROM bills ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<Bill>>

    @Query("SELECT * FROM bills")
    suspend fun all(): List<Bill>

    @Query("SELECT * FROM bills WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): Bill?

    @Query("SELECT COUNT(*) FROM bills WHERE customerId = :customerId OR customerName = :name")
    suspend fun countForCustomer(customerId: Long, name: String): Int

    @Query("SELECT * FROM bill_items WHERE billId = :billId")
    suspend fun linesFor(billId: Long): List<BillItem>

    @Query("SELECT * FROM bill_items")
    suspend fun allLines(): List<BillItem>

    @Query("SELECT * FROM bill_items")
    fun observeAllLines(): Flow<List<BillItem>>

    /** Sold quantity in the item's PRIMARY unit (primaryQty), falling back to qty on legacy rows. */
    @Query(
        "SELECT name AS name, SUM(CASE WHEN primaryQty > 0 THEN primaryQty ELSE qty END) AS qty " +
            "FROM bill_items GROUP BY name COLLATE NOCASE"
    )
    fun observeSoldQty(): Flow<List<NameQty>>

    /** Per-line sale movements for the item-movement report (primary-unit quantity). */
    @Query(
        "SELECT bi.name AS name, (CASE WHEN bi.primaryQty > 0 THEN bi.primaryQty ELSE bi.qty END) AS qty, " +
            "b.dateMillis AS dateMillis, b.id AS voucherId, b.billNo AS voucherNo " +
            "FROM bill_items bi JOIN bills b ON bi.billId = b.id"
    )
    fun observeSaleMovements(): Flow<List<MoveRow>>

    @Query(
        "SELECT (bi.qty*bi.price) AS taxable, (bi.qty*bi.price*bi.taxPercent/100.0) AS tax, " +
            "bi.taxPercent AS rate, b.dateMillis AS dateMillis " +
            "FROM bill_items bi JOIN bills b ON bi.billId = b.id"
    )
    suspend fun taxLines(): List<TaxLineInfo>

    @Query("SELECT * FROM bills WHERE source = :source AND billNo = :billNo LIMIT 1")
    suspend fun findBySourceAndNo(source: String, billNo: String): Bill?
}

@Dao
interface ReceiptDao {
    @Query("SELECT * FROM receipts ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<Receipt>>

    @Query("SELECT * FROM receipts")
    suspend fun all(): List<Receipt>

    @Query("SELECT COUNT(*) FROM receipts WHERE source = ''")
    suspend fun localCount(): Int

    @Query("SELECT * FROM receipts WHERE source = :source AND receiptNo = :receiptNo LIMIT 1")
    suspend fun findBySourceAndNo(source: String, receiptNo: String): Receipt?

    @Query("SELECT DISTINCT payFrom FROM receipts WHERE payFrom != '' ORDER BY payFrom COLLATE NOCASE ASC")
    suspend fun payFromNames(): List<String>

    @Insert
    suspend fun insert(receipt: Receipt): Long

    @Update
    suspend fun update(receipt: Receipt)

    @Delete
    suspend fun delete(receipt: Receipt)
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses")
    suspend fun all(): List<Expense>

    @Query("SELECT COUNT(*) FROM expenses WHERE source = ''")
    suspend fun localCount(): Int

    @Query("SELECT * FROM expenses WHERE source = :source AND voucherNo = :voucherNo LIMIT 1")
    suspend fun findBySourceAndNo(source: String, voucherNo: String): Expense?

    @Insert
    suspend fun insert(expense: Expense): Long

    @Update
    suspend fun update(expense: Expense)

    @Delete
    suspend fun delete(expense: Expense)
}

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY role ASC, username COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<User>>

    @Query("SELECT * FROM users WHERE username = :username COLLATE NOCASE AND active = 1 LIMIT 1")
    suspend fun byUsername(username: String): User?

    @Query("SELECT * FROM users WHERE id = :id AND active = 1 LIMIT 1")
    suspend fun byId(id: Long): User?

    @Query("SELECT * FROM users")
    suspend fun all(): List<User>

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: User): Long

    @Update
    suspend fun update(user: User)

    @Delete
    suspend fun delete(user: User)
}
