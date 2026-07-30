package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A reusable SMS/WhatsApp message template. [body] may contain {variables} such as {name} or a
 * custom field like {due}. Built-in variables filled at send time: {name}, {mobile}. Custom ones are
 * filled per contact from an imported Excel sheet (bulk send).
 */
@Entity(tableName = "sms_templates")
data class SmsTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val body: String
)

@Dao
interface SmsTemplateDao {
    @Query("SELECT * FROM sms_templates ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SmsTemplate>>

    @Query("SELECT * FROM sms_templates ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<SmsTemplate>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(t: SmsTemplate): Long
    @Update suspend fun update(t: SmsTemplate)
    @Delete suspend fun delete(t: SmsTemplate)
}
