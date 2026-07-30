package com.billing.pos.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/** Data access for the Bulk SMS contact book and its group tree. */
class ContactRepository(context: Context) {
    private val dao = AppDatabase.get(context).contactDao()
    private val groupDao = AppDatabase.get(context).contactGroupDao()

    val contacts: Flow<List<Contact>> = dao.observeAll()
    val groups: Flow<List<ContactGroup>> = groupDao.observeAll()

    suspend fun saveContact(c: Contact): Long = if (c.id == 0L) dao.insert(c) else { dao.update(c); c.id }
    suspend fun deleteContact(c: Contact) = dao.delete(c)
    suspend fun allContacts(): List<Contact> = dao.all()

    /** Find an existing group node (by name under the same parent+level) or create it; returns its id. */
    suspend fun ensureGroup(name: String, parentId: Long, level: Int): Long {
        val n = name.trim()
        if (n.isBlank()) return 0
        return groupDao.find(n, parentId, level)?.id ?: groupDao.insert(ContactGroup(name = n, parentId = parentId, level = level))
    }

    suspend fun allGroups(): List<ContactGroup> = groupDao.all()

    /**
     * Import contacts. When [replaceAll] is true the whole book is cleared first; otherwise new
     * numbers are appended and existing mobile numbers are updated. Groups named in the rows are
     * created on the fly. Returns added/updated counts.
     */
    suspend fun importContacts(rows: List<ContactImportRow>, replaceAll: Boolean): Pair<Int, Int> {
        if (replaceAll) dao.deleteAll()
        var added = 0
        var updated = 0
        for (r in rows) {
            if (r.name.isBlank() || r.mobile.isBlank()) continue
            val gId = ensureGroup(r.group, 0, 1)
            val sgId = if (r.subGroup.isNotBlank()) ensureGroup(r.subGroup, gId, 2) else 0
            val ssgId = if (r.subSubGroup.isNotBlank()) ensureGroup(r.subSubGroup, sgId, 3) else 0
            val existing = if (replaceAll) null else dao.byMobile(r.mobile)
            if (existing == null) {
                dao.insert(Contact(name = r.name, mobile = r.mobile, groupId = gId, subGroupId = sgId, subSubGroupId = ssgId))
                added++
            } else {
                dao.update(existing.copy(name = r.name, groupId = gId, subGroupId = sgId, subSubGroupId = ssgId))
                updated++
            }
        }
        return added to updated
    }
}

/** One row parsed from a contact import file. */
data class ContactImportRow(
    val name: String,
    val mobile: String,
    val group: String = "",
    val subGroup: String = "",
    val subSubGroup: String = ""
)
