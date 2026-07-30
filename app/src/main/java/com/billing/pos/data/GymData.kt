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

/** A gym member (admission record). */
@Entity(tableName = "gym_members")
data class GymMember(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val gender: String = "",
    val address: String = "",
    val photoPath: String = "",
    val joinDateMillis: Long,
    val plan: String = "",
    val admissionFee: Double = 0.0,
    val totalFee: Double = 0.0,
    /** Number of fee installments the total is split into (Monthly = 12). */
    val installments: Int = 1,
    /** Training slot / batch time, e.g. "6:00–7:00 AM". */
    val slot: String = "",
    val trainer: String = "",
    val active: Boolean = true
)

/** One scheduled fee line for a member (admission fee or an installment). */
@Entity(tableName = "gym_fees")
data class GymFee(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memberId: Long,
    val dueDateMillis: Long,
    val amount: Double,
    val paidAmount: Double = 0.0,
    val paidDateMillis: Long = 0,
    /** "Admission" or "Installment #n". */
    val kind: String = "Installment",
    val note: String = ""
)

@Dao
interface GymMemberDao {
    @Query("SELECT * FROM gym_members ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<GymMember>>

    @Query("SELECT * FROM gym_members ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<GymMember>

    @Query("SELECT * FROM gym_members WHERE id = :id")
    suspend fun byId(id: Long): GymMember?

    @Query("SELECT COUNT(*) FROM gym_members") suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(m: GymMember): Long
    @Update suspend fun update(m: GymMember)
    @Delete suspend fun delete(m: GymMember)
}

@Dao
interface GymFeeDao {
    @Query("SELECT * FROM gym_fees ORDER BY dueDateMillis ASC")
    fun observeAll(): Flow<List<GymFee>>

    @Query("SELECT * FROM gym_fees WHERE memberId = :memberId ORDER BY dueDateMillis ASC")
    fun observeForMember(memberId: Long): Flow<List<GymFee>>

    @Query("SELECT * FROM gym_fees WHERE memberId = :memberId ORDER BY dueDateMillis ASC")
    suspend fun forMember(memberId: Long): List<GymFee>

    @Query("SELECT * FROM gym_fees WHERE id = :id")
    suspend fun byId(id: Long): GymFee?

    @Insert suspend fun insert(f: GymFee): Long
    @Insert suspend fun insertAll(f: List<GymFee>)
    @Update suspend fun update(f: GymFee)
    @Query("DELETE FROM gym_fees WHERE memberId = :memberId") suspend fun deleteForMember(memberId: Long)
}
