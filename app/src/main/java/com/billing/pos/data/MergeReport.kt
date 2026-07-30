package com.billing.pos.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a merge-restore actually inserted.
 *
 * Records the new row ids per category, not just counts, so the log can list the entries
 * and open any of them for editing. Kept as JSON in preferences so the last merge can be
 * reviewed later, not only on the screen that ran it.
 */
data class MergeGroup(
    /** Stable key, also the routing hint — see [MergeCategories]. */
    val key: String,
    val label: String,
    val ids: List<Long>
) {
    val count: Int get() = ids.size
}

data class MergeReport(
    val atMillis: Long,
    val groups: List<MergeGroup>
) {
    val total: Int get() = groups.sumOf { it.count }
    fun nonEmpty(): List<MergeGroup> = groups.filter { it.count > 0 }

    fun toJson(): String = JSONObject().apply {
        put("atMillis", atMillis)
        put("groups", JSONArray().apply {
            groups.filter { it.count > 0 }.forEach { g ->
                put(JSONObject().apply {
                    put("key", g.key)
                    put("label", g.label)
                    put("ids", JSONArray().apply { g.ids.forEach { put(it) } })
                })
            }
        })
    }.toString()

    companion object {
        fun fromJson(text: String): MergeReport? = runCatching {
            val o = JSONObject(text)
            val arr = o.optJSONArray("groups") ?: JSONArray()
            val groups = ArrayList<MergeGroup>()
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val idsArr = g.optJSONArray("ids") ?: JSONArray()
                val ids = ArrayList<Long>()
                for (j in 0 until idsArr.length()) ids.add(idsArr.getLong(j))
                groups.add(MergeGroup(g.optString("key"), g.optString("label"), ids))
            }
            MergeReport(o.optLong("atMillis"), groups)
        }.getOrNull()

        /** The most recent merge, or null if none has run on this device. */
        fun load(context: Context): MergeReport? =
            AppPrefs(context).lastMergeReport.takeIf { it.isNotBlank() }?.let { fromJson(it) }

        fun save(context: Context, report: MergeReport) {
            AppPrefs(context).lastMergeReport = report.toJson()
        }
    }
}

/**
 * Collects ids while a merge runs. One builder per merge; [add] is called as each row lands.
 */
class MergeReportBuilder {
    private val byKey = LinkedHashMap<String, MutableList<Long>>()

    fun add(key: String, newId: Long) {
        byKey.getOrPut(key) { ArrayList() }.add(newId)
    }

    fun addAll(key: String, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        byKey.getOrPut(key) { ArrayList() }.addAll(ids)
    }

    fun build(): MergeReport = MergeReport(
        atMillis = System.currentTimeMillis(),
        groups = MergeCategories.ORDER.mapNotNull { (key, label) ->
            byKey[key]?.takeIf { it.isNotEmpty() }?.let { MergeGroup(key, label, it) }
        }
    )
}

/** Category keys, their display names, and where tapping an entry should go. */
object MergeCategories {

    /** Display order on the summary. */
    val ORDER: List<Pair<String, String>> = listOf(
        "bills" to "Sales entries",
        "estimates" to "Estimates",
        "quotations" to "Quotations",
        "salesReturns" to "Sales returns",
        "purchases" to "Purchases",
        "purchaseReturns" to "Purchase returns",
        "purchaseQuotations" to "Purchase orders (LPO)",
        "purchaseQuotes" to "Purchase quotations",
        "materialReceipts" to "Material receipts",
        "materialOuts" to "Material out",
        "hireInvoices" to "Hire invoices",
        "hireReturns" to "Hire returns",
        "labBills" to "Lab bills",
        "labTests" to "Lab tests",
        "patients" to "Patients",
        "items" to "Items",
        "customers" to "Customers",
        "suppliers" to "Suppliers",
        "receipts" to "Receipts",
        "expenses" to "Payments",
        "journalEntries" to "Journal entries",
        "accountHeads" to "Account heads",
        "diaryEntries" to "Diary entries",
        "custOrders" to "Orders",
        "savedCalcs" to "Saved calculations",
        "users" to "Users"
    )

    /**
     * Edit route for a category, with {id} replaced by the row id. Null where the app has
     * no per-record edit screen — those categories still show in the summary and list.
     */
    fun editRoute(key: String): String? = when (key) {
        "bills" -> "billing/edit/{id}"
        "estimates" -> "estimate/edit/{id}"
        "quotations" -> "quotation/edit/{id}"
        "salesReturns" -> "salesreturn/edit/{id}"
        "purchases" -> "purchase/edit/{id}"
        "purchaseReturns" -> "purchasereturn/edit/{id}"
        "purchaseQuotations" -> "lpo/edit/{id}"
        "purchaseQuotes" -> "pquote/edit/{id}"
        "materialReceipts" -> "materialreceipt/edit/{id}"
        "materialOuts" -> "materialout/edit/{id}"
        "hireInvoices" -> "hire/edit/{id}"
        "hireReturns" -> "hirereturn/edit/{id}"
        "labBills" -> "labbill/edit/{id}"
        "labTests" -> "labtest/edit/{id}"
        "items" -> "items/edit/{id}"
        "custOrders" -> "order/edit/{id}"
        "diaryEntries" -> "diary/edit/{id}"
        else -> null
    }
}
