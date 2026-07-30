package com.billing.pos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A supplier (vendor). The seeded "Cash Supplier" has isDefault = true. */
@Entity(tableName = "suppliers")
data class Supplier(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val gstin: String = "",
    val isDefault: Boolean = false
)

/** A purchase bill header (mirror of Bill, but for buying from a supplier). */
@Entity(tableName = "purchases")
data class Purchase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseNo: String,
    val dateMillis: Long,
    val supplierId: Long,
    val supplierName: String,
    val paymentMethod: String,
    val subTotal: Double,
    val taxTotal: Double,
    val additionalCharge: Double,
    val discount: Double,
    val grandTotal: Double,
    val paidAmount: Double = 0.0,
    val supplierGstin: String = "",
    val source: String = "",
    /** When false, this purchase does NOT add stock (goods already received via a Material
     *  Receipt Note against the LPO); it is booked only for VAT. Default true = normal purchase. */
    val stockReceived: Boolean = true,
    /** The LPO this purchase was booked against, if any. */
    val lpoNo: String = ""
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

/** A single line on a purchase. */
@Entity(tableName = "purchase_items")
data class PurchaseItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseId: Long,
    val name: String,
    val qty: Double,
    val price: Double,
    val taxPercent: Double,
    val lineTotal: Double,
    /** Batch/lot received (when batch tracking is on). */
    val batchNo: String = "",
    /** Unit this line was purchased in (blank = the item's primary unit). */
    val unit: String = "",
    /** [qty] converted to the item's primary unit, for stock math. 0 = legacy row, use [qty]. */
    val primaryQty: Double = 0.0
)
