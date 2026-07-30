package com.billing.pos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    // ---- groups ----
    @Query("SELECT * FROM account_groups ORDER BY nature ASC, name COLLATE NOCASE ASC")
    fun observeGroups(): Flow<List<AccountGroup>>

    @Query("SELECT * FROM account_groups")
    suspend fun allGroups(): List<AccountGroup>

    @Query("SELECT COUNT(*) FROM account_groups")
    suspend fun groupCount(): Int

    @Query("SELECT COUNT(*) FROM account_heads WHERE groupId = :groupId")
    suspend fun headCountInGroup(groupId: Long): Int

    @Insert
    suspend fun insertGroup(group: AccountGroup): Long

    @Update
    suspend fun updateGroup(group: AccountGroup)

    @Delete
    suspend fun deleteGroup(group: AccountGroup)

    // ---- heads ----
    @Query("SELECT * FROM account_heads ORDER BY name COLLATE NOCASE ASC")
    fun observeHeads(): Flow<List<AccountHead>>

    @Query("SELECT * FROM account_heads")
    suspend fun allHeads(): List<AccountHead>

    @Query("SELECT * FROM account_heads WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun headByName(name: String): AccountHead?

    @Query("SELECT * FROM account_heads WHERE name = :name AND groupId = :groupId COLLATE NOCASE LIMIT 1")
    suspend fun headByNameGroup(name: String, groupId: Long): AccountHead?

    @Insert
    suspend fun insertHead(head: AccountHead): Long

    @Update
    suspend fun updateHead(head: AccountHead)

    @Delete
    suspend fun deleteHead(head: AccountHead)
}
