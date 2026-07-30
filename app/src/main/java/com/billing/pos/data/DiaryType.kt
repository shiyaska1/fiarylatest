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
 * A diary category ("Customer call", "Supplier", "Personal"…).
 *
 * Entries reference it by id rather than by name, so correcting a spelling updates one row
 * and every entry that uses it follows automatically.
 */
@Entity(tableName = "diary_types")
data class DiaryType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Dao
interface DiaryTypeDao {
    @Query("SELECT * FROM diary_types ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<DiaryType>>

    @Query("SELECT * FROM diary_types ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<DiaryType>

    @Query("SELECT COUNT(*) FROM diary_types") suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(t: DiaryType): Long
    @Update suspend fun update(t: DiaryType)
    @Delete suspend fun delete(t: DiaryType)

    /** Entries keep their own id reference; clearing it leaves them as "no type". */
    @Query("UPDATE diary_entries SET typeId = 0 WHERE typeId = :typeId")
    suspend fun clearTypeOnEntries(typeId: Long)
}
