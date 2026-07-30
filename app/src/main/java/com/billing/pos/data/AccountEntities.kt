package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Classifies a group for the financial statements. */
enum class AccountNature(val label: String) {
    ASSET("Asset"), LIABILITY("Liability"), INCOME("Income"), EXPENSE("Expense")
}

/** An account group (e.g. Sundry Debtors, Sales Account) — top of the chart of accounts. */
@Entity(tableName = "account_groups")
data class AccountGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nature: AccountNature,
    val isSystem: Boolean = false
)

/** An account head (ledger) under a group (e.g. Cash, a customer, Sales). */
@Entity(tableName = "account_heads")
data class AccountHead(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val groupId: Long,
    val openingBalance: Double = 0.0,
    val openingIsDebit: Boolean = true,
    val isSystem: Boolean = false
)
