package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One SMS send transaction, for the delivery-status report. Written whether the message went via a
 * gateway or the phone SIM. [status] is updated as the provider/OS reports progress.
 */
@Entity(tableName = "sms_log")
data class SmsLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateMillis: Long,
    val mobile: String,
    val contactName: String = "",
    val body: String,
    /** "Gateway" or "SIM". */
    val channel: String = "Gateway",
    /** QUEUED, SENT, FAILED, DELIVERED. */
    val status: String = "QUEUED",
    /** Raw provider/OS response or error, for troubleshooting. */
    val response: String = "",
    /** Groups the rows of one bulk run together (0 = single send). */
    val batchId: Long = 0
)

@Dao
interface SmsLogDao {
    @Query("SELECT * FROM sms_log ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<SmsLog>>

    @Query("SELECT * FROM sms_log WHERE dateMillis BETWEEN :from AND :to ORDER BY dateMillis DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<SmsLog>>

    @Insert suspend fun insert(log: SmsLog): Long

    @Query("UPDATE sms_log SET status = :status, response = :response WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, response: String)

    @Query("SELECT COUNT(*) FROM sms_log WHERE status = :status") suspend fun countByStatus(status: String): Int
}
