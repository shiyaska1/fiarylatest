package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A voice note, photo or document attached to a payment. */
@Entity(tableName = "expense_attachments")
data class ExpenseAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    val path: String,
    val name: String,
    val mime: String
) {
    val isAudio: Boolean get() = mime.startsWith("audio/")
    val isImage: Boolean get() = mime.startsWith("image/")
}

@Dao
interface ExpenseAttachmentDao {
    @Query("SELECT * FROM expense_attachments WHERE expenseId = :expenseId ORDER BY id ASC")
    suspend fun forExpense(expenseId: Long): List<ExpenseAttachment>

    @Query("SELECT * FROM expense_attachments")
    fun observeAll(): Flow<List<ExpenseAttachment>>

    @Query("SELECT * FROM expense_attachments") suspend fun all(): List<ExpenseAttachment>

    @Insert suspend fun insert(a: ExpenseAttachment): Long

    @Query("DELETE FROM expense_attachments WHERE expenseId = :expenseId")
    suspend fun deleteForExpense(expenseId: Long)
}
