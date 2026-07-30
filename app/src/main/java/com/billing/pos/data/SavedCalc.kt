package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A saved calculator tape.
 *
 * The amounts are kept as a comma-separated list rather than a second table: a tape is
 * only ever read and written whole, and this keeps it trivially portable in a backup.
 * Minus entries are stored negative, exactly as they are on screen.
 */
@Entity(tableName = "saved_calcs")
data class SavedCalc(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateMillis: Long,
    val amounts: String,
    val total: Double,
    val title: String = "",
    val customerId: Long = 0,
    /** Kept by name as well, so a tape still reads correctly if the customer is renamed. */
    val customerName: String = DEFAULT_CUSTOMER,
    val narration: String = ""
) {
    val amountList: List<Double>
        get() = amounts.split(',').mapNotNull { it.trim().toDoubleOrNull() }

    companion object {
        const val DEFAULT_CUSTOMER = "Cash Customer"
        fun pack(values: List<Double>) = values.joinToString(",")
    }
}

@Dao
interface SavedCalcDao {
    @Query("SELECT * FROM saved_calcs ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<SavedCalc>>

    @Query("SELECT * FROM saved_calcs ORDER BY dateMillis DESC")
    suspend fun all(): List<SavedCalc>

    @Insert suspend fun insert(c: SavedCalc): Long

    @Update suspend fun update(c: SavedCalc)

    @Query("DELETE FROM saved_calcs WHERE id = :id")
    suspend fun delete(id: Long)
}
