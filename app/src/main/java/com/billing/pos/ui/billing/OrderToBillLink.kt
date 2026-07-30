package com.billing.pos.ui.billing

/** One line handed from an order to a new sales bill. */
data class BillPrefillLine(val itemId: Long, val name: String, val qty: Double, val price: Double, val unit: String)

/**
 * One-shot hand-off of a customer's orders into a new sales bill: the billing screen reads
 * it on open, fills the customer and every ordered line, and clears it.
 */
object OrderToBillLink {
    @Volatile var customerId: Long = 0
    @Volatile var customerName: String = ""
    @Volatile var lines: List<BillPrefillLine> = emptyList()

    val hasData: Boolean get() = lines.isNotEmpty()

    fun set(id: Long, name: String, l: List<BillPrefillLine>) {
        customerId = id; customerName = name; lines = l
    }
    fun take(): Triple<Long, String, List<BillPrefillLine>> {
        val r = Triple(customerId, customerName, lines)
        customerId = 0; customerName = ""; lines = emptyList()
        return r
    }
}
