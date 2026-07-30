package com.billing.pos.report

import com.billing.pos.data.AccountGroup
import com.billing.pos.data.AccountHead
import com.billing.pos.data.AccountNature
import com.billing.pos.data.Bill
import com.billing.pos.data.Expense
import com.billing.pos.data.JournalEntry
import com.billing.pos.data.JournalLine
import com.billing.pos.data.Purchase
import com.billing.pos.data.PurchaseReturn
import com.billing.pos.data.Receipt
import com.billing.pos.data.SalesReturn

/** One balanced posting to an account — the unified "account transaction" every report reads. */
data class Posting(
    val head: String,
    val group: String,
    val nature: AccountNature,
    val debit: Double,
    val credit: Double,
    val date: Long,
    val particulars: String = "",
    val vch: String = ""
)

/**
 * The single source of truth for all accounting reports (Ledger, Trial Balance, P&L, Balance Sheet).
 * It maps every money document — sales, purchase, sales return, purchase return, receipt, payment,
 * opening balances and manual journals — into balanced double-entry postings.
 */
object AccountingEngine {

    private const val OPENING_DATE = Long.MIN_VALUE

    fun build(
        heads: List<AccountHead>,
        groups: List<AccountGroup>,
        bills: List<Bill>,
        purchases: List<Purchase>,
        receipts: List<Receipt>,
        expenses: List<Expense>,
        jEntries: List<JournalEntry>,
        jLines: List<JournalLine>,
        salesReturns: List<SalesReturn> = emptyList(),
        purchaseReturns: List<PurchaseReturn> = emptyList()
    ): List<Posting> {
        val groupById = groups.associateBy { it.id }
        val headById = heads.associateBy { it.id }
        val out = ArrayList<Posting>()

        fun natureOfGroup(name: String): AccountNature =
            groups.firstOrNull { it.name == name }?.nature ?: AccountNature.ASSET

        // Resolve a party's actual group/nature from its ledger head (auto-created per party).
        fun partyGroup(name: String, defaultGroup: String): Pair<String, AccountNature> {
            val h = heads.firstOrNull { it.name.equals(name, true) }
            val g = h?.let { groupById[it.groupId] }
            val defNat = if (defaultGroup == "Sundry Debtors") AccountNature.ASSET else AccountNature.LIABILITY
            return (g?.name ?: defaultGroup) to (g?.nature ?: defNat)
        }

        fun cashBank(accountId: Long, mode: String): Triple<String, String, AccountNature> {
            headById[accountId]?.let { h ->
                val g = groupById[h.groupId]
                return Triple(h.name, g?.name ?: "Cash-in-hand", g?.nature ?: AccountNature.ASSET)
            }
            val (nm, grp) = when (mode) {
                "UPI" -> "UPI" to "Cash-in-hand"
                "Card" -> "Card" to "Cash-in-hand"
                "Cheque" -> "Cheque" to "Cash-in-hand"
                "Bank" -> "Bank" to "Bank Accounts"
                else -> "Cash" to "Cash-in-hand"
            }
            return Triple(nm, grp, AccountNature.ASSET)
        }

        // opening balances
        heads.forEach { h ->
            if (h.openingBalance != 0.0) {
                val g = groupById[h.groupId]
                out.add(Posting(h.name, g?.name ?: "", g?.nature ?: AccountNature.ASSET,
                    if (h.openingIsDebit) h.openingBalance else 0.0,
                    if (h.openingIsDebit) 0.0 else h.openingBalance, OPENING_DATE, "Opening Balance", ""))
            }
        }

        // sales bills
        bills.forEach { b ->
            val net = b.subTotal + b.additionalCharge - b.discount
            val part = "Sales - ${b.customerName}"
            if (b.paymentMethod == "Credit") {
                out.add(Posting(b.customerName, "Sundry Debtors", AccountNature.ASSET, b.grandTotal, 0.0, b.dateMillis, part, b.billNo))
            } else {
                val (nm, grp, nat) = cashBank(0, b.paymentMethod)
                out.add(Posting(nm, grp, nat, b.grandTotal, 0.0, b.dateMillis, part, b.billNo))
            }
            out.add(Posting("Sales", "Sales Account", natureOfGroup("Sales Account"), 0.0, net, b.dateMillis, part, b.billNo))
            if (b.taxTotal != 0.0)
                out.add(Posting("Output Tax (GST/VAT)", "Duties & Taxes", natureOfGroup("Duties & Taxes"), 0.0, b.taxTotal, b.dateMillis, part, b.billNo))
        }

        // purchases
        purchases.forEach { p ->
            val net = p.subTotal + p.additionalCharge - p.discount
            val part = "Purchase - ${p.supplierName}"
            out.add(Posting("Purchase", "Purchase Account", natureOfGroup("Purchase Account"), net, 0.0, p.dateMillis, part, p.purchaseNo))
            if (p.taxTotal != 0.0)
                out.add(Posting("Input Tax (GST/VAT)", "Duties & Taxes", natureOfGroup("Duties & Taxes"), p.taxTotal, 0.0, p.dateMillis, part, p.purchaseNo))
            if (p.paymentMethod == "Credit") {
                out.add(Posting(p.supplierName, "Sundry Creditors", AccountNature.LIABILITY, 0.0, p.grandTotal, p.dateMillis, part, p.purchaseNo))
            } else {
                val (nm, grp, nat) = cashBank(0, p.paymentMethod)
                out.add(Posting(nm, grp, nat, 0.0, p.grandTotal, p.dateMillis, part, p.purchaseNo))
            }
        }

        // sales returns: reduce income + reduce customer receivable
        salesReturns.forEach { r ->
            val net = r.subTotal + r.additionalCharge - r.discount
            val part = "Sales Return - ${r.customerName}"
            out.add(Posting("Sales", "Sales Account", natureOfGroup("Sales Account"), net, 0.0, r.dateMillis, part, r.returnNo))
            if (r.taxTotal != 0.0)
                out.add(Posting("Output Tax (GST/VAT)", "Duties & Taxes", natureOfGroup("Duties & Taxes"), r.taxTotal, 0.0, r.dateMillis, part, r.returnNo))
            out.add(Posting(r.customerName, "Sundry Debtors", AccountNature.ASSET, 0.0, r.grandTotal, r.dateMillis, part, r.returnNo))
        }

        // purchase returns: reduce expense + reduce supplier payable
        purchaseReturns.forEach { r ->
            val net = r.subTotal + r.additionalCharge - r.discount
            val part = "Purchase Return - ${r.supplierName}"
            out.add(Posting("Purchase", "Purchase Account", natureOfGroup("Purchase Account"), 0.0, net, r.dateMillis, part, r.returnNo))
            if (r.taxTotal != 0.0)
                out.add(Posting("Input Tax (GST/VAT)", "Duties & Taxes", natureOfGroup("Duties & Taxes"), 0.0, r.taxTotal, r.dateMillis, part, r.returnNo))
            out.add(Posting(r.supplierName, "Sundry Creditors", AccountNature.LIABILITY, r.grandTotal, 0.0, r.dateMillis, part, r.returnNo))
        }

        // receipts (money in)
        receipts.forEach { rc ->
            val (nm, grp, nat) = cashBank(rc.toAccountId, rc.paymentMode)
            val party = rc.payFrom.ifBlank { rc.customerName }.ifBlank { "Sundry Debtors" }
            val (pg, pn) = partyGroup(party, "Sundry Debtors")
            out.add(Posting(nm, grp, nat, rc.amount, 0.0, rc.dateMillis, "Receipt - $party", rc.receiptNo))
            out.add(Posting(party, pg, pn, 0.0, rc.amount, rc.dateMillis, "Receipt", rc.receiptNo))
        }

        // payments / expenses (money out)
        expenses.forEach { e ->
            val (nm, grp, nat) = cashBank(e.fromAccountId, e.paymentMode)
            if (e.payTo.isNotBlank()) {
                val (pg, pn) = partyGroup(e.payTo, "Sundry Creditors")
                out.add(Posting(e.payTo, pg, pn, e.amount, 0.0, e.dateMillis, "Payment", e.voucherNo))
            } else {
                out.add(Posting(e.description.ifBlank { "Indirect Expenses" }, "Indirect Expenses", natureOfGroup("Indirect Expenses"), e.amount, 0.0, e.dateMillis, "Payment", e.voucherNo))
            }
            out.add(Posting(nm, grp, nat, 0.0, e.amount, e.dateMillis, "Payment - ${e.payTo.ifBlank { e.description }}", e.voucherNo))
        }

        // manual journal lines
        val entryById = jEntries.associateBy { it.id }
        jLines.forEach { l ->
            val e = entryById[l.entryId] ?: return@forEach
            val h = headById[l.headId]
            val g = h?.let { groupById[it.groupId] }
            out.add(Posting(l.headName, g?.name ?: "", g?.nature ?: AccountNature.ASSET,
                if (l.isDebit) l.amount else 0.0, if (!l.isDebit) l.amount else 0.0, e.dateMillis,
                e.narration.ifBlank { "Journal" }, e.voucherNo))
        }

        return out
    }
}
