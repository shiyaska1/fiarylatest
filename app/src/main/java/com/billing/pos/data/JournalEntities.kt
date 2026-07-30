package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A journal voucher header. */
@Entity(tableName = "journal_entries")
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherNo: String,
    val dateMillis: Long,
    val narration: String = "",
    /** If set (Cash/UPI/Card/Cheque) this voucher also shows in the Cash Book. Blank = not shown. */
    val cashMode: String = "",
    /** For the Cash Book: true = money received (in), false = paid (out). */
    val cashIsIn: Boolean = true,
    /** Amount to reflect in the Cash Book (set to the voucher total when cashMode is used). */
    val cashAmount: Double = 0.0,
    val source: String = ""
)

/** One posting line of a journal voucher (a debit or a credit to an account head). */
@Entity(tableName = "journal_lines")
data class JournalLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val headId: Long,
    val headName: String,
    val amount: Double,
    val isDebit: Boolean
)
