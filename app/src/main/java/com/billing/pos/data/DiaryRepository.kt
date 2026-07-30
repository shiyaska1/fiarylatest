package com.billing.pos.data

import android.content.Context
import com.billing.pos.diary.AttachmentStore
import kotlinx.coroutines.flow.Flow

class DiaryRepository(context: Context) {
    private val dao = AppDatabase.get(context).diaryDao()

    fun search(query: String): Flow<List<DiaryEntry>> = dao.search(query)
    fun searchBetween(query: String, from: Long, to: Long): Flow<List<DiaryEntry>> =
        dao.searchBetween(query, from, to)
    fun searchAll(query: String): Flow<List<DiaryEntry>> = dao.searchAll(query)
    fun searchFiltered(
        query: String, anyType: Boolean, typeId: Long, useDate: Boolean, from: Long, to: Long
    ): Flow<List<DiaryEntry>> = dao.searchFiltered(query, anyType, typeId, useDate, from, to)

    // ---- diary types (master) ----
    private val typeDao = AppDatabase.get(context).diaryTypeDao()
    val types: Flow<List<DiaryType>> = typeDao.observeAll()
    suspend fun addType(name: String): Long = typeDao.insert(DiaryType(name = name.trim()))
    /** Returns the id of the type with this name (case-insensitive), creating it if absent. */
    suspend fun typeIdByName(name: String): Long {
        val n = name.trim()
        return typeDao.all().firstOrNull { it.name.equals(n, ignoreCase = true) }?.id
            ?: typeDao.insert(DiaryType(name = n))
    }

    /**
     * Saves one auto-generated voice diary entry: title, type "voice", and a single AUDIO block
     * pointing at [audioPath]. Used by the hourly background recorder (APK build only).
     */
    suspend fun saveAutoVoiceEntry(title: String, createdAt: Long, audioPath: String, name: String, durationMs: Long): Long {
        val typeId = typeIdByName("voice")
        val id = dao.insert(DiaryEntry(title = title, remarks = "", createdAt = createdAt, updatedAt = createdAt, typeId = typeId))
        dao.insertBlock(DiaryBlock(entryId = id, position = 0, type = BlockType.AUDIO, path = audioPath, name = name, mime = "audio/mp4", durationMs = durationMs))
        return id
    }
    suspend fun renameType(type: DiaryType, newName: String) = typeDao.update(type.copy(name = newName.trim()))
    suspend fun deleteType(type: DiaryType) {
        typeDao.clearTypeOnEntries(type.id)
        typeDao.delete(type)
    }
    val allAttachments: Flow<List<DiaryAttachment>> = dao.observeAllAttachments()
    val allBlocks: Flow<List<DiaryBlock>> = dao.observeAllBlocks()

    suspend fun byId(id: Long): DiaryEntry? = dao.byId(id)
    suspend fun attachmentsFor(entryId: Long): List<DiaryAttachment> = dao.attachmentsFor(entryId)

    suspend fun upsert(entry: DiaryEntry): Long =
        if (entry.id == 0L) dao.insert(entry) else { dao.update(entry); entry.id }

    suspend fun insertAttachment(attachment: DiaryAttachment): Long = dao.insertAttachment(attachment)

    suspend fun deleteAttachment(attachment: DiaryAttachment) {
        AttachmentStore.delete(attachment)
        dao.deleteAttachment(attachment)
    }

    // ---- body blocks ----
    suspend fun blocksFor(entryId: Long): List<DiaryBlock> = dao.blocksFor(entryId)

    /** Replaces all blocks for an entry with the given ordered list (positions reassigned). */
    suspend fun replaceBlocks(entryId: Long, blocks: List<DiaryBlock>) {
        dao.deleteBlocksFor(entryId)
        blocks.forEachIndexed { index, b ->
            dao.insertBlock(b.copy(id = 0, entryId = entryId, position = index))
        }
    }

    /** Deletes the entry, its attachment + block rows, and their files. */
    suspend fun deleteEntry(entry: DiaryEntry) {
        dao.attachmentsFor(entry.id).forEach { AttachmentStore.delete(it) }
        dao.deleteAttachmentsFor(entry.id)
        dao.blocksFor(entry.id).forEach { b -> if (b.path.isNotBlank()) runCatching { java.io.File(b.path).delete() } }
        dao.deleteBlocksFor(entry.id)
        dao.delete(entry)
    }
}
