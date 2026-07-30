package com.billing.pos.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.billing.pos.data.User

/** In-memory logged-in user. Cleared on app restart (login required each launch). */
object Session {
    var current by mutableStateOf<User?>(null)
        private set

    fun login(user: User) { current = user }
    fun logout() { current = null }

    val isLoggedIn: Boolean get() = current != null

    // Permission helpers (safe when logged out → false).
    val canCreate: Boolean get() = current?.canCreateInvoice == true
    val canEdit: Boolean get() = current?.canEditInvoice == true
    val canDelete: Boolean get() = current?.canDeleteInvoice == true
    val canViewInvoice: Boolean get() = current?.canViewInvoice == true

    val canCreateReceipt: Boolean get() = current?.canCreateReceipt == true
    val canEditReceipt: Boolean get() = current?.canEditReceipt == true
    val canDeleteReceipt: Boolean get() = current?.canDeleteReceipt == true
    val canViewReceipt: Boolean get() = current?.canViewReceipt == true

    val canCreatePayment: Boolean get() = current?.canCreatePayment == true
    val canEditPayment: Boolean get() = current?.canEditPayment == true
    val canDeletePayment: Boolean get() = current?.canDeletePayment == true
    val canViewPayment: Boolean get() = current?.canViewPayment == true

    val canViewCashbook: Boolean get() = current?.canViewCashbook == true
    val canExport: Boolean get() = current?.canExport == true
    val canImport: Boolean get() = current?.canImport == true
    val canManageUsers: Boolean get() = current?.canManageUsers == true

    /** Label attached to bills this user creates, so imports can be traced. */
    val sourceLabel: String get() = current?.username ?: ""
}
