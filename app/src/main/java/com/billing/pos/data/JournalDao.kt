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
interface JournalDao {
    @Query("SELECT COUNT(*) FROM journal_entries WHERE source = ''")
    suspend fun localCount(): Int

    @Query("SELECT * FROM journal_entries ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<JournalEntry>>

    @Query("SELECT * FROM journal_entries")
    suspend fun allEntries(): List<JournalEntry>

    @Query("SELECT * FROM journal_entries WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): JournalEntry?

    @Query("SELECT * FROM journal_lines WHERE entryId = :entryId")
    suspend fun linesFor(entryId: Long): List<JournalLine>

    @Query("SELECT * FROM journal_lines")
    suspend fun allLines(): List<JournalLine>

    @Query("SELECT * FROM journal_lines")
    fun observeAllLines(): Flow<List<JournalLine>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: JournalEntry): Long

    @Insert
    suspend fun insertLines(lines: List<JournalLine>)

    @Update
    suspend fun updateEntry(entry: JournalEntry)

    @Query("DELETE FROM journal_lines WHERE entryId = :entryId")
    suspend fun deleteLines(entryId: Long)

    @Delete
    suspend fun deleteEntry(entry: JournalEntry)

    @Transaction
    suspend fun saveJournal(entry: JournalEntry, lines: List<JournalLine>): Long {
        val id = insertEntry(entry)
        insertLines(lines.map { it.copy(id = 0, entryId = id) })
        return id
    }

    @Transaction
    suspend fun updateJournal(entry: JournalEntry, lines: List<JournalLine>) {
        updateEntry(entry)
        deleteLines(entry.id)
        insertLines(lines.map { it.copy(id = 0, entryId = entry.id) })
    }

    @Transaction
    suspend fun deleteJournal(entry: JournalEntry) {
        deleteLines(entry.id)
        deleteEntry(entry)
    }
}
