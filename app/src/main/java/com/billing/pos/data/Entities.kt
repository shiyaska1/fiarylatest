package com.billing.pos.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A customer. The seeded "Cash" customer has isDefault = true. */
@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val gstin: String = "",
    val isDefault: Boolean = false,
    val customerType: String = "General"
)

/** A saleable item. taxPercent = 0.0 means "without tax". */
@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val price: Double,
    /** Cost/purchase price in the primary unit; 0 = not set (reports fall back to the last purchase rate). */
    val purchasePrice: Double = 0.0,
    val taxPercent: Double = 0.0,
    val barcode: String = "",
    val hsn: String = "",
    val category: String = "",
    /** Stock on hand before any purchase/sale was recorded in this app. */
    val openingStock: Double = 0.0,
    /** Primary unit of measure. [price] and stock are always expressed in this unit. e.g. BOX, PCS, KG. */
    val unit: String = "PCS",
    /** Smaller alternate unit the item may also be sold in, e.g. PCS inside a BOX. */
    val secondaryUnit: String = "PCS",
    /** How many [secondaryUnit] make one [unit]. Always 1.0 when both units are the same. */
    val conversionFactor: Double = 1.0,
    /** Where the item sits in the store (rack/shelf), as free text. */
    val storeLocation: String = "",
    /** Composition / salt / chemical content (medical stores). Searchable. */
    val chemicalContent: String = ""
)

/**
 * A file attached to an item. [kind] is "PHOTO" (product picture),
 * "LOCATION" (photo of where it sits in the store) or "CATALOGUE" (a PDF).
 */
@Entity(tableName = "item_attachments")
data class ItemAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val path: String,
    val name: String,
    val mime: String,
    val kind: String = "PHOTO"
)

/** A saved bill (invoice header). `source` marks where it came from ("" = this device). */
@Entity(tableName = "bills")
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billNo: String,
    val dateMillis: Long,
    val customerId: Long,
    val customerName: String,
    val paymentMethod: String,
    val subTotal: Double,
    val taxTotal: Double,
    val additionalCharge: Double,
    val discount: Double,
    val grandTotal: Double,
    /** Amount already received. Credit invoices start at 0; others = grandTotal. */
    val paidAmount: Double = 0.0,
    val customerGstin: String = "",
    val source: String = "",
    /** Free-text note; printed on the bill only when non-blank. */
    val remarks: String = ""
) {
    val balance: Double get() = (grandTotal - paidAmount).coerceAtLeast(0.0)
    val paymentStatus: String
        get() = when {
            paymentMethod != PaymentMethod.CREDIT.label -> "Paid"
            balance <= 0.001 -> "Paid"
            paidAmount > 0.001 -> "Partial"
            else -> "Credit"
        }
}

/** A document/photo attached to a saved bill. */
@Entity(tableName = "bill_attachments")
data class BillAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,
    val path: String,
    val name: String,
    val mime: String
)

/** A single line on a saved bill. */
@Entity(tableName = "bill_items")
data class BillItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,
    val name: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val lineTotal: Double,
    /** Batch/lot this line was sold from (when batch tracking is on). */
    val batchNo: String = "",
    /** Unit this line was billed in (blank = the item's primary unit). */
    val unit: String = "",
    /** [qty] converted to the item's primary unit, for stock math. 0 = legacy row, use [qty]. */
    val primaryQty: Double = 0.0
)

/** Convenience wrapper: a bill plus its lines. */
data class BillWithItems(
    val bill: Bill,
    val lines: List<BillItem>
)

enum class PaymentMethod(val label: String) {
    CASH("Cash"), UPI("UPI"), CARD("Card"), CREDIT("Credit")
}

/** Payment mode for receipts and expenses (no "Credit"). */
enum class PayMode(val label: String) {
    CASH("Cash"), UPI("UPI"), CARD("Card"), CHEQUE("Cheque")
}

/**
 * A receipt voucher: money received. May be against an invoice (billId > 0,
 * payFrom = customer) or from any other source (billId = 0, payFrom = entered name).
 */
@Entity(tableName = "receipts")
data class Receipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptNo: String,
    val billId: Long,
    val billNo: String,
    val customerName: String,
    val dateMillis: Long,
    val amount: Double,
    val paymentMode: String,
    val payFrom: String = "",
    val source: String = "",
    /** Cash/Bank account head the money was received into (0 = unspecified/legacy). */
    val toAccountId: Long = 0
)

/**
 * A payment / expense voucher: money paid out. May be a general expense or a
 * payment against a (credit) purchase (purchaseId > 0, payTo = supplier).
 */
@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherNo: String,
    val dateMillis: Long,
    val description: String,
    val amount: Double,
    val paymentMode: String,
    val purchaseId: Long = 0,
    val purchaseNo: String = "",
    val payTo: String = "",
    val source: String = "",
    /** Cash/Bank account head the money was paid from (0 = unspecified/legacy). */
    val fromAccountId: Long = 0
)

enum class Role(val label: String) {
    SUPER_USER("Super User"), ADMIN("Owner / Admin"), SALESMAN("Salesman")
}

/**
 * An app user. Permissions are decided by a super user at creation time and
 * are never altered by data import.
 */
@Entity(tableName = "users", indices = [Index(value = ["username"], unique = true)])
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val passwordHash: String,
    val role: Role,
    // Invoice
    val canCreateInvoice: Boolean = true,
    val canEditInvoice: Boolean = false,
    val canDeleteInvoice: Boolean = false,
    val canViewInvoice: Boolean = true,
    // Receipt
    val canCreateReceipt: Boolean = false,
    val canEditReceipt: Boolean = false,
    val canDeleteReceipt: Boolean = false,
    val canViewReceipt: Boolean = false,
    // Payment / expense
    val canCreatePayment: Boolean = false,
    val canEditPayment: Boolean = false,
    val canDeletePayment: Boolean = false,
    val canViewPayment: Boolean = false,
    // Reports & data & admin
    val canViewCashbook: Boolean = false,
    val canExport: Boolean = true,
    val canImport: Boolean = false,
    val canManageUsers: Boolean = false,
    val active: Boolean = true
)
