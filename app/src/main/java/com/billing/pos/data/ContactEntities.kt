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
 * One node in the contact grouping tree, used for all three levels:
 * level 1 = group, level 2 = sub-group (parent = a group), level 3 = sub-sub-group (parent = a sub-group).
 * A single self-referencing table keeps the "+ add new" master identical at every level.
 */
@Entity(tableName = "contact_groups")
data class ContactGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Parent node id. 0 for a top-level group. */
    val parentId: Long = 0,
    /** 1 = group, 2 = sub-group, 3 = sub-sub-group. */
    val level: Int = 1
)

/** A contact in the Bulk SMS address book. Name and mobile are mandatory. */
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val mobile: String,
    val groupId: Long = 0,
    val subGroupId: Long = 0,
    val subSubGroupId: Long = 0
)

@Dao
interface ContactGroupDao {
    @Query("SELECT * FROM contact_groups ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ContactGroup>>

    @Query("SELECT * FROM contact_groups ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<ContactGroup>

    @Query("SELECT * FROM contact_groups WHERE level = :level AND parentId = :parentId AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun find(name: String, parentId: Long, level: Int): ContactGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(g: ContactGroup): Long
    @Update suspend fun update(g: ContactGroup)
    @Delete suspend fun delete(g: ContactGroup)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<Contact>

    @Query("SELECT COUNT(*) FROM contacts") suspend fun count(): Int

    @Query("SELECT * FROM contacts WHERE mobile = :mobile LIMIT 1")
    suspend fun byMobile(mobile: String): Contact?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(c: Contact): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(c: List<Contact>)
    @Update suspend fun update(c: Contact)
    @Delete suspend fun delete(c: Contact)

    @Query("DELETE FROM contacts") suspend fun deleteAll()
}
