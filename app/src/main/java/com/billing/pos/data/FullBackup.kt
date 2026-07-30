package com.billing.pos.data

import android.content.Context
import android.net.Uri
import com.billing.pos.diary.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Complete backup of the whole app (all tables incl. users, diary + attachment
 * files, and settings) into a single .zip, and a full restore that replaces
 * everything — used to move to a new device or recover after a reinstall.
 */
object FullBackup {

    suspend fun create(context: Context): File {
        val db = AppDatabase.get(context)
        val prefs = AppPrefs(context)

        val root = JSONObject()
        root.put("app", "pos-billing-full")
        root.put("version", 1)
        root.put("createdAt", System.currentTimeMillis())
        root.put(
            "settings",
            JSONObject()
                .put("companyName", prefs.companyName)
                .put("companyAddress", prefs.companyAddress)
                .put("companyPhone", prefs.companyPhone)
        )

        root.put("customers", JSONArray().apply { db.customerDao().all().forEach { put(custJson(it)) } })
        root.put("items", JSONArray().apply { db.itemDao().all().forEach { put(itemJson(it)) } })
        root.put("bills", JSONArray().apply { db.billDao().all().forEach { put(billJson(it)) } })
        root.put("billItems", JSONArray().apply { db.billDao().allLines().forEach { put(lineJson(it)) } })
        root.put("receipts", JSONArray().apply { db.receiptDao().all().forEach { put(receiptJson(it)) } })
        root.put("expenses", JSONArray().apply { db.expenseDao().all().forEach { put(expenseJson(it)) } })
        root.put("users", JSONArray().apply { db.userDao().all().forEach { put(userJson(it)) } })
        root.put("diaryEntries", JSONArray().apply { db.diaryDao().allEntries().forEach { put(entryJson(it)) } })
        root.put("diaryTypes", JSONArray().apply { db.diaryTypeDao().all().forEach { t -> put(JSONObject().put("id", t.id).put("name", t.name)) } })
        val attachments = db.diaryDao().allAttachments()
        root.put("diaryAttachments", JSONArray().apply { attachments.forEach { put(attJson(it)) } })
        val diaryBlocks = db.diaryDao().allBlocks()
        root.put("diaryBlocks", JSONArray().apply { diaryBlocks.forEach { put(blockJson(it)) } })

        root.put("suppliers", JSONArray().apply { db.supplierDao().all().forEach { put(supplierJson(it)) } })
        root.put("purchases", JSONArray().apply { db.purchaseDao().all().forEach { put(purchaseJson(it)) } })
        root.put("purchaseItems", JSONArray().apply { db.purchaseDao().allLines().forEach { put(pLineJson(it)) } })
        root.put("accountGroups", JSONArray().apply { db.accountDao().allGroups().forEach { put(groupJson(it)) } })
        root.put("accountHeads", JSONArray().apply { db.accountDao().allHeads().forEach { put(headJson(it)) } })
        root.put("journalEntries", JSONArray().apply { db.journalDao().allEntries().forEach { put(jEntryJson(it)) } })
        root.put("journalLines", JSONArray().apply { db.journalDao().allLines().forEach { put(jLineJson(it)) } })
        val itemAtts = db.itemAttachmentDao().all()
        root.put("itemAttachments", JSONArray().apply { itemAtts.forEach { put(itemAttJson(it)) } })
        root.put("itemBatches", JSONArray().apply { db.itemBatchDao().all().forEach { put(batchJson(it)) } })
        root.put("itemSizes", JSONArray().apply { db.itemSizeDao().all().forEach { put(sizeJson(it)) } })
        root.put("quotations", JSONArray().apply { db.quotationDao().all().forEach { put(quotationJson(it)) } })
        root.put("quotationItems", JSONArray().apply { db.quotationDao().allLines().forEach { put(qItemJson(it)) } })
        root.put("estimates", JSONArray().apply { db.estimateDao().all().forEach { put(estimateJson(it)) } })
        root.put("estimateItems", JSONArray().apply { db.estimateDao().allLines().forEach { put(eItemJson(it)) } })
        root.put("salesReturns", JSONArray().apply { db.salesReturnDao().all().forEach { put(salesReturnJson(it)) } })
        root.put("salesReturnItems", JSONArray().apply { db.salesReturnDao().allLines().forEach { put(srItemJson(it)) } })
        root.put("purchaseReturns", JSONArray().apply { db.purchaseReturnDao().all().forEach { put(purchaseReturnJson(it)) } })
        root.put("purchaseReturnItems", JSONArray().apply { db.purchaseReturnDao().allLines().forEach { put(prItemJson(it)) } })
        root.put("purchaseQuotations", JSONArray().apply { db.purchaseQuotationDao().all().forEach { put(lpoJson(it)) } })
        root.put("purchaseQuotationItems", JSONArray().apply { db.purchaseQuotationDao().allLines().forEach { put(lpoItemJson(it)) } })
        root.put("hireInvoices", JSONArray().apply { db.hireInvoiceDao().all().forEach { put(hireJson(it)) } })
        root.put("hireInvoiceItems", JSONArray().apply { db.hireInvoiceDao().allLines().forEach { put(hireItemJson(it)) } })
        root.put("hireReturns", JSONArray().apply { db.hireReturnDao().all().forEach { put(hireRetJson(it)) } })
        root.put("hireReturnItems", JSONArray().apply { db.hireReturnDao().allLines().forEach { put(hireRetItemJson(it)) } })
        root.put("labTests", JSONArray().apply { db.labTestDao().allTests().forEach { put(labTestJson(it)) } })
        root.put("labEvaluations", JSONArray().apply { db.labTestDao().allEvaluations().forEach { put(labEvalJson(it)) } })
        root.put("patients", JSONArray().apply { db.patientDao().all().forEach { put(patientJson(it)) } })
        root.put("labBills", JSONArray().apply { db.labBillDao().allBills().forEach { put(labBillJson(it)) } })
        root.put("labBillTests", JSONArray().apply { db.labBillDao().allBillTests().forEach { put(labBillTestJson(it)) } })
        root.put("labResults", JSONArray().apply { db.labBillDao().allResults().forEach { put(labResultJson(it)) } })
        root.put("labGroups", JSONArray().apply { db.labMasterDao().allGroups().forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        root.put("labEvalMasters", JSONArray().apply { db.labMasterDao().allEvals().forEach { put(labEvalMasterJson(it)) } })
        root.put("labHeadings", JSONArray().apply { db.labMasterDao().allHeadings().forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        root.put("labReceipts", JSONArray().apply { db.labReceiptDao().all().forEach { put(labReceiptJson(it)) } })
        root.put("labDoctors", JSONArray().apply { db.labMasterDao().allDoctors().forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        root.put("materialOuts", JSONArray().apply { db.materialOutDao().all().forEach { put(matOutJson(it)) } })
        root.put("materialOutItems", JSONArray().apply { db.materialOutDao().allLines().forEach { put(matOutItemJson(it)) } })
        root.put("materialReceipts", JSONArray().apply { db.materialReceiptDao().all().forEach { put(matRecJson(it)) } })
        root.put("materialReceiptItems", JSONArray().apply { db.materialReceiptDao().allLines().forEach { put(matRecItemJson(it)) } })
        val billAtts = db.billAttachmentDao().all()
        root.put("billAttachments", JSONArray().apply { billAtts.forEach { put(billAttJson(it)) } })
        val expenseAtts = db.expenseAttachmentDao().all()
        root.put("expenseAttachments", JSONArray().apply { expenseAtts.forEach { put(expenseAttJson(it)) } })
        root.put("savedCalcs", JSONArray().apply { db.savedCalcDao().all().forEach { put(savedCalcJson(it)) } })
        root.put("purchaseQuotes", JSONArray().apply { db.purchaseQuoteDao().all().forEach { put(pQuoteJson(it)) } })
        root.put("purchaseQuoteItems", JSONArray().apply { db.purchaseQuoteDao().allLines().forEach { put(pQuoteItemJson(it)) } })
        val custAtts = db.customerAttachmentDao().all()
        root.put("customerAttachments", JSONArray().apply { custAtts.forEach { put(custAttJson(it)) } })
        root.put("custOrders", JSONArray().apply { db.custOrderDao().all().forEach { put(orderJson(it)) } })
        root.put("custOrderItems", JSONArray().apply { db.custOrderDao().allLines().forEach { put(orderItemJson(it)) } })
        val orderAtts = db.custOrderDao().allAttachments()
        root.put("custOrderAttachments", JSONArray().apply { orderAtts.forEach { put(orderAttJson(it)) } })

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val zip = File(dir, "pos-full-backup.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("full-backup.json"))
            zos.write(root.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            attachments.forEach { att ->
                val f = File(att.path)
                if (f.exists()) {
                    zos.putNextEntry(ZipEntry("files/" + f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            diaryBlocks.forEach { b ->
                if (b.path.isNotBlank()) {
                    val f = File(b.path)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry("files/" + f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            itemAtts.forEach { att ->
                val f = File(att.path)
                if (f.exists()) {
                    zos.putNextEntry(ZipEntry("itemfiles/" + f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            billAtts.forEach { att ->
                val f = File(att.path)
                if (f.exists()) {
                    zos.putNextEntry(ZipEntry("billfiles/" + f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            expenseAtts.forEach { att ->
                val f = File(att.path)
                if (f.exists()) {
                    zos.putNextEntry(ZipEntry("expensefiles/" + f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            orderAtts.forEach { att ->
                val f = File(att.path)
                if (f.exists()) { zos.putNextEntry(ZipEntry("orderfiles/" + f.name)); f.inputStream().use { it.copyTo(zos) }; zos.closeEntry() }
            }
            custAtts.forEach { att ->
                val f = File(att.path)
                if (f.exists()) {
                    zos.putNextEntry(ZipEntry("customerfiles/" + f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return zip
    }

    suspend fun restore(context: Context, uri: Uri, merge: Boolean = false): Result<String> = runCatching {
        val filesDir = AttachmentStore.dir(context)
        val itemFilesDir = com.billing.pos.items.ItemAttachmentStore.dir(context)
        val billFilesDir = com.billing.pos.bills.BillAttachmentStore.dir(context)
        val expenseFilesDir = com.billing.pos.expenses.ExpenseAttachmentStore.dir(context)
        val customerFilesDir = CustomerAttachmentStore.dir(context)
        val orderFilesDir = OrderAttachmentStore.dir(context)
        var json: String? = null
        // Streamed, never loaded whole: a backup with photos and voice notes can be hundreds
        // of MB, and reading it into a ByteArray first is an instant OutOfMemory on a phone.
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read the file")
        ZipInputStream(input.buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    when {
                        e.name.endsWith(".json") -> json = zis.readBytes().toString(Charsets.UTF_8)
                        e.name.startsWith("itemfiles/") -> {
                            val out = File(itemFilesDir, e.name.removePrefix("itemfiles/"))
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        e.name.startsWith("billfiles/") -> {
                            val out = File(billFilesDir, e.name.removePrefix("billfiles/"))
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        e.name.startsWith("expensefiles/") -> {
                            val out = File(expenseFilesDir, e.name.removePrefix("expensefiles/"))
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        e.name.startsWith("customerfiles/") -> {
                            val out = File(customerFilesDir, e.name.removePrefix("customerfiles/"))
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        e.name.startsWith("orderfiles/") -> {
                            val out = File(orderFilesDir, e.name.removePrefix("orderfiles/"))
                            out.outputStream().use { zis.copyTo(it) }
                        }
                        e.name.startsWith("files/") -> {
                            val out = File(filesDir, e.name.removePrefix("files/"))
                            out.outputStream().use { zis.copyTo(it) }
                        }
                    }
                }
                e = zis.nextEntry
            }
        }
        val data = json ?: error("Not a valid backup file")
        val root = JSONObject(data)

        val db = AppDatabase.get(context)

        if (merge) {
            val report = mergeInto(context, db, root)
            MergeReport.save(context, report)
            return@runCatching "Merge complete — ${report.total} record(s) added"
        }

        withContext(Dispatchers.IO) { db.clearAllTables() }

        root.optJSONObject("settings")?.let { s ->
            val prefs = AppPrefs(context)
            prefs.companyName = s.optString("companyName", "My Shop")
            prefs.companyAddress = s.optString("companyAddress", "")
            prefs.companyPhone = s.optString("companyPhone", "")
        }

        root.optJSONArray("customers")?.let { for (i in 0 until it.length()) db.customerDao().insert(readCust(it.getJSONObject(i))) }
        root.optJSONArray("items")?.let { for (i in 0 until it.length()) db.itemDao().insert(readItem(it.getJSONObject(i))) }
        root.optJSONArray("bills")?.let { for (i in 0 until it.length()) db.billDao().insertBill(readBill(it.getJSONObject(i))) }
        root.optJSONArray("billItems")?.let {
            val lines = ArrayList<BillItem>()
            for (i in 0 until it.length()) lines.add(readLine(it.getJSONObject(i)))
            if (lines.isNotEmpty()) db.billDao().insertLines(lines)
        }
        root.optJSONArray("receipts")?.let { for (i in 0 until it.length()) db.receiptDao().insert(readReceipt(it.getJSONObject(i))) }
        root.optJSONArray("expenses")?.let { for (i in 0 until it.length()) db.expenseDao().insert(readExpense(it.getJSONObject(i))) }
        root.optJSONArray("users")?.let { for (i in 0 until it.length()) db.userDao().insert(readUser(it.getJSONObject(i))) }
        root.optJSONArray("diaryTypes")?.let {
            for (i in 0 until it.length()) {
                val o = it.getJSONObject(i)
                db.diaryTypeDao().insert(DiaryType(o.optLong("id"), o.optString("name")))
            }
        }
        root.optJSONArray("diaryEntries")?.let { for (i in 0 until it.length()) db.diaryDao().insert(readEntry(it.getJSONObject(i))) }
        root.optJSONArray("diaryBlocks")?.let {
            for (i in 0 until it.length()) db.diaryDao().insertBlock(readBlock(context, it.getJSONObject(i)))
        }
        root.optJSONArray("diaryAttachments")?.let {
            for (i in 0 until it.length()) db.diaryDao().insertAttachment(readAtt(context, it.getJSONObject(i)))
        }
        root.optJSONArray("suppliers")?.let { for (i in 0 until it.length()) db.supplierDao().insert(readSupplier(it.getJSONObject(i))) }
        root.optJSONArray("purchases")?.let { for (i in 0 until it.length()) db.purchaseDao().insertPurchase(readPurchase(it.getJSONObject(i))) }
        root.optJSONArray("purchaseItems")?.let {
            val lines = ArrayList<PurchaseItem>()
            for (i in 0 until it.length()) lines.add(readPLine(it.getJSONObject(i)))
            if (lines.isNotEmpty()) db.purchaseDao().insertLines(lines)
        }
        root.optJSONArray("accountGroups")?.let { for (i in 0 until it.length()) db.accountDao().insertGroup(readGroup(it.getJSONObject(i))) }
        root.optJSONArray("accountHeads")?.let { for (i in 0 until it.length()) db.accountDao().insertHead(readHead(it.getJSONObject(i))) }
        root.optJSONArray("journalEntries")?.let { for (i in 0 until it.length()) db.journalDao().insertEntry(readJEntry(it.getJSONObject(i))) }
        root.optJSONArray("journalLines")?.let {
            val lines = ArrayList<JournalLine>()
            for (i in 0 until it.length()) lines.add(readJLine(it.getJSONObject(i)))
            if (lines.isNotEmpty()) db.journalDao().insertLines(lines)
        }
        root.optJSONArray("itemAttachments")?.let {
            for (i in 0 until it.length()) db.itemAttachmentDao().insert(readItemAtt(context, it.getJSONObject(i)))
        }
        root.optJSONArray("itemBatches")?.let {
            for (i in 0 until it.length()) db.itemBatchDao().insert(readBatch(it.getJSONObject(i)))
        }
        root.optJSONArray("itemSizes")?.let {
            for (i in 0 until it.length()) db.itemSizeDao().insert(readSize(it.getJSONObject(i)))
        }
        root.optJSONArray("quotations")?.let { for (i in 0 until it.length()) db.quotationDao().insertHeader(readQuotation(it.getJSONObject(i))) }
        root.optJSONArray("quotationItems")?.let {
            val ls = ArrayList<QuotationItem>()
            for (i in 0 until it.length()) ls.add(readQItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.quotationDao().insertLines(ls)
        }
        root.optJSONArray("estimates")?.let { for (i in 0 until it.length()) db.estimateDao().insertHeader(readEstimate(it.getJSONObject(i))) }
        root.optJSONArray("estimateItems")?.let {
            val ls = ArrayList<EstimateItem>()
            for (i in 0 until it.length()) ls.add(readEItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.estimateDao().insertLines(ls)
        }
        root.optJSONArray("salesReturns")?.let { for (i in 0 until it.length()) db.salesReturnDao().insertHeader(readSalesReturn(it.getJSONObject(i))) }
        root.optJSONArray("salesReturnItems")?.let {
            val ls = ArrayList<SalesReturnItem>()
            for (i in 0 until it.length()) ls.add(readSRItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.salesReturnDao().insertLines(ls)
        }
        root.optJSONArray("purchaseReturns")?.let { for (i in 0 until it.length()) db.purchaseReturnDao().insertHeader(readPurchaseReturn(it.getJSONObject(i))) }
        root.optJSONArray("purchaseReturnItems")?.let {
            val ls = ArrayList<PurchaseReturnItem>()
            for (i in 0 until it.length()) ls.add(readPRItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.purchaseReturnDao().insertLines(ls)
        }
        root.optJSONArray("purchaseQuotations")?.let { for (i in 0 until it.length()) db.purchaseQuotationDao().insertHeader(readLpo(it.getJSONObject(i))) }
        root.optJSONArray("purchaseQuotationItems")?.let {
            val ls = ArrayList<PurchaseQuotationItem>()
            for (i in 0 until it.length()) ls.add(readLpoItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.purchaseQuotationDao().insertLines(ls)
        }
        root.optJSONArray("hireInvoices")?.let { for (i in 0 until it.length()) db.hireInvoiceDao().insertHeader(readHire(it.getJSONObject(i))) }
        root.optJSONArray("hireInvoiceItems")?.let {
            val ls = ArrayList<HireInvoiceItem>()
            for (i in 0 until it.length()) ls.add(readHireItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.hireInvoiceDao().insertLines(ls)
        }
        root.optJSONArray("hireReturns")?.let { for (i in 0 until it.length()) db.hireReturnDao().insertHeader(readHireRet(it.getJSONObject(i))) }
        root.optJSONArray("hireReturnItems")?.let {
            val ls = ArrayList<HireReturnItem>()
            for (i in 0 until it.length()) ls.add(readHireRetItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.hireReturnDao().insertLines(ls)
        }
        root.optJSONArray("labTests")?.let { for (i in 0 until it.length()) db.labTestDao().insertTest(readLabTest(it.getJSONObject(i))) }
        root.optJSONArray("labEvaluations")?.let {
            val ls = ArrayList<LabEvaluation>()
            for (i in 0 until it.length()) ls.add(readLabEval(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.labTestDao().insertEvaluations(ls)
        }
        root.optJSONArray("patients")?.let { for (i in 0 until it.length()) db.patientDao().insert(readPatient(it.getJSONObject(i))) }
        root.optJSONArray("labBills")?.let { for (i in 0 until it.length()) db.labBillDao().insertBill(readLabBill(it.getJSONObject(i))) }
        root.optJSONArray("labBillTests")?.let {
            val ls = ArrayList<LabBillTest>()
            for (i in 0 until it.length()) ls.add(readLabBillTest(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.labBillDao().insertTests(ls)
        }
        root.optJSONArray("labResults")?.let {
            val ls = ArrayList<LabResultValue>()
            for (i in 0 until it.length()) ls.add(readLabResult(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.labBillDao().insertResults(ls)
        }
        root.optJSONArray("labGroups")?.let { for (i in 0 until it.length()) { val o = it.getJSONObject(i); db.labMasterDao().insertGroup(LabGroup(o.optLong("id"), o.optString("name"))) } }
        root.optJSONArray("labEvalMasters")?.let { for (i in 0 until it.length()) db.labMasterDao().insertEval(readLabEvalMaster(it.getJSONObject(i))) }
        root.optJSONArray("labHeadings")?.let { for (i in 0 until it.length()) { val o = it.getJSONObject(i); db.labMasterDao().insertHeading(LabHeading(o.optLong("id"), o.optString("name"))) } }
        root.optJSONArray("labReceipts")?.let { for (i in 0 until it.length()) db.labReceiptDao().insert(readLabReceipt(it.getJSONObject(i))) }
        root.optJSONArray("labDoctors")?.let { for (i in 0 until it.length()) { val o = it.getJSONObject(i); db.labMasterDao().insertDoctor(LabDoctor(o.optLong("id"), o.optString("name"))) } }
        root.optJSONArray("materialOuts")?.let { for (i in 0 until it.length()) db.materialOutDao().insertHeader(readMatOut(it.getJSONObject(i))) }
        root.optJSONArray("materialOutItems")?.let {
            val ls = ArrayList<MaterialOutItem>()
            for (i in 0 until it.length()) ls.add(readMatOutItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.materialOutDao().insertLines(ls)
        }
        root.optJSONArray("billAttachments")?.let {
            for (i in 0 until it.length()) db.billAttachmentDao().insert(readBillAtt(context, it.getJSONObject(i)))
        }
        root.optJSONArray("expenseAttachments")?.let {
            for (i in 0 until it.length()) db.expenseAttachmentDao().insert(readExpenseAtt(context, it.getJSONObject(i)))
        }
        root.optJSONArray("savedCalcs")?.let {
            for (i in 0 until it.length()) db.savedCalcDao().insert(readSavedCalc(it.getJSONObject(i)))
        }
        root.optJSONArray("purchaseQuotes")?.let {
            for (i in 0 until it.length()) db.purchaseQuoteDao().insertHeader(readPQuote(it.getJSONObject(i)))
        }
        root.optJSONArray("purchaseQuoteItems")?.let {
            val ls = ArrayList<PurchaseQuoteItem>()
            for (i in 0 until it.length()) ls.add(readPQuoteItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.purchaseQuoteDao().insertLines(ls)
        }
        root.optJSONArray("customerAttachments")?.let {
            for (i in 0 until it.length()) db.customerAttachmentDao().insert(readCustAtt(context, it.getJSONObject(i)))
        }
        root.optJSONArray("custOrders")?.let { for (i in 0 until it.length()) db.custOrderDao().insertHeader(readOrder(it.getJSONObject(i))) }
        root.optJSONArray("custOrderItems")?.let {
            val ls = ArrayList<CustOrderItem>(); for (i in 0 until it.length()) ls.add(readOrderItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.custOrderDao().insertLines(ls)
        }
        root.optJSONArray("custOrderAttachments")?.let { for (i in 0 until it.length()) db.custOrderDao().insertAttachment(readOrderAtt(context, it.getJSONObject(i))) }
        root.optJSONArray("materialReceipts")?.let {
            for (i in 0 until it.length()) db.materialReceiptDao().insertHeader(readMatRec(it.getJSONObject(i)))
        }
        root.optJSONArray("materialReceiptItems")?.let {
            val ls = ArrayList<MaterialReceiptItem>()
            for (i in 0 until it.length()) ls.add(readMatRecItem(it.getJSONObject(i)))
            if (ls.isNotEmpty()) db.materialReceiptDao().insertLines(ls)
        }

        "Restore complete"
    }

    /**
     * Appends a backup into the existing data (no wipe). Masters (customers, suppliers,
     * items, account groups/heads) are reused when a same-named row already exists;
     * documents are always inserted as new rows with their parent ids remapped.
     * Users are intentionally NOT merged so local logins are preserved.
     */
    private suspend fun mergeInto(context: Context, db: AppDatabase, root: JSONObject): MergeReport {
        val log = MergeReportBuilder()
        // Customers
        val custByName = HashMap<String, Long>()
        db.customerDao().all().forEach { custByName[it.name.lowercase()] = it.id }
        val custMap = HashMap<Long, Long>()
        root.optJSONArray("customers")?.let {
            for (i in 0 until it.length()) {
                val c = readCust(it.getJSONObject(i)); val key = c.name.lowercase()
                custMap[c.id] = custByName[key]
                    ?: db.customerDao().insert(c.copy(id = 0)).also { nid -> custByName[key] = nid; log.add("customers", nid) }
            }
        }
        // Suppliers
        val suppByName = HashMap<String, Long>()
        db.supplierDao().all().forEach { suppByName[it.name.lowercase()] = it.id }
        val suppMap = HashMap<Long, Long>()
        root.optJSONArray("suppliers")?.let {
            for (i in 0 until it.length()) {
                val s = readSupplier(it.getJSONObject(i)); val key = s.name.lowercase()
                suppMap[s.id] = suppByName[key]
                    ?: db.supplierDao().insert(s.copy(id = 0)).also { nid -> suppByName[key] = nid; log.add("suppliers", nid) }
            }
        }
        // Items
        val itemByName = HashMap<String, Long>()
        db.itemDao().all().forEach { itemByName[it.name.lowercase()] = it.id }
        val itemMap = HashMap<Long, Long>()
        root.optJSONArray("items")?.let {
            for (i in 0 until it.length()) {
                val it2 = readItem(it.getJSONObject(i)); val key = it2.name.lowercase()
                itemMap[it2.id] = itemByName[key]
                    ?: db.itemDao().insert(it2.copy(id = 0)).also { nid -> itemByName[key] = nid; log.add("items", nid) }
            }
        }
        // Account groups
        val groupByName = HashMap<String, Long>()
        db.accountDao().allGroups().forEach { groupByName[it.name.lowercase()] = it.id }
        val groupMap = HashMap<Long, Long>()
        root.optJSONArray("accountGroups")?.let {
            for (i in 0 until it.length()) {
                val g = readGroup(it.getJSONObject(i)); val key = g.name.lowercase()
                groupMap[g.id] = groupByName[key] ?: db.accountDao().insertGroup(g.copy(id = 0)).also { nid -> groupByName[key] = nid }
            }
        }
        // Account heads
        val headByName = HashMap<String, Long>()
        db.accountDao().allHeads().forEach { headByName[it.name.lowercase()] = it.id }
        val headMap = HashMap<Long, Long>()
        root.optJSONArray("accountHeads")?.let {
            for (i in 0 until it.length()) {
                val h = readHead(it.getJSONObject(i)); val key = h.name.lowercase()
                headMap[h.id] = headByName[key]
                    ?: db.accountDao().insertHead(h.copy(id = 0, groupId = groupMap[h.groupId] ?: h.groupId))
                        .also { nid -> headByName[key] = nid; log.add("accountHeads", nid) }
            }
        }

        // Diary types — merged before entries, which remap their typeId onto these.
        val typeByName = HashMap<String, Long>()
        db.diaryTypeDao().all().forEach { typeByName[it.name.lowercase()] = it.id }
        val diaryTypeMap = HashMap<Long, Long>()
        root.optJSONArray("diaryTypes")?.let {
            for (i in 0 until it.length()) {
                val o = it.getJSONObject(i)
                val name = o.optString("name"); val key = name.lowercase()
                diaryTypeMap[o.optLong("id")] =
                    typeByName[key] ?: db.diaryTypeDao().insert(DiaryType(name = name)).also { nid -> typeByName[key] = nid }
            }
        }

        // Users — matched by username so the same person is not duplicated.
        val userByName = HashMap<String, Long>()
        db.userDao().all().forEach { userByName[it.username.lowercase()] = it.id }
        root.optJSONArray("users")?.let {
            for (i in 0 until it.length()) {
                val u = readUser(it.getJSONObject(i)); val key = u.username.lowercase()
                if (userByName.containsKey(key)) continue
                val nid = db.userDao().insert(u.copy(id = 0))
                userByName[key] = nid
                log.add("users", nid)
            }
        }

        // Bills + items
        val expMap = HashMap<Long, Long>()
        val billMap = HashMap<Long, Long>()
        root.optJSONArray("bills")?.let {
            for (i in 0 until it.length()) {
                val b = readBill(it.getJSONObject(i))
                billMap[b.id] = db.billDao().insertBill(b.copy(id = 0, customerId = custMap[b.customerId] ?: b.customerId))
                    .also { nid -> log.add("bills", nid) }
            }
        }
        root.optJSONArray("billItems")?.let {
            val lines = ArrayList<BillItem>()
            for (i in 0 until it.length()) {
                val l = readLine(it.getJSONObject(i)); val nb = billMap[l.billId] ?: continue
                lines.add(l.copy(id = 0, billId = nb))
            }
            if (lines.isNotEmpty()) db.billDao().insertLines(lines)
        }
        // Purchases + items
        val purMap = HashMap<Long, Long>()
        root.optJSONArray("purchases")?.let {
            for (i in 0 until it.length()) {
                val p = readPurchase(it.getJSONObject(i))
                purMap[p.id] = db.purchaseDao().insertPurchase(p.copy(id = 0, supplierId = suppMap[p.supplierId] ?: p.supplierId))
                    .also { nid -> log.add("purchases", nid) }
            }
        }
        root.optJSONArray("purchaseItems")?.let {
            val lines = ArrayList<PurchaseItem>()
            for (i in 0 until it.length()) {
                val l = readPLine(it.getJSONObject(i)); val np = purMap[l.purchaseId] ?: continue
                lines.add(l.copy(id = 0, purchaseId = np))
            }
            if (lines.isNotEmpty()) db.purchaseDao().insertLines(lines)
        }
        // Receipts / expenses
        root.optJSONArray("receipts")?.let {
            for (i in 0 until it.length()) {
                val r = readReceipt(it.getJSONObject(i))
                val nb = if (r.billId > 0) (billMap[r.billId] ?: 0L) else 0L
                db.receiptDao().insert(r.copy(id = 0, billId = nb))
            }
        }
        root.optJSONArray("expenses")?.let {
            for (i in 0 until it.length()) {
                val ex = readExpense(it.getJSONObject(i))
                val np = if (ex.purchaseId > 0) (purMap[ex.purchaseId] ?: 0L) else 0L
                expMap[ex.id] = db.expenseDao().insert(ex.copy(id = 0, purchaseId = np)).also { nid -> log.add("expenses", nid) }
            }
        }
        // Journals
        val jMap = HashMap<Long, Long>()
        root.optJSONArray("journalEntries")?.let {
            for (i in 0 until it.length()) {
                val e = readJEntry(it.getJSONObject(i))
                jMap[e.id] = db.journalDao().insertEntry(e.copy(id = 0))
            }
        }
        root.optJSONArray("journalLines")?.let {
            val lines = ArrayList<JournalLine>()
            for (i in 0 until it.length()) {
                val l = readJLine(it.getJSONObject(i)); val ne = jMap[l.entryId] ?: continue
                lines.add(l.copy(id = 0, entryId = ne, headId = headMap[l.headId] ?: l.headId))
            }
            if (lines.isNotEmpty()) db.journalDao().insertLines(lines)
        }
        // Diary + attachments
        val diaryMap = HashMap<Long, Long>()
        root.optJSONArray("diaryEntries")?.let {
            for (i in 0 until it.length()) {
                val e = readEntry(it.getJSONObject(i))
                diaryMap[e.id] = db.diaryDao()
                    .insert(e.copy(id = 0, typeId = diaryTypeMap[e.typeId] ?: 0L))
                    .also { nid -> log.add("diaryEntries", nid) }
            }
        }
        root.optJSONArray("diaryAttachments")?.let {
            for (i in 0 until it.length()) {
                val a = readAtt(context, it.getJSONObject(i)); val ne = diaryMap[a.entryId] ?: continue
                db.diaryDao().insertAttachment(a.copy(id = 0, entryId = ne))
            }
        }
        root.optJSONArray("diaryBlocks")?.let {
            for (i in 0 until it.length()) {
                val b = readBlock(context, it.getJSONObject(i)); val ne = diaryMap[b.entryId] ?: continue
                db.diaryDao().insertBlock(b.copy(id = 0, entryId = ne))
            }
        }
        // Item attachments
        root.optJSONArray("itemAttachments")?.let {
            for (i in 0 until it.length()) {
                val a = readItemAtt(context, it.getJSONObject(i)); val ni = itemMap[a.itemId] ?: continue
                db.itemAttachmentDao().insert(a.copy(id = 0, itemId = ni))
            }
        }
        root.optJSONArray("itemBatches")?.let {
            for (i in 0 until it.length()) {
                val b = readBatch(it.getJSONObject(i)); val ni = itemMap[b.itemId] ?: continue
                db.itemBatchDao().insert(b.copy(id = 0, itemId = ni))
            }
        }
        root.optJSONArray("itemSizes")?.let {
            for (i in 0 until it.length()) {
                val s = readSize(it.getJSONObject(i)); val ni = itemMap[s.itemId] ?: continue
                db.itemSizeDao().insert(s.copy(id = 0, itemId = ni))
            }
        }
        // Quotations
        val quoteMap = HashMap<Long, Long>()
        root.optJSONArray("quotations")?.let {
            for (i in 0 until it.length()) {
                val q = readQuotation(it.getJSONObject(i)); quoteMap[q.id] = db.quotationDao().insertHeader(q.copy(id = 0))
            }
        }
        root.optJSONArray("quotationItems")?.let {
            for (i in 0 until it.length()) {
                val l = readQItem(it.getJSONObject(i)); val nq = quoteMap[l.quotationId] ?: continue
                db.quotationDao().insertLines(listOf(l.copy(id = 0, quotationId = nq)))
            }
        }
        val estMap = HashMap<Long, Long>()
        root.optJSONArray("estimates")?.let {
            for (i in 0 until it.length()) {
                val e = readEstimate(it.getJSONObject(i)); estMap[e.id] = db.estimateDao().insertHeader(e.copy(id = 0))
            }
        }
        root.optJSONArray("estimateItems")?.let {
            for (i in 0 until it.length()) {
                val l = readEItem(it.getJSONObject(i)); val ne = estMap[l.estimateId] ?: continue
                db.estimateDao().insertLines(listOf(l.copy(id = 0, estimateId = ne)))
            }
        }
        // Sales returns
        val srMap = HashMap<Long, Long>()
        root.optJSONArray("salesReturns")?.let {
            for (i in 0 until it.length()) {
                val r = readSalesReturn(it.getJSONObject(i)); srMap[r.id] = db.salesReturnDao().insertHeader(r.copy(id = 0))
            }
        }
        root.optJSONArray("salesReturnItems")?.let {
            for (i in 0 until it.length()) {
                val l = readSRItem(it.getJSONObject(i)); val nr = srMap[l.returnId] ?: continue
                db.salesReturnDao().insertLines(listOf(l.copy(id = 0, returnId = nr)))
            }
        }
        // Purchase returns
        val prMap = HashMap<Long, Long>()
        root.optJSONArray("purchaseReturns")?.let {
            for (i in 0 until it.length()) {
                val r = readPurchaseReturn(it.getJSONObject(i)); prMap[r.id] = db.purchaseReturnDao().insertHeader(r.copy(id = 0))
            }
        }
        root.optJSONArray("purchaseReturnItems")?.let {
            for (i in 0 until it.length()) {
                val l = readPRItem(it.getJSONObject(i)); val nr = prMap[l.returnId] ?: continue
                db.purchaseReturnDao().insertLines(listOf(l.copy(id = 0, returnId = nr)))
            }
        }
        // Purchase quotations (LPO)
        val lpoMap = HashMap<Long, Long>()
        root.optJSONArray("purchaseQuotations")?.let {
            for (i in 0 until it.length()) {
                val r = readLpo(it.getJSONObject(i)); lpoMap[r.id] = db.purchaseQuotationDao().insertHeader(r.copy(id = 0))
            }
        }
        root.optJSONArray("purchaseQuotationItems")?.let {
            for (i in 0 until it.length()) {
                val l = readLpoItem(it.getJSONObject(i)); val nl = lpoMap[l.lpoId] ?: continue
                db.purchaseQuotationDao().insertLines(listOf(l.copy(id = 0, lpoId = nl)))
            }
        }
        // Hire invoices
        val hireMap = HashMap<Long, Long>()
        root.optJSONArray("hireInvoices")?.let {
            for (i in 0 until it.length()) {
                val h = readHire(it.getJSONObject(i)); hireMap[h.id] = db.hireInvoiceDao().insertHeader(h.copy(id = 0))
            }
        }
        root.optJSONArray("hireInvoiceItems")?.let {
            for (i in 0 until it.length()) {
                val l = readHireItem(it.getJSONObject(i)); val nh = hireMap[l.hireId] ?: continue
                db.hireInvoiceDao().insertLines(listOf(l.copy(id = 0, hireId = nh)))
            }
        }
        // Hire returns
        val hireRetMap = HashMap<Long, Long>()
        root.optJSONArray("hireReturns")?.let {
            for (i in 0 until it.length()) {
                val r = readHireRet(it.getJSONObject(i))
                hireRetMap[r.id] = db.hireReturnDao().insertHeader(r.copy(id = 0, hireId = hireMap[r.hireId] ?: r.hireId))
            }
        }
        root.optJSONArray("hireReturnItems")?.let {
            for (i in 0 until it.length()) {
                val l = readHireRetItem(it.getJSONObject(i)); val nr = hireRetMap[l.returnId] ?: continue
                db.hireReturnDao().insertLines(listOf(l.copy(id = 0, returnId = nr)))
            }
        }
        // Lab tests + evaluations
        val labTestMap = HashMap<Long, Long>()
        root.optJSONArray("labTests")?.let {
            for (i in 0 until it.length()) {
                val t = readLabTest(it.getJSONObject(i)); labTestMap[t.id] = db.labTestDao().insertTest(t.copy(id = 0))
            }
        }
        root.optJSONArray("labEvaluations")?.let {
            for (i in 0 until it.length()) {
                val e = readLabEval(it.getJSONObject(i)); val nt = labTestMap[e.testId] ?: continue
                db.labTestDao().insertEvaluations(listOf(e.copy(id = 0, testId = nt)))
            }
        }
        // Patients
        val patientMap = HashMap<Long, Long>()
        root.optJSONArray("patients")?.let {
            for (i in 0 until it.length()) {
                val p = readPatient(it.getJSONObject(i)); patientMap[p.id] = db.patientDao().insert(p.copy(id = 0))
            }
        }
        // Lab bills + tests + results
        val labBillMap = HashMap<Long, Long>()
        root.optJSONArray("labBills")?.let {
            for (i in 0 until it.length()) {
                val b = readLabBill(it.getJSONObject(i))
                labBillMap[b.id] = db.labBillDao().insertBill(b.copy(id = 0, patientId = patientMap[b.patientId] ?: b.patientId))
            }
        }
        root.optJSONArray("labBillTests")?.let {
            for (i in 0 until it.length()) {
                val t = readLabBillTest(it.getJSONObject(i)); val nb = labBillMap[t.billId] ?: continue
                db.labBillDao().insertTests(listOf(t.copy(id = 0, billId = nb, testId = labTestMap[t.testId] ?: t.testId)))
            }
        }
        root.optJSONArray("labResults")?.let {
            for (i in 0 until it.length()) {
                val r = readLabResult(it.getJSONObject(i)); val nb = labBillMap[r.billId] ?: continue
                db.labBillDao().insertResults(listOf(r.copy(id = 0, billId = nb, testId = labTestMap[r.testId] ?: r.testId)))
            }
        }
        // Lab masters (deduped by name)
        root.optJSONArray("labGroups")?.let { for (i in 0 until it.length()) db.labMasterDao().insertGroup(LabGroup(name = it.getJSONObject(i).optString("name"))) }
        root.optJSONArray("labEvalMasters")?.let { for (i in 0 until it.length()) db.labMasterDao().insertEval(readLabEvalMaster(it.getJSONObject(i)).copy(id = 0)) }
        root.optJSONArray("labHeadings")?.let { for (i in 0 until it.length()) db.labMasterDao().insertHeading(LabHeading(name = it.getJSONObject(i).optString("name"))) }
        root.optJSONArray("labReceipts")?.let {
            for (i in 0 until it.length()) {
                val r = readLabReceipt(it.getJSONObject(i)); val nb = labBillMap[r.labBillId] ?: continue
                db.labReceiptDao().insert(r.copy(id = 0, labBillId = nb))
            }
        }
        root.optJSONArray("labDoctors")?.let { for (i in 0 until it.length()) db.labMasterDao().insertDoctor(LabDoctor(name = it.getJSONObject(i).optString("name"))) }
        // Material out
        val matOutMap = HashMap<Long, Long>()
        root.optJSONArray("materialOuts")?.let {
            for (i in 0 until it.length()) { val m = readMatOut(it.getJSONObject(i)); matOutMap[m.id] = db.materialOutDao().insertHeader(m.copy(id = 0)) }
        }
        root.optJSONArray("materialOutItems")?.let {
            for (i in 0 until it.length()) { val l = readMatOutItem(it.getJSONObject(i)); val nm = matOutMap[l.outId] ?: continue; db.materialOutDao().insertLines(listOf(l.copy(id = 0, outId = nm))) }
        }
        // Bill attachments
        root.optJSONArray("billAttachments")?.let {
            for (i in 0 until it.length()) {
                val a = readBillAtt(context, it.getJSONObject(i)); val nb = billMap[a.billId] ?: continue
                db.billAttachmentDao().insert(a.copy(id = 0, billId = nb))
            }
        }
        // Payment attachments (voice / photo / file)
        root.optJSONArray("expenseAttachments")?.let {
            for (i in 0 until it.length()) {
                val a = readExpenseAtt(context, it.getJSONObject(i)); val ne = expMap[a.expenseId] ?: continue
                db.expenseAttachmentDao().insert(a.copy(id = 0, expenseId = ne))
            }
        }
        // Customer documents follow whichever customer row they ended up attached to.
        root.optJSONArray("customerAttachments")?.let {
            for (i in 0 until it.length()) {
                val a2 = readCustAtt(context, it.getJSONObject(i))
                val nc = custMap[a2.customerId] ?: continue
                db.customerAttachmentDao().insert(a2.copy(id = 0, customerId = nc))
            }
        }
        // Purchase quotations, with their lines following the header's new id.
        val pQuoteMap = HashMap<Long, Long>()
        root.optJSONArray("purchaseQuotes")?.let {
            for (i in 0 until it.length()) {
                val q = readPQuote(it.getJSONObject(i))
                pQuoteMap[q.id] = db.purchaseQuoteDao().insertHeader(q.copy(id = 0))
                    .also { nid -> log.add("purchaseQuotes", nid) }
            }
        }
        root.optJSONArray("purchaseQuoteItems")?.let {
            for (i in 0 until it.length()) {
                val l = readPQuoteItem(it.getJSONObject(i))
                val nq = pQuoteMap[l.quoteId] ?: continue
                db.purchaseQuoteDao().insertLines(listOf(l.copy(id = 0, quoteId = nq)))
            }
        }
        // Saved calculator tapes — standalone, so they merge by simply being added.
        root.optJSONArray("savedCalcs")?.let {
            for (i in 0 until it.length()) {
                db.savedCalcDao().insert(readSavedCalc(it.getJSONObject(i)).copy(id = 0))
                    .also { nid -> log.add("savedCalcs", nid) }
            }
        }
        // Customer orders, lines and attachments following the header's new id.
        val orderMap = HashMap<Long, Long>()
        root.optJSONArray("custOrders")?.let {
            for (i in 0 until it.length()) {
                val o = readOrder(it.getJSONObject(i))
                orderMap[o.id] = db.custOrderDao().insertHeader(o.copy(id = 0)).also { nid -> log.add("custOrders", nid) }
            }
        }
        root.optJSONArray("custOrderItems")?.let {
            for (i in 0 until it.length()) {
                val l = readOrderItem(it.getJSONObject(i)); val no = orderMap[l.orderId] ?: continue
                db.custOrderDao().insertLines(listOf(l.copy(id = 0, orderId = no)))
            }
        }
        root.optJSONArray("custOrderAttachments")?.let {
            for (i in 0 until it.length()) {
                val a = readOrderAtt(context, it.getJSONObject(i)); val no = orderMap[a.orderId] ?: continue
                db.custOrderDao().insertAttachment(a.copy(id = 0, orderId = no))
            }
        }
        // Material receipts + lines
        val matRecMap = HashMap<Long, Long>()
        root.optJSONArray("materialReceipts")?.let {
            for (i in 0 until it.length()) {
                val m = readMatRec(it.getJSONObject(i))
                matRecMap[m.id] = db.materialReceiptDao()
                    .insertHeader(m.copy(id = 0, supplierId = suppMap[m.supplierId] ?: m.supplierId))
                    .also { nid -> log.add("materialReceipts", nid) }
            }
        }
        root.optJSONArray("materialReceiptItems")?.let {
            for (i in 0 until it.length()) {
                val l = readMatRecItem(it.getJSONObject(i)); val nm = matRecMap[l.receiptId] ?: continue
                db.materialReceiptDao().insertLines(listOf(l.copy(id = 0, receiptId = nm, itemId = itemMap[l.itemId] ?: l.itemId)))
            }
        }

        return log.build()
    }

    // ---- serialisers ----
    private fun custJson(c: Customer) = JSONObject().put("id", c.id).put("name", c.name)
        .put("phone", c.phone).put("address", c.address).put("gstin", c.gstin).put("isDefault", c.isDefault).put("customerType", c.customerType)

    private fun itemJson(i: Item) = JSONObject().put("id", i.id).put("name", i.name)
        .put("price", i.price).put("taxPercent", i.taxPercent).put("barcode", i.barcode).put("hsn", i.hsn)
        .put("category", i.category).put("openingStock", i.openingStock).put("unit", i.unit)
        .put("secondaryUnit", i.secondaryUnit).put("conversionFactor", i.conversionFactor)
        .put("storeLocation", i.storeLocation).put("chemicalContent", i.chemicalContent)

    private fun billJson(b: Bill) = JSONObject().put("id", b.id).put("billNo", b.billNo)
        .put("dateMillis", b.dateMillis).put("customerId", b.customerId).put("customerName", b.customerName)
        .put("paymentMethod", b.paymentMethod).put("subTotal", b.subTotal).put("taxTotal", b.taxTotal)
        .put("additionalCharge", b.additionalCharge).put("discount", b.discount)
        .put("grandTotal", b.grandTotal).put("paidAmount", b.paidAmount)
        .put("customerGstin", b.customerGstin).put("source", b.source).put("remarks", b.remarks)

    private fun lineJson(l: BillItem) = JSONObject().put("id", l.id).put("billId", l.billId)
        .put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("batchNo", l.batchNo)
        .put("unit", l.unit).put("primaryQty", l.primaryQty)

    private fun receiptJson(r: Receipt) = JSONObject().put("id", r.id).put("receiptNo", r.receiptNo)
        .put("billId", r.billId).put("billNo", r.billNo).put("customerName", r.customerName)
        .put("dateMillis", r.dateMillis).put("amount", r.amount).put("paymentMode", r.paymentMode)
        .put("payFrom", r.payFrom).put("source", r.source)

    private fun expenseJson(e: Expense) = JSONObject().put("id", e.id).put("voucherNo", e.voucherNo)
        .put("dateMillis", e.dateMillis).put("description", e.description).put("amount", e.amount)
        .put("paymentMode", e.paymentMode).put("purchaseId", e.purchaseId).put("purchaseNo", e.purchaseNo)
        .put("payTo", e.payTo).put("source", e.source)

    private fun userJson(u: User) = JSONObject().put("id", u.id).put("username", u.username)
        .put("passwordHash", u.passwordHash).put("role", u.role.name)
        .put("canCreateInvoice", u.canCreateInvoice).put("canEditInvoice", u.canEditInvoice)
        .put("canDeleteInvoice", u.canDeleteInvoice).put("canViewInvoice", u.canViewInvoice)
        .put("canCreateReceipt", u.canCreateReceipt).put("canEditReceipt", u.canEditReceipt)
        .put("canDeleteReceipt", u.canDeleteReceipt).put("canViewReceipt", u.canViewReceipt)
        .put("canCreatePayment", u.canCreatePayment).put("canEditPayment", u.canEditPayment)
        .put("canDeletePayment", u.canDeletePayment).put("canViewPayment", u.canViewPayment)
        .put("canViewCashbook", u.canViewCashbook)
        .put("canExport", u.canExport).put("canImport", u.canImport)
        .put("canManageUsers", u.canManageUsers).put("active", u.active)

    private fun entryJson(e: DiaryEntry) = JSONObject().put("id", e.id).put("title", e.title)
        .put("remarks", e.remarks).put("createdAt", e.createdAt).put("updatedAt", e.updatedAt)
        .put("typeId", e.typeId)
        .put("reminderEnabled", e.reminderEnabled).put("reminderAt", e.reminderAt)
        .put("reminderDaily", e.reminderDaily)
        .put("titleSize", e.titleSize).put("titleColor", e.titleColor)
        .put("titleBold", e.titleBold).put("titleItalic", e.titleItalic)
        .put("bodySize", e.bodySize).put("bodyColor", e.bodyColor)
        .put("bodyBold", e.bodyBold).put("bodyItalic", e.bodyItalic)

    private fun supplierJson(s: Supplier) = JSONObject().put("id", s.id).put("name", s.name)
        .put("phone", s.phone).put("address", s.address).put("gstin", s.gstin).put("isDefault", s.isDefault)

    private fun purchaseJson(p: Purchase) = JSONObject().put("id", p.id).put("purchaseNo", p.purchaseNo)
        .put("dateMillis", p.dateMillis).put("supplierId", p.supplierId).put("supplierName", p.supplierName)
        .put("paymentMethod", p.paymentMethod).put("subTotal", p.subTotal).put("taxTotal", p.taxTotal)
        .put("additionalCharge", p.additionalCharge).put("discount", p.discount).put("grandTotal", p.grandTotal)
        .put("paidAmount", p.paidAmount).put("supplierGstin", p.supplierGstin).put("source", p.source)

    private fun pLineJson(l: PurchaseItem) = JSONObject().put("id", l.id).put("purchaseId", l.purchaseId)
        .put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("batchNo", l.batchNo)
        .put("unit", l.unit).put("primaryQty", l.primaryQty)

    private fun groupJson(g: AccountGroup) = JSONObject().put("id", g.id).put("name", g.name)
        .put("nature", g.nature.name).put("isSystem", g.isSystem)

    private fun headJson(h: AccountHead) = JSONObject().put("id", h.id).put("name", h.name)
        .put("groupId", h.groupId).put("openingBalance", h.openingBalance)
        .put("openingIsDebit", h.openingIsDebit).put("isSystem", h.isSystem)

    private fun jEntryJson(e: JournalEntry) = JSONObject().put("id", e.id).put("voucherNo", e.voucherNo)
        .put("dateMillis", e.dateMillis).put("narration", e.narration)
        .put("cashMode", e.cashMode).put("cashIsIn", e.cashIsIn).put("cashAmount", e.cashAmount)
        .put("source", e.source)

    private fun jLineJson(l: JournalLine) = JSONObject().put("id", l.id).put("entryId", l.entryId)
        .put("headId", l.headId).put("headName", l.headName).put("amount", l.amount).put("isDebit", l.isDebit)

    private fun batchJson(b: ItemBatch) = JSONObject().put("id", b.id).put("itemId", b.itemId)
        .put("batchNo", b.batchNo).put("expiryMillis", b.expiryMillis).put("quantity", b.quantity)

    private fun readBatch(o: JSONObject) = ItemBatch(
        id = o.optLong("id"), itemId = o.optLong("itemId"), batchNo = o.optString("batchNo"),
        expiryMillis = o.optLong("expiryMillis"), quantity = o.optDouble("quantity", 0.0)
    )

    private fun sizeJson(s: ItemSize) = JSONObject().put("id", s.id).put("itemId", s.itemId)
        .put("name", s.name).put("price", s.price)

    private fun custAttJson(a: CustomerAttachment) = JSONObject().put("id", a.id)
        .put("customerId", a.customerId).put("file", File(a.path).name)
        .put("name", a.name).put("mime", a.mime)

    private fun readCustAtt(context: Context, o: JSONObject) = CustomerAttachment(
        id = o.optLong("id"), customerId = o.optLong("customerId"),
        path = File(CustomerAttachmentStore.dir(context), o.optString("file")).absolutePath,
        name = o.optString("name"), mime = o.optString("mime")
    )

    private fun pQuoteJson(q: PurchaseQuote) = JSONObject().put("id", q.id).put("quoteNo", q.quoteNo)
        .put("dateMillis", q.dateMillis).put("supplierId", q.supplierId).put("supplierName", q.supplierName)
        .put("subTotal", q.subTotal).put("taxTotal", q.taxTotal).put("additionalCharge", q.additionalCharge)
        .put("discount", q.discount).put("grandTotal", q.grandTotal).put("remarks", q.remarks)

    private fun readPQuote(o: JSONObject) = PurchaseQuote(
        id = o.optLong("id"), quoteNo = o.optString("quoteNo"), dateMillis = o.optLong("dateMillis"),
        supplierId = o.optLong("supplierId"), supplierName = o.optString("supplierName"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks")
    )

    private fun pQuoteItemJson(l: PurchaseQuoteItem) = JSONObject().put("id", l.id)
        .put("quoteId", l.quoteId).put("itemId", l.itemId).put("name", l.name).put("qty", l.qty)
        .put("price", l.price).put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal)
        .put("unit", l.unit)

    private fun readPQuoteItem(o: JSONObject) = PurchaseQuoteItem(
        id = o.optLong("id"), quoteId = o.optLong("quoteId"), itemId = o.optLong("itemId"),
        name = o.optString("name"), qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        unit = o.optString("unit")
    )

    private fun savedCalcJson(c: SavedCalc) = JSONObject().put("id", c.id)
        .put("dateMillis", c.dateMillis).put("amounts", c.amounts)
        .put("total", c.total).put("title", c.title)
        .put("customerId", c.customerId).put("customerName", c.customerName)
        .put("narration", c.narration)

    private fun readSavedCalc(o: JSONObject) = SavedCalc(
        id = o.optLong("id"), dateMillis = o.optLong("dateMillis"),
        amounts = o.optString("amounts"), total = o.optDouble("total", 0.0),
        title = o.optString("title"),
        customerId = o.optLong("customerId"),
        customerName = o.optString("customerName", SavedCalc.DEFAULT_CUSTOMER),
        narration = o.optString("narration")
    )

    private fun orderJson(o: CustOrder) = JSONObject().put("id", o.id).put("orderNo", o.orderNo)
        .put("dateMillis", o.dateMillis).put("customerId", o.customerId).put("customerName", o.customerName)
        .put("remark", o.remark).put("latitude", o.latitude).put("longitude", o.longitude).put("grandTotal", o.grandTotal)
    private fun readOrder(o: JSONObject) = CustOrder(
        id = o.optLong("id"), orderNo = o.optString("orderNo"), dateMillis = o.optLong("dateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"),
        remark = o.optString("remark"), latitude = o.optDouble("latitude", 0.0), longitude = o.optDouble("longitude", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0)
    )
    private fun orderItemJson(l: CustOrderItem) = JSONObject().put("id", l.id).put("orderId", l.orderId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("lineTotal", l.lineTotal).put("unit", l.unit)
    private fun readOrderItem(o: JSONObject) = CustOrderItem(
        id = o.optLong("id"), orderId = o.optLong("orderId"), itemId = o.optLong("itemId"),
        name = o.optString("name"), qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        lineTotal = o.optDouble("lineTotal", 0.0), unit = o.optString("unit")
    )
    private fun orderAttJson(a: CustOrderAttachment) = JSONObject().put("id", a.id).put("orderId", a.orderId)
        .put("file", File(a.path).name).put("name", a.name).put("mime", a.mime)
    private fun readOrderAtt(context: Context, o: JSONObject) = CustOrderAttachment(
        id = o.optLong("id"), orderId = o.optLong("orderId"),
        path = File(OrderAttachmentStore.dir(context), o.optString("file")).absolutePath,
        name = o.optString("name"), mime = o.optString("mime")
    )

    private fun quotationJson(q: Quotation) = JSONObject().put("id", q.id).put("quotationNo", q.quotationNo)
        .put("dateMillis", q.dateMillis).put("customerId", q.customerId).put("customerName", q.customerName)
        .put("subTotal", q.subTotal).put("taxTotal", q.taxTotal).put("additionalCharge", q.additionalCharge)
        .put("discount", q.discount).put("grandTotal", q.grandTotal).put("remarks", q.remarks)
        .put("terms", q.terms)

    private fun readQuotation(o: JSONObject) = Quotation(
        id = o.optLong("id"), quotationNo = o.optString("quotationNo"), dateMillis = o.optLong("dateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks"),
        terms = o.optString("terms")
    )

    private fun estimateJson(e: Estimate) = JSONObject().put("id", e.id).put("estimateNo", e.estimateNo)
        .put("dateMillis", e.dateMillis).put("customerId", e.customerId).put("customerName", e.customerName)
        .put("paymentMethod", e.paymentMethod)
        .put("subTotal", e.subTotal).put("taxTotal", e.taxTotal).put("additionalCharge", e.additionalCharge)
        .put("discount", e.discount).put("grandTotal", e.grandTotal)
        .put("customerGstin", e.customerGstin).put("remarks", e.remarks)

    private fun readEstimate(o: JSONObject) = Estimate(
        id = o.optLong("id"), estimateNo = o.optString("estimateNo"), dateMillis = o.optLong("dateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"),
        paymentMethod = o.optString("paymentMethod"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0),
        customerGstin = o.optString("customerGstin"), remarks = o.optString("remarks")
    )

    private fun eItemJson(l: EstimateItem) = JSONObject().put("id", l.id).put("estimateId", l.estimateId)
        .put("name", l.name).put("qty", l.qty).put("price", l.price).put("taxPercent", l.taxPercent)
        .put("lineTotal", l.lineTotal).put("unit", l.unit)

    private fun readEItem(o: JSONObject) = EstimateItem(
        id = o.optLong("id"), estimateId = o.optLong("estimateId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        unit = o.optString("unit")
    )

    private fun qItemJson(l: QuotationItem) = JSONObject().put("id", l.id).put("quotationId", l.quotationId)
        .put("name", l.name).put("qty", l.qty).put("price", l.price).put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("unit", l.unit).put("note", l.note)

    private fun readQItem(o: JSONObject) = QuotationItem(
        id = o.optLong("id"), quotationId = o.optLong("quotationId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        unit = o.optString("unit"), note = o.optString("note")
    )

    private fun salesReturnJson(r: SalesReturn) = JSONObject().put("id", r.id).put("returnNo", r.returnNo)
        .put("dateMillis", r.dateMillis).put("customerId", r.customerId).put("customerName", r.customerName)
        .put("billNo", r.billNo).put("subTotal", r.subTotal).put("taxTotal", r.taxTotal)
        .put("additionalCharge", r.additionalCharge).put("discount", r.discount).put("grandTotal", r.grandTotal)
        .put("remarks", r.remarks)

    private fun readSalesReturn(o: JSONObject) = SalesReturn(
        id = o.optLong("id"), returnNo = o.optString("returnNo"), dateMillis = o.optLong("dateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"), billNo = o.optString("billNo"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks")
    )

    private fun srItemJson(l: SalesReturnItem) = JSONObject().put("id", l.id).put("returnId", l.returnId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("batchNo", l.batchNo)
        .put("unit", l.unit).put("primaryQty", l.primaryQty)

    private fun readSRItem(o: JSONObject) = SalesReturnItem(
        id = o.optLong("id"), returnId = o.optLong("returnId"), itemId = o.optLong("itemId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0), batchNo = o.optString("batchNo"),
        unit = o.optString("unit"), primaryQty = o.optDouble("primaryQty", 0.0)
    )

    private fun purchaseReturnJson(r: PurchaseReturn) = JSONObject().put("id", r.id).put("returnNo", r.returnNo)
        .put("dateMillis", r.dateMillis).put("supplierId", r.supplierId).put("supplierName", r.supplierName)
        .put("billNo", r.billNo).put("subTotal", r.subTotal).put("taxTotal", r.taxTotal)
        .put("additionalCharge", r.additionalCharge).put("discount", r.discount).put("grandTotal", r.grandTotal)
        .put("remarks", r.remarks)

    private fun readPurchaseReturn(o: JSONObject) = PurchaseReturn(
        id = o.optLong("id"), returnNo = o.optString("returnNo"), dateMillis = o.optLong("dateMillis"),
        supplierId = o.optLong("supplierId"), supplierName = o.optString("supplierName"), billNo = o.optString("billNo"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks")
    )

    private fun prItemJson(l: PurchaseReturnItem) = JSONObject().put("id", l.id).put("returnId", l.returnId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("batchNo", l.batchNo)
        .put("unit", l.unit).put("primaryQty", l.primaryQty)

    private fun readPRItem(o: JSONObject) = PurchaseReturnItem(
        id = o.optLong("id"), returnId = o.optLong("returnId"), itemId = o.optLong("itemId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0), batchNo = o.optString("batchNo"),
        unit = o.optString("unit"), primaryQty = o.optDouble("primaryQty", 0.0)
    )

    private fun lpoJson(r: PurchaseQuotation) = JSONObject().put("id", r.id).put("lpoNo", r.lpoNo)
        .put("dateMillis", r.dateMillis).put("supplierId", r.supplierId).put("supplierName", r.supplierName)
        .put("subTotal", r.subTotal).put("taxTotal", r.taxTotal)
        .put("additionalCharge", r.additionalCharge).put("discount", r.discount).put("grandTotal", r.grandTotal)
        .put("remarks", r.remarks)

    private fun readLpo(o: JSONObject) = PurchaseQuotation(
        id = o.optLong("id"), lpoNo = o.optString("lpoNo"), dateMillis = o.optLong("dateMillis"),
        supplierId = o.optLong("supplierId"), supplierName = o.optString("supplierName"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks")
    )

    private fun lpoItemJson(l: PurchaseQuotationItem) = JSONObject().put("id", l.id).put("lpoId", l.lpoId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("unit", l.unit)

    private fun readLpoItem(o: JSONObject) = PurchaseQuotationItem(
        id = o.optLong("id"), lpoId = o.optLong("lpoId"), itemId = o.optLong("itemId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        unit = o.optString("unit")
    )

    private fun hireJson(h: HireInvoice) = JSONObject().put("id", h.id).put("hireNo", h.hireNo)
        .put("dateMillis", h.dateMillis).put("startDateMillis", h.startDateMillis).put("endDateMillis", h.endDateMillis)
        .put("customerId", h.customerId).put("customerName", h.customerName)
        .put("subTotal", h.subTotal).put("taxTotal", h.taxTotal)
        .put("additionalCharge", h.additionalCharge).put("discount", h.discount).put("grandTotal", h.grandTotal)
        .put("remarks", h.remarks)

    private fun readHire(o: JSONObject) = HireInvoice(
        id = o.optLong("id"), hireNo = o.optString("hireNo"), dateMillis = o.optLong("dateMillis"),
        startDateMillis = o.optLong("startDateMillis"), endDateMillis = o.optLong("endDateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"),
        subTotal = o.optDouble("subTotal", 0.0), taxTotal = o.optDouble("taxTotal", 0.0),
        additionalCharge = o.optDouble("additionalCharge", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks")
    )

    private fun hireItemJson(l: HireInvoiceItem) = JSONObject().put("id", l.id).put("hireId", l.hireId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("unit", l.unit)

    private fun readHireItem(o: JSONObject) = HireInvoiceItem(
        id = o.optLong("id"), hireId = o.optLong("hireId"), itemId = o.optLong("itemId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0), unit = o.optString("unit")
    )

    private fun hireRetJson(r: HireReturn) = JSONObject().put("id", r.id).put("returnNo", r.returnNo)
        .put("dateMillis", r.dateMillis).put("hireId", r.hireId).put("hireNo", r.hireNo)
        .put("customerId", r.customerId).put("customerName", r.customerName).put("remarks", r.remarks)

    private fun readHireRet(o: JSONObject) = HireReturn(
        id = o.optLong("id"), returnNo = o.optString("returnNo"), dateMillis = o.optLong("dateMillis"),
        hireId = o.optLong("hireId"), hireNo = o.optString("hireNo"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"), remarks = o.optString("remarks")
    )

    private fun hireRetItemJson(l: HireReturnItem) = JSONObject().put("id", l.id).put("returnId", l.returnId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("unit", l.unit)

    private fun readHireRetItem(o: JSONObject) = HireReturnItem(
        id = o.optLong("id"), returnId = o.optLong("returnId"), itemId = o.optLong("itemId"),
        name = o.optString("name"), qty = o.optDouble("qty", 0.0), unit = o.optString("unit")
    )

    private fun labTestJson(t: LabTest) = JSONObject().put("id", t.id).put("name", t.name)
        .put("price", t.price).put("sampleType", t.sampleType).put("category", t.category)
    private fun readLabTest(o: JSONObject) = LabTest(
        id = o.optLong("id"), name = o.optString("name"), price = o.optDouble("price", 0.0),
        sampleType = o.optString("sampleType"), category = o.optString("category")
    )

    private fun labEvalJson(e: LabEvaluation) = JSONObject().put("id", e.id).put("testId", e.testId)
        .put("name", e.name).put("unit", e.unit).put("normalValue", e.normalValue)
        .put("groupName", e.groupName).put("sortOrder", e.sortOrder).put("isHeading", e.isHeading).put("isPageBreak", e.isPageBreak)
    private fun readLabEval(o: JSONObject) = LabEvaluation(
        id = o.optLong("id"), testId = o.optLong("testId"), name = o.optString("name"),
        unit = o.optString("unit"), normalValue = o.optString("normalValue"),
        groupName = o.optString("groupName"), sortOrder = o.optInt("sortOrder", 0),
        isHeading = o.optBoolean("isHeading", false), isPageBreak = o.optBoolean("isPageBreak", false)
    )

    private fun labEvalMasterJson(e: LabEvalMaster) = JSONObject().put("id", e.id).put("name", e.name)
        .put("unit", e.unit).put("normalValue", e.normalValue).put("groupName", e.groupName)
    private fun readLabEvalMaster(o: JSONObject) = LabEvalMaster(
        id = o.optLong("id"), name = o.optString("name"), unit = o.optString("unit"),
        normalValue = o.optString("normalValue"), groupName = o.optString("groupName")
    )

    private fun patientJson(p: Patient) = JSONObject().put("id", p.id).put("name", p.name)
        .put("age", p.age).put("gender", p.gender).put("phone", p.phone)
        .put("address", p.address).put("referredBy", p.referredBy)
    private fun readPatient(o: JSONObject) = Patient(
        id = o.optLong("id"), name = o.optString("name"), age = o.optString("age"),
        gender = o.optString("gender"), phone = o.optString("phone"),
        address = o.optString("address"), referredBy = o.optString("referredBy")
    )

    private fun labBillJson(b: LabBill) = JSONObject().put("id", b.id).put("billNo", b.billNo)
        .put("dateMillis", b.dateMillis).put("patientId", b.patientId).put("patientName", b.patientName)
        .put("patientPhone", b.patientPhone)
        .put("age", b.age).put("gender", b.gender).put("referredBy", b.referredBy)
        .put("subTotal", b.subTotal).put("discount", b.discount).put("grandTotal", b.grandTotal)
        .put("remarks", b.remarks).put("resultEntered", b.resultEntered).put("resultDateMillis", b.resultDateMillis)
        .put("paymentMethod", b.paymentMethod).put("paidAmount", b.paidAmount)
    private fun readLabBill(o: JSONObject) = LabBill(
        id = o.optLong("id"), billNo = o.optString("billNo"), dateMillis = o.optLong("dateMillis"),
        patientId = o.optLong("patientId"), patientName = o.optString("patientName"),
        patientPhone = o.optString("patientPhone"),
        age = o.optString("age"), gender = o.optString("gender"), referredBy = o.optString("referredBy"),
        subTotal = o.optDouble("subTotal", 0.0), discount = o.optDouble("discount", 0.0),
        grandTotal = o.optDouble("grandTotal", 0.0), remarks = o.optString("remarks"),
        resultEntered = o.optBoolean("resultEntered", false), resultDateMillis = o.optLong("resultDateMillis"),
        paymentMethod = o.optString("paymentMethod", "Cash"), paidAmount = o.optDouble("paidAmount", 0.0)
    )

    private fun labBillTestJson(t: LabBillTest) = JSONObject().put("id", t.id).put("billId", t.billId)
        .put("testId", t.testId).put("testName", t.testName).put("price", t.price)
    private fun readLabBillTest(o: JSONObject) = LabBillTest(
        id = o.optLong("id"), billId = o.optLong("billId"), testId = o.optLong("testId"),
        testName = o.optString("testName"), price = o.optDouble("price", 0.0)
    )

    private fun labResultJson(r: LabResultValue) = JSONObject().put("id", r.id).put("billId", r.billId)
        .put("testId", r.testId).put("testName", r.testName).put("evaluationId", r.evaluationId)
        .put("evaluationName", r.evaluationName).put("groupName", r.groupName).put("unit", r.unit)
        .put("normalValue", r.normalValue).put("result", r.result).put("sortOrder", r.sortOrder).put("isHeading", r.isHeading).put("isPageBreak", r.isPageBreak)
    private fun readLabResult(o: JSONObject) = LabResultValue(
        id = o.optLong("id"), billId = o.optLong("billId"), testId = o.optLong("testId"),
        testName = o.optString("testName"), evaluationId = o.optLong("evaluationId"),
        evaluationName = o.optString("evaluationName"), groupName = o.optString("groupName"),
        unit = o.optString("unit"), normalValue = o.optString("normalValue"),
        result = o.optString("result"), sortOrder = o.optInt("sortOrder", 0),
        isHeading = o.optBoolean("isHeading", false), isPageBreak = o.optBoolean("isPageBreak", false)
    )

    private fun labReceiptJson(r: LabReceipt) = JSONObject().put("id", r.id).put("labBillId", r.labBillId)
        .put("billNo", r.billNo).put("patientName", r.patientName).put("dateMillis", r.dateMillis)
        .put("amount", r.amount).put("mode", r.mode)
    private fun readLabReceipt(o: JSONObject) = LabReceipt(
        id = o.optLong("id"), labBillId = o.optLong("labBillId"), billNo = o.optString("billNo"),
        patientName = o.optString("patientName"), dateMillis = o.optLong("dateMillis"),
        amount = o.optDouble("amount", 0.0), mode = o.optString("mode", "Cash")
    )

    private fun matOutJson(m: MaterialOut) = JSONObject().put("id", m.id).put("voucherNo", m.voucherNo)
        .put("dateMillis", m.dateMillis).put("resultRef", m.resultRef).put("resultTests", m.resultTests).put("remarks", m.remarks)
    private fun readMatOut(o: JSONObject) = MaterialOut(
        id = o.optLong("id"), voucherNo = o.optString("voucherNo"), dateMillis = o.optLong("dateMillis"),
        resultRef = o.optString("resultRef"), resultTests = o.optString("resultTests"), remarks = o.optString("remarks")
    )
    private fun matOutItemJson(l: MaterialOutItem) = JSONObject().put("id", l.id).put("outId", l.outId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("unit", l.unit)
    private fun readMatOutItem(o: JSONObject) = MaterialOutItem(
        id = o.optLong("id"), outId = o.optLong("outId"), itemId = o.optLong("itemId"),
        name = o.optString("name"), qty = o.optDouble("qty", 0.0), unit = o.optString("unit")
    )

    private fun readSize(o: JSONObject) = ItemSize(
        id = o.optLong("id"), itemId = o.optLong("itemId"), name = o.optString("name"), price = o.optDouble("price", 0.0)
    )

    private fun itemAttJson(a: ItemAttachment) = JSONObject().put("id", a.id).put("itemId", a.itemId)
        .put("file", File(a.path).name).put("name", a.name).put("mime", a.mime).put("kind", a.kind)

    private fun matRecJson(m: MaterialReceipt) = JSONObject().put("id", m.id).put("receiptNo", m.receiptNo)
        .put("dateMillis", m.dateMillis).put("supplierId", m.supplierId).put("supplierName", m.supplierName)
        .put("lpoId", m.lpoId).put("lpoNo", m.lpoNo).put("remarks", m.remarks)

    private fun readMatRec(o: JSONObject) = MaterialReceipt(
        id = o.optLong("id"), receiptNo = o.optString("receiptNo"), dateMillis = o.optLong("dateMillis"),
        supplierId = o.optLong("supplierId"), supplierName = o.optString("supplierName"),
        lpoId = o.optLong("lpoId"), lpoNo = o.optString("lpoNo"), remarks = o.optString("remarks")
    )

    private fun matRecItemJson(l: MaterialReceiptItem) = JSONObject().put("id", l.id).put("receiptId", l.receiptId)
        .put("itemId", l.itemId).put("name", l.name).put("qty", l.qty).put("price", l.price)
        .put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal).put("batchNo", l.batchNo).put("unit", l.unit)

    private fun readMatRecItem(o: JSONObject) = MaterialReceiptItem(
        id = o.optLong("id"), receiptId = o.optLong("receiptId"), itemId = o.optLong("itemId"),
        name = o.optString("name"), qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        batchNo = o.optString("batchNo"), unit = o.optString("unit")
    )

    private fun expenseAttJson(a: ExpenseAttachment) = JSONObject().put("id", a.id).put("expenseId", a.expenseId)
        .put("file", File(a.path).name).put("name", a.name).put("mime", a.mime)

    private fun readExpenseAtt(context: Context, o: JSONObject) = ExpenseAttachment(
        id = o.optLong("id"), expenseId = o.optLong("expenseId"),
        path = File(com.billing.pos.expenses.ExpenseAttachmentStore.dir(context), o.optString("file")).absolutePath,
        name = o.optString("name"), mime = o.optString("mime")
    )

    private fun billAttJson(a: BillAttachment) = JSONObject().put("id", a.id).put("billId", a.billId)
        .put("file", File(a.path).name).put("name", a.name).put("mime", a.mime)

    private fun attJson(a: DiaryAttachment) = JSONObject().put("id", a.id).put("entryId", a.entryId)
        .put("file", if (a.type == AttachmentType.LOCATION) "" else File(a.path).name)
        .put("locUrl", if (a.type == AttachmentType.LOCATION) a.path else "")
        .put("name", a.name).put("mime", a.mime).put("type", a.type.name)

    private fun blockJson(b: DiaryBlock) = JSONObject().put("id", b.id).put("entryId", b.entryId)
        .put("position", b.position).put("type", b.type.name).put("text", b.text)
        .put("file", if (b.path.isBlank()) "" else File(b.path).name)
        .put("name", b.name).put("mime", b.mime).put("durationMs", b.durationMs)

    // ---- deserialisers ----
    private fun readCust(o: JSONObject) = Customer(
        id = o.optLong("id"), name = o.optString("name"), phone = o.optString("phone"),
        address = o.optString("address"), gstin = o.optString("gstin"), isDefault = o.optBoolean("isDefault", false),
        customerType = o.optString("customerType", "General")
    )

    private fun readItem(o: JSONObject) = Item(
        id = o.optLong("id"), name = o.optString("name"),
        price = o.optDouble("price", 0.0), taxPercent = o.optDouble("taxPercent", 0.0),
        barcode = o.optString("barcode"), hsn = o.optString("hsn"),
        category = o.optString("category"), openingStock = o.optDouble("openingStock", 0.0),
        unit = o.optString("unit", "PCS"),
        secondaryUnit = o.optString("secondaryUnit", "PCS"),
        conversionFactor = o.optDouble("conversionFactor", 1.0),
        storeLocation = o.optString("storeLocation"),
        chemicalContent = o.optString("chemicalContent")
    )

    private fun readBill(o: JSONObject) = Bill(
        id = o.optLong("id"), billNo = o.optString("billNo"), dateMillis = o.optLong("dateMillis"),
        customerId = o.optLong("customerId"), customerName = o.optString("customerName"),
        paymentMethod = o.optString("paymentMethod"), subTotal = o.optDouble("subTotal", 0.0),
        taxTotal = o.optDouble("taxTotal", 0.0), additionalCharge = o.optDouble("additionalCharge", 0.0),
        discount = o.optDouble("discount", 0.0), grandTotal = o.optDouble("grandTotal", 0.0),
        paidAmount = o.optDouble("paidAmount", 0.0), customerGstin = o.optString("customerGstin"),
        source = o.optString("source"), remarks = o.optString("remarks")
    )

    private fun readLine(o: JSONObject) = BillItem(
        id = o.optLong("id"), billId = o.optLong("billId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        batchNo = o.optString("batchNo"), unit = o.optString("unit"),
        primaryQty = o.optDouble("primaryQty", 0.0)
    )

    private fun readReceipt(o: JSONObject) = Receipt(
        id = o.optLong("id"), receiptNo = o.optString("receiptNo"), billId = o.optLong("billId"),
        billNo = o.optString("billNo"), customerName = o.optString("customerName"),
        dateMillis = o.optLong("dateMillis"), amount = o.optDouble("amount", 0.0),
        paymentMode = o.optString("paymentMode"), payFrom = o.optString("payFrom"), source = o.optString("source")
    )

    private fun readExpense(o: JSONObject) = Expense(
        id = o.optLong("id"), voucherNo = o.optString("voucherNo"), dateMillis = o.optLong("dateMillis"),
        description = o.optString("description"), amount = o.optDouble("amount", 0.0),
        paymentMode = o.optString("paymentMode"), purchaseId = o.optLong("purchaseId"),
        purchaseNo = o.optString("purchaseNo"), payTo = o.optString("payTo"), source = o.optString("source")
    )

    private fun readUser(o: JSONObject) = User(
        id = o.optLong("id"), username = o.optString("username"), passwordHash = o.optString("passwordHash"),
        role = runCatching { Role.valueOf(o.optString("role", "SALESMAN")) }.getOrDefault(Role.SALESMAN),
        canCreateInvoice = o.optBoolean("canCreateInvoice", true), canEditInvoice = o.optBoolean("canEditInvoice", false),
        canDeleteInvoice = o.optBoolean("canDeleteInvoice", false), canViewInvoice = o.optBoolean("canViewInvoice", true),
        canCreateReceipt = o.optBoolean("canCreateReceipt", false), canEditReceipt = o.optBoolean("canEditReceipt", false),
        canDeleteReceipt = o.optBoolean("canDeleteReceipt", false), canViewReceipt = o.optBoolean("canViewReceipt", false),
        canCreatePayment = o.optBoolean("canCreatePayment", false), canEditPayment = o.optBoolean("canEditPayment", false),
        canDeletePayment = o.optBoolean("canDeletePayment", false), canViewPayment = o.optBoolean("canViewPayment", false),
        canViewCashbook = o.optBoolean("canViewCashbook", false),
        canExport = o.optBoolean("canExport", true), canImport = o.optBoolean("canImport", false),
        canManageUsers = o.optBoolean("canManageUsers", false), active = o.optBoolean("active", true)
    )

    private fun readEntry(o: JSONObject) = DiaryEntry(
        id = o.optLong("id"), title = o.optString("title"), remarks = o.optString("remarks"),
        createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
        typeId = o.optLong("typeId", 0L),
        reminderEnabled = o.optBoolean("reminderEnabled", false), reminderAt = o.optLong("reminderAt"),
        reminderDaily = o.optBoolean("reminderDaily", false),
        titleSize = o.optInt("titleSize", 20), titleColor = o.optInt("titleColor", 0),
        titleBold = o.optBoolean("titleBold", true), titleItalic = o.optBoolean("titleItalic", false),
        bodySize = o.optInt("bodySize", 15), bodyColor = o.optInt("bodyColor", 0),
        bodyBold = o.optBoolean("bodyBold", false), bodyItalic = o.optBoolean("bodyItalic", false)
    )

    private fun readSupplier(o: JSONObject) = Supplier(
        id = o.optLong("id"), name = o.optString("name"), phone = o.optString("phone"),
        address = o.optString("address"), gstin = o.optString("gstin"), isDefault = o.optBoolean("isDefault", false)
    )

    private fun readPurchase(o: JSONObject) = Purchase(
        id = o.optLong("id"), purchaseNo = o.optString("purchaseNo"), dateMillis = o.optLong("dateMillis"),
        supplierId = o.optLong("supplierId"), supplierName = o.optString("supplierName"),
        paymentMethod = o.optString("paymentMethod"), subTotal = o.optDouble("subTotal", 0.0),
        taxTotal = o.optDouble("taxTotal", 0.0), additionalCharge = o.optDouble("additionalCharge", 0.0),
        discount = o.optDouble("discount", 0.0), grandTotal = o.optDouble("grandTotal", 0.0),
        paidAmount = o.optDouble("paidAmount", 0.0), supplierGstin = o.optString("supplierGstin"),
        source = o.optString("source")
    )

    private fun readPLine(o: JSONObject) = PurchaseItem(
        id = o.optLong("id"), purchaseId = o.optLong("purchaseId"), name = o.optString("name"),
        qty = o.optDouble("qty", 0.0), price = o.optDouble("price", 0.0),
        taxPercent = o.optDouble("taxPercent", 0.0), lineTotal = o.optDouble("lineTotal", 0.0),
        batchNo = o.optString("batchNo"), unit = o.optString("unit"),
        primaryQty = o.optDouble("primaryQty", 0.0)
    )

    private fun readGroup(o: JSONObject) = AccountGroup(
        id = o.optLong("id"), name = o.optString("name"),
        nature = runCatching { AccountNature.valueOf(o.optString("nature", "ASSET")) }.getOrDefault(AccountNature.ASSET),
        isSystem = o.optBoolean("isSystem", false)
    )

    private fun readHead(o: JSONObject) = AccountHead(
        id = o.optLong("id"), name = o.optString("name"), groupId = o.optLong("groupId"),
        openingBalance = o.optDouble("openingBalance", 0.0), openingIsDebit = o.optBoolean("openingIsDebit", true),
        isSystem = o.optBoolean("isSystem", false)
    )

    private fun readJEntry(o: JSONObject) = JournalEntry(
        id = o.optLong("id"), voucherNo = o.optString("voucherNo"), dateMillis = o.optLong("dateMillis"),
        narration = o.optString("narration"),
        cashMode = o.optString("cashMode"), cashIsIn = o.optBoolean("cashIsIn", true),
        cashAmount = o.optDouble("cashAmount", 0.0),
        source = o.optString("source")
    )

    private fun readItemAtt(context: Context, o: JSONObject) = ItemAttachment(
        id = o.optLong("id"), itemId = o.optLong("itemId"),
        path = File(com.billing.pos.items.ItemAttachmentStore.dir(context), o.optString("file")).absolutePath,
        name = o.optString("name"), mime = o.optString("mime"), kind = o.optString("kind", "PHOTO")
    )

    private fun readBillAtt(context: Context, o: JSONObject) = BillAttachment(
        id = o.optLong("id"), billId = o.optLong("billId"),
        path = File(com.billing.pos.bills.BillAttachmentStore.dir(context), o.optString("file")).absolutePath,
        name = o.optString("name"), mime = o.optString("mime")
    )

    private fun readJLine(o: JSONObject) = JournalLine(
        id = o.optLong("id"), entryId = o.optLong("entryId"), headId = o.optLong("headId"),
        headName = o.optString("headName"), amount = o.optDouble("amount", 0.0), isDebit = o.optBoolean("isDebit", true)
    )

    private fun readAtt(context: Context, o: JSONObject): DiaryAttachment {
        val type = runCatching { AttachmentType.valueOf(o.optString("type", "DOCUMENT")) }.getOrDefault(AttachmentType.DOCUMENT)
        val path = if (type == AttachmentType.LOCATION) o.optString("locUrl")
        else File(AttachmentStore.dir(context), o.optString("file")).absolutePath
        return DiaryAttachment(
            id = o.optLong("id"), entryId = o.optLong("entryId"), path = path,
            name = o.optString("name"), mime = o.optString("mime"), type = type
        )
    }

    private fun readBlock(context: Context, o: JSONObject): DiaryBlock {
        val type = runCatching { BlockType.valueOf(o.optString("type", "TEXT")) }.getOrDefault(BlockType.TEXT)
        val file = o.optString("file")
        val path = if (file.isBlank()) "" else File(AttachmentStore.dir(context), file).absolutePath
        return DiaryBlock(
            id = o.optLong("id"), entryId = o.optLong("entryId"), position = o.optInt("position"),
            type = type, text = o.optString("text"), path = path,
            name = o.optString("name"), mime = o.optString("mime"), durationMs = o.optLong("durationMs")
        )
    }
}
