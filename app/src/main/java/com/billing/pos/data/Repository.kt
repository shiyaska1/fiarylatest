package com.billing.pos.data

import android.content.Context
import com.billing.pos.auth.PasswordHasher
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

data class ImportResult(
    val billsAdded: Int,
    val billsSkipped: Int,
    val receiptsAdded: Int,
    val expensesAdded: Int,
    val source: String
)

/** Single access point for all data operations. */
class Repository(context: Context) {

    private val db = AppDatabase.get(context)
    private val customerDao = db.customerDao()
    private val itemDao = db.itemDao()
    private val itemAttachmentDao = db.itemAttachmentDao()
    private val billAttachmentDao = db.billAttachmentDao()
    private val billDao = db.billDao()
    private val userDao = db.userDao()
    private val receiptDao = db.receiptDao()
    private val expenseDao = db.expenseDao()
    private val supplierDao = db.supplierDao()
    private val purchaseDao = db.purchaseDao()
    private val accountDao = db.accountDao()
    private val journalDao = db.journalDao()
    private val appPrefs = AppPrefs(context)

    /** Device letter/name prefix for document numbers (e.g. "A-"), blank if not set. Keeps two synced phones from clashing. */
    private fun tagPrefix(): String {
        val t = appPrefs.deviceTag
        return if (t.isBlank()) "" else "$t-"
    }

    val customers: Flow<List<Customer>> = customerDao.observeAll()
    val items: Flow<List<Item>> = itemDao.observeAll()
    val itemAttachments: Flow<List<ItemAttachment>> = itemAttachmentDao.observeAll()
    val billAttachments: Flow<List<BillAttachment>> = billAttachmentDao.observeAll()
    val users: Flow<List<User>> = userDao.observeAll()
    val allBills: Flow<List<Bill>> = billDao.observeAll()
    val billLines: Flow<List<BillItem>> = billDao.observeAllLines()
    val allReceipts: Flow<List<Receipt>> = receiptDao.observeAll()
    val allExpenses: Flow<List<Expense>> = expenseDao.observeAll()
    val suppliers: Flow<List<Supplier>> = supplierDao.observeAll()
    val allPurchases: Flow<List<Purchase>> = purchaseDao.observeAll()
    val accountGroups: Flow<List<AccountGroup>> = accountDao.observeGroups()
    val accountHeads: Flow<List<AccountHead>> = accountDao.observeHeads()
    val journalEntries: Flow<List<JournalEntry>> = journalDao.observeAll()
    val journalLines: Flow<List<JournalLine>> = journalDao.observeAllLines()
    val purchaseLines: Flow<List<PurchaseLineInfo>> = purchaseDao.observePurchaseLines()
    val purchaseLineParties: Flow<List<PurchaseLineParty>> = purchaseDao.observePurchaseLineParties()
    val soldQty: Flow<List<NameQty>> = billDao.observeSoldQty()

    /** Seeds the default Cash customer and the initial super user. */
    companion object {
        const val DEFAULT_USERNAME = "superadmin"
        const val DEFAULT_PASSWORD = "admin123"
    }

    suspend fun ensureDefaults() {
        if (customerDao.count() == 0) {
            customerDao.insert(Customer(name = "Cash Customer", isDefault = true))
        }
        if (supplierDao.count() == 0) {
            supplierDao.insert(Supplier(name = "Cash Supplier", isDefault = true))
        }
        if (accountDao.groupCount() == 0) seedChartOfAccounts()
        runCatching { syncPartyHeads() }   // ensure every customer/supplier has a ledger head
        if (userDao.count() == 0) {
            userDao.insert(
                User(
                    username = DEFAULT_USERNAME,
                    passwordHash = PasswordHasher.hash(DEFAULT_PASSWORD),
                    role = Role.SUPER_USER,
                    canCreateInvoice = true, canEditInvoice = true, canDeleteInvoice = true, canViewInvoice = true,
                    canCreateReceipt = true, canEditReceipt = true, canDeleteReceipt = true, canViewReceipt = true,
                    canCreatePayment = true, canEditPayment = true, canDeletePayment = true, canViewPayment = true,
                    canViewCashbook = true,
                    canExport = true, canImport = true, canManageUsers = true
                )
            )
        }
    }

    private suspend fun seedChartOfAccounts() {
        suspend fun g(name: String, nature: AccountNature) =
            accountDao.insertGroup(AccountGroup(name = name, nature = nature, isSystem = true))
        val cash = g("Cash-in-hand", AccountNature.ASSET)
        val bank = g("Bank Accounts", AccountNature.ASSET)
        val debtors = g("Sundry Debtors", AccountNature.ASSET)
        g("Current Assets", AccountNature.ASSET)
        g("Fixed Assets", AccountNature.ASSET)
        val creditors = g("Sundry Creditors", AccountNature.LIABILITY)
        val taxes = g("Duties & Taxes", AccountNature.LIABILITY)
        g("Current Liabilities", AccountNature.LIABILITY)
        val capital = g("Capital Account", AccountNature.LIABILITY)
        val salesGrp = g("Sales Account", AccountNature.INCOME)
        g("Direct Income", AccountNature.INCOME)
        val indirectInc = g("Indirect Income", AccountNature.INCOME)
        val purchaseGrp = g("Purchase Account", AccountNature.EXPENSE)
        g("Direct Expenses", AccountNature.EXPENSE)
        val indirectExp = g("Indirect Expenses", AccountNature.EXPENSE)

        suspend fun h(name: String, groupId: Long) =
            accountDao.insertHead(AccountHead(name = name, groupId = groupId, isSystem = true))
        h("Cash", cash)
        h("Bank", bank)
        h("Sundry Debtors", debtors)
        h("Sundry Creditors", creditors)
        h("Sales", salesGrp)
        h("Purchase", purchaseGrp)
        h("Output Tax (GST/VAT)", taxes)
        h("Input Tax (GST/VAT)", taxes)
        h("Discount Allowed", indirectExp)
        h("Discount Received", indirectInc)
        h("Opening Capital", capital)
    }

    suspend fun addAccountGroup(name: String, nature: AccountNature): Long =
        accountDao.insertGroup(AccountGroup(name = name.trim(), nature = nature))

    suspend fun updateAccountGroup(group: AccountGroup) = accountDao.updateGroup(group)

    suspend fun deleteAccountGroup(group: AccountGroup): Result<Unit> {
        if (group.isSystem) return Result.failure(IllegalStateException("System group cannot be deleted"))
        if (accountDao.headCountInGroup(group.id) > 0) return Result.failure(IllegalStateException("Group has account heads"))
        accountDao.deleteGroup(group)
        return Result.success(Unit)
    }

    suspend fun addAccountHead(name: String, groupId: Long, openingBalance: Double, openingIsDebit: Boolean): Long =
        accountDao.insertHead(AccountHead(name = name.trim(), groupId = groupId, openingBalance = openingBalance, openingIsDebit = openingIsDebit))

    suspend fun updateAccountHead(head: AccountHead) = accountDao.updateHead(head)

    // ---- party & cash/bank ledger helpers (used by receipts/payments) ----
    private suspend fun groupIdByName(name: String): Long =
        accountDao.allGroups().firstOrNull { it.name.equals(name, true) }?.id ?: 0

    /** Ensure a ledger head [name] exists under [groupName]; returns its id (0 if group missing/blank). */
    private suspend fun ensureHeadIn(name: String, groupName: String): Long {
        val n = name.trim()
        if (n.isBlank()) return 0
        val gid = groupIdByName(groupName)
        if (gid == 0L) return 0
        return accountDao.headByNameGroup(n, gid)?.id ?: accountDao.insertHead(AccountHead(name = n, groupId = gid))
    }
    suspend fun ensureCustomerHead(name: String): Long = ensureHeadIn(name, "Sundry Debtors")
    suspend fun ensureSupplierHead(name: String): Long = ensureHeadIn(name, "Sundry Creditors")

    /** Cash/Bank head for a payment mode (e.g. UPI, Card, Cheque), created under Cash-in-hand if missing. */
    suspend fun ensureModeAccount(modeLabel: String): Long {
        val gid = groupIdByName("Cash-in-hand")
        if (gid == 0L) return 0
        return accountDao.headByNameGroup(modeLabel, gid)?.id
            ?: accountDao.headByName(modeLabel)?.id
            ?: accountDao.insertHead(AccountHead(name = modeLabel, groupId = gid))
    }

    /** Heads under Cash-in-hand + Bank Accounts groups — the "received to"/"from account" options. */
    val cashBankHeads: Flow<List<AccountHead>> =
        kotlinx.coroutines.flow.combine(accountDao.observeGroups(), accountDao.observeHeads()) { groups, heads ->
            val ids = groups.filter { it.name == "Cash-in-hand" || it.name == "Bank Accounts" }.map { it.id }.toSet()
            heads.filter { it.groupId in ids }
        }

    /** Heads under Sundry Debtors + Sundry Creditors groups — extra party options beyond customers/suppliers. */
    val sundryHeads: Flow<List<AccountHead>> =
        kotlinx.coroutines.flow.combine(accountDao.observeGroups(), accountDao.observeHeads()) { groups, heads ->
            val ids = groups.filter { it.name == "Sundry Debtors" || it.name == "Sundry Creditors" }.map { it.id }.toSet()
            heads.filter { it.groupId in ids }
        }

    suspend fun deleteAccountHead(head: AccountHead): Result<Unit> {
        if (head.isSystem) return Result.failure(IllegalStateException("System head cannot be deleted"))
        accountDao.deleteHead(head)
        return Result.success(Unit)
    }

    // ---- journal ----
    suspend fun nextJournalNo(): String =
        "JV-" + (journalDao.localCount() + 1).toString().padStart(4, '0')

    suspend fun saveJournal(entry: JournalEntry, lines: List<JournalLine>): Long = journalDao.saveJournal(entry, lines)
    suspend fun updateJournal(entry: JournalEntry, lines: List<JournalLine>) = journalDao.updateJournal(entry, lines)
    suspend fun deleteJournal(entry: JournalEntry) = journalDao.deleteJournal(entry)
    suspend fun journalById(id: Long): JournalEntry? = journalDao.byId(id)
    suspend fun journalLinesFor(id: Long): List<JournalLine> = journalDao.linesFor(id)

    // ---- auth / users ----
    suspend fun login(username: String, password: String): User? {
        val user = userDao.byUsername(username.trim()) ?: return null
        return if (PasswordHasher.verify(password, user.passwordHash)) user else null
    }

    suspend fun userById(id: Long): User? = userDao.byId(id)

    /** The built-in super-admin, for automatic login. */
    suspend fun superAdmin(): User? = userDao.byUsername(DEFAULT_USERNAME)

    /**
     * True while the factory account still has its printed password. Drives both the
     * credential hint on the login screen and the forced password change after login —
     * derived from the database, so it can never drift out of sync with reality.
     */
    suspend fun usesDefaultPassword(): Boolean {
        val user = userDao.byUsername(DEFAULT_USERNAME) ?: return false
        return PasswordHasher.verify(DEFAULT_PASSWORD, user.passwordHash)
    }

    fun isDefaultAccount(user: User): Boolean =
        user.username.equals(DEFAULT_USERNAME, true) &&
            PasswordHasher.verify(DEFAULT_PASSWORD, user.passwordHash)

    suspend fun createUser(user: User, password: String): Result<Unit> = runCatching {
        userDao.insert(user.copy(passwordHash = PasswordHasher.hash(password)))
        Unit
    }

    /** Updates a user. If [newPassword] is non-blank the password is reset. */
    suspend fun updateUser(user: User, newPassword: String?): Result<Unit> = runCatching {
        val hash = if (newPassword.isNullOrBlank()) user.passwordHash
        else PasswordHasher.hash(newPassword)
        userDao.update(user.copy(passwordHash = hash))
        Unit
    }

    suspend fun deleteUser(user: User) = userDao.delete(user)

    // ---- customers / items ----
    suspend fun addCustomer(name: String, phone: String, address: String, gstin: String = "", customerType: String = "General"): Long {
        val id = customerDao.insert(Customer(name = name.trim(), phone = phone.trim(), address = address.trim(), gstin = gstin.trim(), customerType = customerType.trim().ifBlank { "General" }))
        ensureCustomerHead(name)   // create the customer's ledger head immediately
        return id
    }

    suspend fun addCustomerReturning(name: String, phone: String, customerType: String = "General"): Customer {
        val id = customerDao.insert(Customer(name = name.trim(), phone = phone.trim(), customerType = customerType.trim().ifBlank { "General" }))
        ensureCustomerHead(name)
        return Customer(id = id, name = name.trim(), phone = phone.trim(), customerType = customerType.trim().ifBlank { "General" })
    }

    suspend fun customerByPhone(phone: String): Customer? =
        phone.trim().takeIf { it.isNotBlank() }?.let { customerDao.byPhone(it) }

    suspend fun updateCustomer(customer: Customer) = customerDao.update(customer)

    suspend fun customerHasTransactions(customer: Customer): Boolean =
        billDao.countForCustomer(customer.id, customer.name) > 0

    /** Deletes a customer only if it has no invoices and is not the default. */
    suspend fun deleteCustomer(customer: Customer): Result<Unit> {
        if (customer.isDefault) return Result.failure(IllegalStateException("Cannot delete the default customer"))
        if (customerHasTransactions(customer)) return Result.failure(IllegalStateException("Customer has invoices"))
        customerDao.delete(customer)
        return Result.success(Unit)
    }

    val allCustomers: Flow<List<Customer>> get() = customers

    suspend fun addItem(
        name: String, price: Double, taxPercent: Double, barcode: String = "", hsn: String = "",
        category: String = "", openingStock: Double = 0.0, unit: String = "PCS", storeLocation: String = "",
        chemicalContent: String = "", secondaryUnit: String = "PCS", conversionFactor: Double = 1.0,
        purchasePrice: Double = 0.0
    ): Long =
        itemDao.insert(Item(
            name = name.trim(), price = price, purchasePrice = purchasePrice, taxPercent = taxPercent,
            barcode = barcode.trim(), hsn = hsn.trim(),
            category = category.trim(), openingStock = openingStock, unit = unit.trim().ifBlank { "PCS" },
            secondaryUnit = secondaryUnit.trim().ifBlank { "PCS" },
            conversionFactor = if (conversionFactor > 0) conversionFactor else 1.0,
            storeLocation = storeLocation.trim(), chemicalContent = chemicalContent.trim()
        ))

    suspend fun updateItem(item: Item) = itemDao.update(item)
    suspend fun deleteItem(item: Item) {
        itemAttachmentDao.forItem(item.id).forEach { com.billing.pos.items.ItemAttachmentStore.delete(it) }
        itemAttachmentDao.deleteForItem(item.id)
        db.itemBatchDao().deleteForItem(item.id)
        db.itemSizeDao().deleteForItem(item.id)
        itemDao.delete(item)
    }

    /** Removes every master item, its batches, sizes and attachment files. Bills keep their lines. */
    suspend fun clearAllItems() {
        itemAttachmentDao.all().forEach { com.billing.pos.items.ItemAttachmentStore.delete(it) }
        itemAttachmentDao.deleteAll()
        db.itemBatchDao().deleteAll()
        db.itemSizeDao().deleteAll()
        itemDao.deleteAll()
    }
    suspend fun itemByBarcode(barcode: String): Item? = itemDao.byBarcode(barcode.trim())
    suspend fun itemByName(name: String): Item? = itemDao.byName(name.trim())

    // ---- item batches (batch no + expiry + qty) ----
    private val itemBatchDao = db.itemBatchDao()
    val itemBatches: Flow<List<ItemBatch>> = itemBatchDao.observeAll()

    /** Batch-wise purchase rate + source voucher, for expiry costing and drill-down. */
    val batchCosts: Flow<List<BatchCostRow>> = purchaseDao.observeBatchCosts()
    suspend fun batchesForItem(itemId: Long): List<ItemBatch> = itemBatchDao.forItem(itemId)
    suspend fun addBatch(batch: ItemBatch): Long = itemBatchDao.insert(batch)
    suspend fun replaceBatches(itemId: Long, batches: List<ItemBatch>) {
        itemBatchDao.deleteForItem(itemId)
        batches.forEach { itemBatchDao.insert(it.copy(id = 0, itemId = itemId)) }
    }
    suspend fun batchByItemAndNo(itemId: Long, batchNo: String): ItemBatch? = itemBatchDao.byItemAndNo(itemId, batchNo)
    /** Deducts a sold quantity from a batch's stock. */
    suspend fun deductBatch(itemId: Long, batchNo: String, qty: Double) {
        if (batchNo.isNotBlank()) itemBatchDao.deductQty(itemId, batchNo, qty)
    }
    /** Receives stock into a batch: adds to an existing batch, or creates a new one. */
    suspend fun receiveBatch(itemId: Long, batchNo: String, expiryMillis: Long, qty: Double) {
        if (batchNo.isBlank() || itemId <= 0) return
        val existing = itemBatchDao.byItemAndNo(itemId, batchNo)
        if (existing != null) itemBatchDao.addQty(itemId, batchNo, qty)
        else itemBatchDao.insert(ItemBatch(itemId = itemId, batchNo = batchNo, expiryMillis = expiryMillis, quantity = qty))
    }

    // ---- quotations ----
    private val quotationDao = db.quotationDao()
    val quotations: Flow<List<Quotation>> = quotationDao.observeAll()
    suspend fun nextQuotationNo(): String = "QT-" + (quotationDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveQuotation(q: Quotation, lines: List<QuotationItem>): Long = quotationDao.save(q, lines)
    suspend fun updateQuotation(q: Quotation, lines: List<QuotationItem>) = quotationDao.update(q, lines)
    suspend fun deleteQuotation(q: Quotation) = quotationDao.delete(q)
    suspend fun quotationById(id: Long): Quotation? = quotationDao.byId(id)
    suspend fun quotationLines(id: Long): List<QuotationItem> = quotationDao.linesFor(id)

    // ---- payment attachments ----
    private val custOrderDao = db.custOrderDao()

    val custOrders: Flow<List<CustOrder>> = custOrderDao.observeAll()
    val custOrderLinesFlow: Flow<List<CustOrderItem>> = custOrderDao.observeAllLines()
    suspend fun nextOrderNo(): String = "ORD-" + (custOrderDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveOrder(o: CustOrder, lines: List<CustOrderItem>): Long = custOrderDao.save(o, lines)
    suspend fun updateOrder(o: CustOrder, lines: List<CustOrderItem>) = custOrderDao.update(o, lines)
    suspend fun deleteOrder(o: CustOrder) = custOrderDao.delete(o)
    suspend fun orderById(id: Long): CustOrder? = custOrderDao.byId(id)
    suspend fun orderLines(id: Long): List<CustOrderItem> = custOrderDao.linesFor(id)
    suspend fun orderAttachmentsFor(id: Long): List<CustOrderAttachment> = custOrderDao.attachmentsFor(id)
    suspend fun replaceOrderAttachments(orderId: Long, list: List<CustOrderAttachment>) {
        val existing = custOrderDao.attachmentsFor(orderId)
        val keep = list.map { it.path }.toSet()
        existing.filter { it.path !in keep }.forEach { runCatching { java.io.File(it.path).delete() } }
        custOrderDao.deleteAttachments(orderId)
        list.forEach { custOrderDao.insertAttachment(it.copy(id = 0, orderId = orderId)) }
    }

    private val customerAttachmentDao = db.customerAttachmentDao()

    suspend fun customerAttachmentsFor(customerId: Long): List<CustomerAttachment> =
        customerAttachmentDao.forCustomer(customerId)

    /** Replaces the whole set, deleting the files of any row that was removed. */
    suspend fun replaceCustomerAttachments(customerId: Long, list: List<CustomerAttachment>) {
        val existing = customerAttachmentDao.forCustomer(customerId)
        val keep = list.map { it.path }.toSet()
        existing.filter { it.path !in keep }.forEach { runCatching { java.io.File(it.path).delete() } }
        customerAttachmentDao.deleteForCustomer(customerId)
        list.forEach { customerAttachmentDao.insert(it.copy(id = 0, customerId = customerId)) }
    }

    /** Adds attachments to a customer without touching the ones already there. */
    suspend fun appendCustomerAttachments(customerId: Long, list: List<CustomerAttachment>) {
        list.forEach { customerAttachmentDao.insert(it.copy(id = 0, customerId = customerId)) }
    }

    private val savedCalcDao = db.savedCalcDao()

    /** Saved calculator tapes, newest first. */
    val savedCalcs: kotlinx.coroutines.flow.Flow<List<SavedCalc>> = savedCalcDao.observeAll()

    suspend fun saveCalc(c: SavedCalc): Long =
        if (c.id == 0L) savedCalcDao.insert(c) else { savedCalcDao.update(c); c.id }

    suspend fun deleteCalc(id: Long) = savedCalcDao.delete(id)

    private val expenseAttachmentDao = db.expenseAttachmentDao()
    suspend fun expenseAttachmentsFor(expenseId: Long): List<ExpenseAttachment> =
        expenseAttachmentDao.forExpense(expenseId)

    /** Replaces the attachment rows for a payment, deleting files that were removed. */
    suspend fun replaceExpenseAttachments(expenseId: Long, list: List<ExpenseAttachment>) {
        val existing = expenseAttachmentDao.forExpense(expenseId)
        val keep = list.map { it.path }.toSet()
        existing.filter { it.path !in keep }.forEach { com.billing.pos.expenses.ExpenseAttachmentStore.delete(it) }
        expenseAttachmentDao.deleteForExpense(expenseId)
        list.forEach { expenseAttachmentDao.insert(it.copy(id = 0, expenseId = expenseId)) }
    }

    // ---- receipt attachments ----
    private val receiptAttachmentDao = db.receiptAttachmentDao()
    suspend fun receiptAttachmentsFor(receiptId: Long): List<ReceiptAttachment> =
        receiptAttachmentDao.forReceipt(receiptId)

    /** Replaces the attachment rows for a receipt, deleting files that were removed. */
    suspend fun replaceReceiptAttachments(receiptId: Long, list: List<ReceiptAttachment>) {
        val existing = receiptAttachmentDao.forReceipt(receiptId)
        val keep = list.map { it.path }.toSet()
        existing.filter { it.path !in keep }.forEach { ReceiptAttachmentStore.delete(it) }
        receiptAttachmentDao.deleteForReceipt(receiptId)
        receiptAttachmentDao.insertAll(list.map { it.copy(id = 0, receiptId = receiptId) })
    }

    // ---- estimates ----
    // Deliberately absent from stockByName: an estimate never moves stock.
    private val estimateDao = db.estimateDao()
    val estimates: Flow<List<Estimate>> = estimateDao.observeAll()
    suspend fun nextEstimateNo(): String = "EST-" + (estimateDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveEstimate(e: Estimate, lines: List<EstimateItem>): Long = estimateDao.save(e, lines)
    suspend fun updateEstimate(e: Estimate, lines: List<EstimateItem>) = estimateDao.update(e, lines)
    suspend fun deleteEstimate(e: Estimate) = estimateDao.delete(e)
    suspend fun estimateById(id: Long): Estimate? = estimateDao.byId(id)
    suspend fun estimateLines(id: Long): List<EstimateItem> = estimateDao.linesFor(id)

    // ---- sales returns ----
    private val salesReturnDao = db.salesReturnDao()
    val salesReturns: Flow<List<SalesReturn>> = salesReturnDao.observeAll()
    suspend fun nextSalesReturnNo(): String = "SR-" + (salesReturnDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveSalesReturn(r: SalesReturn, lines: List<SalesReturnItem>): Long = salesReturnDao.save(r, lines)
    suspend fun updateSalesReturn(r: SalesReturn, lines: List<SalesReturnItem>) = salesReturnDao.update(r, lines)
    suspend fun deleteSalesReturn(r: SalesReturn) = salesReturnDao.delete(r)
    suspend fun salesReturnById(id: Long): SalesReturn? = salesReturnDao.byId(id)
    suspend fun salesReturnLines(id: Long): List<SalesReturnItem> = salesReturnDao.linesFor(id)

    // ---- purchase returns ----
    private val purchaseReturnDao = db.purchaseReturnDao()
    val purchaseReturns: Flow<List<PurchaseReturn>> = purchaseReturnDao.observeAll()
    suspend fun nextPurchaseReturnNo(): String = "PR-" + (purchaseReturnDao.count() + 1).toString().padStart(4, '0')
    suspend fun savePurchaseReturn(r: PurchaseReturn, lines: List<PurchaseReturnItem>): Long = purchaseReturnDao.save(r, lines)
    suspend fun updatePurchaseReturn(r: PurchaseReturn, lines: List<PurchaseReturnItem>) = purchaseReturnDao.update(r, lines)
    suspend fun deletePurchaseReturn(r: PurchaseReturn) = purchaseReturnDao.delete(r)
    suspend fun purchaseReturnById(id: Long): PurchaseReturn? = purchaseReturnDao.byId(id)
    suspend fun purchaseReturnLines(id: Long): List<PurchaseReturnItem> = purchaseReturnDao.linesFor(id)

    // ---- purchase quotations (LPO) ----
    private val purchaseQuotationDao = db.purchaseQuotationDao()
    val purchaseQuotations: Flow<List<PurchaseQuotation>> = purchaseQuotationDao.observeAll()
    private val purchaseQuoteDao = db.purchaseQuoteDao()

    val purchaseQuotes: Flow<List<PurchaseQuote>> = purchaseQuoteDao.observeAll()
    suspend fun nextPurchaseQuoteNo(): String = "PQ-" + (purchaseQuoteDao.count() + 1).toString().padStart(4, '0')
    suspend fun savePurchaseQuote(r: PurchaseQuote, lines: List<PurchaseQuoteItem>): Long = purchaseQuoteDao.save(r, lines)
    suspend fun updatePurchaseQuote(r: PurchaseQuote, lines: List<PurchaseQuoteItem>) = purchaseQuoteDao.update(r, lines)
    suspend fun deletePurchaseQuote(r: PurchaseQuote) = purchaseQuoteDao.delete(r)
    suspend fun purchaseQuoteById(id: Long): PurchaseQuote? = purchaseQuoteDao.byId(id)
    suspend fun purchaseQuoteLines(id: Long): List<PurchaseQuoteItem> = purchaseQuoteDao.linesFor(id)

    suspend fun nextLpoNo(): String = "LPO-" + (purchaseQuotationDao.count() + 1).toString().padStart(4, '0')
    suspend fun savePurchaseQuotation(r: PurchaseQuotation, lines: List<PurchaseQuotationItem>): Long = purchaseQuotationDao.save(r, lines)
    suspend fun updatePurchaseQuotation(r: PurchaseQuotation, lines: List<PurchaseQuotationItem>) = purchaseQuotationDao.update(r, lines)
    suspend fun deletePurchaseQuotation(r: PurchaseQuotation) = purchaseQuotationDao.delete(r)
    suspend fun purchaseQuotationById(id: Long): PurchaseQuotation? = purchaseQuotationDao.byId(id)
    suspend fun purchaseQuotationLines(id: Long): List<PurchaseQuotationItem> = purchaseQuotationDao.linesFor(id)

    // ---- hire (rental) invoices ----
    private val hireInvoiceDao = db.hireInvoiceDao()
    val hireInvoices: Flow<List<HireInvoice>> = hireInvoiceDao.observeAll()
    val hireOutByItem: Flow<List<HireNameQty>> = hireInvoiceDao.observeOutByItem()
    val hireLinesFlow: Flow<List<HireInvoiceItem>> = hireInvoiceDao.observeAllLines()
    suspend fun nextHireNo(): String = "HR-" + (hireInvoiceDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveHireInvoice(h: HireInvoice, lines: List<HireInvoiceItem>): Long = hireInvoiceDao.save(h, lines)
    suspend fun updateHireInvoice(h: HireInvoice, lines: List<HireInvoiceItem>) = hireInvoiceDao.update(h, lines)
    suspend fun deleteHireInvoice(h: HireInvoice) = hireInvoiceDao.delete(h)
    suspend fun hireInvoiceById(id: Long): HireInvoice? = hireInvoiceDao.byId(id)
    suspend fun hireInvoiceLines(id: Long): List<HireInvoiceItem> = hireInvoiceDao.linesFor(id)
    suspend fun allHireLines(): List<HireInvoiceItem> = hireInvoiceDao.allLines()

    // ---- hire returns ----
    private val hireReturnDao = db.hireReturnDao()
    val hireReturns: Flow<List<HireReturn>> = hireReturnDao.observeAll()
    val hireReturnedByItem: Flow<List<HireNameQty>> = hireReturnDao.observeReturnedByItem()
    val hireReturnedByHire: Flow<List<HireIdQty>> = hireReturnDao.observeReturnedByHire()
    suspend fun nextHireReturnNo(): String = "HRR-" + (hireReturnDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveHireReturn(r: HireReturn, lines: List<HireReturnItem>): Long = hireReturnDao.save(r, lines)
    suspend fun updateHireReturn(r: HireReturn, lines: List<HireReturnItem>) = hireReturnDao.update(r, lines)
    suspend fun deleteHireReturn(r: HireReturn) = hireReturnDao.delete(r)
    suspend fun hireReturnById(id: Long): HireReturn? = hireReturnDao.byId(id)
    suspend fun hireReturnLines(id: Long): List<HireReturnItem> = hireReturnDao.linesFor(id)
    suspend fun returnedQtyForHire(hireId: Long): Double = hireReturnDao.returnedQtyForHire(hireId) ?: 0.0

    // ---- medical lab: tests + evaluations ----
    private val labTestDao = db.labTestDao()
    val labTests: Flow<List<LabTest>> = labTestDao.observeTests()
    suspend fun labTestCount(): Int = labTestDao.count()
    suspend fun saveLabTest(t: LabTest, evals: List<LabEvaluation>): Long = labTestDao.saveTest(t, evals)
    suspend fun deleteLabTest(t: LabTest) = labTestDao.delete(t)
    suspend fun labTestById(id: Long): LabTest? = labTestDao.testById(id)
    suspend fun labEvaluationsFor(testId: Long): List<LabEvaluation> = labTestDao.evaluationsFor(testId)
    suspend fun allLabTests(): List<LabTest> = labTestDao.allTests()
    /** Seeds the built-in sample tests the first time (only when none exist). Also fills the masters. */
    suspend fun seedSampleLabTests(): Int {
        if (labTestDao.count() > 0) return 0
        SampleLabData.tests.forEach { s ->
            labTestDao.saveTest(
                LabTest(name = s.name, price = s.price, sampleType = s.sampleType),
                s.evaluations.map { LabEvaluation(testId = 0, name = it.name, unit = it.unit, normalValue = it.normal, groupName = it.group) }
            )
            s.evaluations.forEach { addEvalToMaster(it.name, it.unit, it.normal, it.group) }
        }
        return SampleLabData.tests.size
    }

    // ---- lab masters: groups + evaluations ----
    private val labMasterDao = db.labMasterDao()
    val labGroups: Flow<List<LabGroup>> = labMasterDao.observeGroups()
    val labEvalMasters: Flow<List<LabEvalMaster>> = labMasterDao.observeEvals()
    suspend fun saveLabGroup(g: LabGroup): Long = labMasterDao.insertGroup(g)
    suspend fun deleteLabGroup(g: LabGroup) = labMasterDao.deleteGroup(g)
    suspend fun ensureLabGroup(name: String) { if (name.isNotBlank() && labMasterDao.groupByName(name) == null) labMasterDao.insertGroup(LabGroup(name = name.trim())) }
    suspend fun saveLabEvalMaster(e: LabEvalMaster): Long = if (e.id == 0L) labMasterDao.insertEval(e) else { labMasterDao.updateEval(e); e.id }
    suspend fun deleteLabEvalMaster(e: LabEvalMaster) = labMasterDao.deleteEval(e)
    suspend fun labEvalsInGroup(group: String): List<LabEvalMaster> = labMasterDao.evalsInGroup(group)
    suspend fun allLabGroups(): List<LabGroup> = labMasterDao.allGroups()
    suspend fun allLabEvalMasters(): List<LabEvalMaster> = labMasterDao.allEvals()
    /** Adds an evaluation to the master if a same-named one doesn't already exist (and its group). */
    suspend fun addEvalToMaster(name: String, unit: String, normal: String, group: String) {
        if (name.isBlank()) return
        ensureLabGroup(group)
        if (labMasterDao.evalByName(name) == null)
            labMasterDao.insertEval(LabEvalMaster(name = name.trim(), unit = unit.trim(), normalValue = normal.trim(), groupName = group.trim()))
    }
    val labHeadings: Flow<List<LabHeading>> = labMasterDao.observeHeadings()
    suspend fun deleteLabHeading(h: LabHeading) = labMasterDao.deleteHeading(h)
    suspend fun addHeadingToMaster(name: String) { if (name.isNotBlank() && labMasterDao.headingByName(name) == null) labMasterDao.insertHeading(LabHeading(name = name.trim())) }
    suspend fun allLabHeadings(): List<LabHeading> = labMasterDao.allHeadings()
    val labDoctors: Flow<List<LabDoctor>> = labMasterDao.observeDoctors()
    suspend fun deleteLabDoctor(d: LabDoctor) = labMasterDao.deleteDoctor(d)
    suspend fun addDoctorToMaster(name: String) { if (name.isNotBlank() && labMasterDao.doctorByName(name) == null) labMasterDao.insertDoctor(LabDoctor(name = name.trim())) }
    suspend fun allLabDoctors(): List<LabDoctor> = labMasterDao.allDoctors()

    // ---- lab patients ----
    private val patientDao = db.patientDao()
    val patients: Flow<List<Patient>> = patientDao.observeAll()
    suspend fun savePatient(p: Patient): Long = if (p.id == 0L) patientDao.insert(p) else { patientDao.update(p); p.id }
    suspend fun deletePatient(p: Patient) = patientDao.delete(p)
    suspend fun patientById(id: Long): Patient? = patientDao.byId(id)

    // ---- lab bills + results ----
    private val labBillDao = db.labBillDao()
    val labBills: Flow<List<LabBill>> = labBillDao.observeBills()
    suspend fun nextLabBillNo(): String = "LAB-" + (labBillDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveLabBill(b: LabBill, tests: List<LabBillTest>): Long = labBillDao.saveBill(b, tests)
    suspend fun deleteLabBill(b: LabBill) = labBillDao.delete(b)
    suspend fun labBillById(id: Long): LabBill? = labBillDao.billById(id)
    suspend fun labBillTests(id: Long): List<LabBillTest> = labBillDao.testsFor(id)
    suspend fun labResultsFor(id: Long): List<LabResultValue> = labBillDao.resultsFor(id)
    suspend fun saveLabResults(bill: LabBill, results: List<LabResultValue>) = labBillDao.saveResults(bill, results)

    // ---- lab balance receipts ----
    private val labReceiptDao = db.labReceiptDao()
    val labReceipts: Flow<List<LabReceipt>> = labReceiptDao.observeAll()
    val labReceiptSumByBill: Flow<List<BillIdSum>> = labReceiptDao.observeSumByBill()
    suspend fun addLabReceipt(r: LabReceipt): Long = labReceiptDao.insert(r)
    suspend fun deleteLabReceipt(r: LabReceipt) = labReceiptDao.delete(r)
    /** Outstanding for a lab bill = grand total − advance paid − later receipts. */
    suspend fun labBillOutstanding(bill: LabBill): Double =
        (bill.grandTotal - bill.paidAmount - labReceiptDao.sumForBill(bill.id)).coerceAtLeast(0.0)

    // ---- material out (consumption / issue) ----
    private val materialOutDao = db.materialOutDao()
    val materialOuts: Flow<List<MaterialOut>> = materialOutDao.observeAll()
    val materialOutByItem: Flow<List<NameQty>> = materialOutDao.observeOutByItem()
    suspend fun nextMaterialOutNo(): String = "MAT-" + (materialOutDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveMaterialOut(m: MaterialOut, lines: List<MaterialOutItem>): Long = materialOutDao.save(m, lines)
    suspend fun updateMaterialOut(m: MaterialOut, lines: List<MaterialOutItem>) = materialOutDao.update(m, lines)
    suspend fun deleteMaterialOut(m: MaterialOut) = materialOutDao.delete(m)
    suspend fun materialOutById(id: Long): MaterialOut? = materialOutDao.byId(id)
    suspend fun materialOutLines(id: Long): List<MaterialOutItem> = materialOutDao.linesFor(id)

    // ---- material receipts (goods received against an LPO) ----
    private val materialReceiptDao = db.materialReceiptDao()
    val materialReceipts: Flow<List<MaterialReceipt>> = materialReceiptDao.observeAll()
    val materialReceivedByItem: Flow<List<NameQty>> = materialReceiptDao.observeReceivedByItem()
    val receivedByLpo: Flow<List<LpoReceivedRow>> = materialReceiptDao.observeReceivedByLpo()
    val purchaseQuotationLinesFlow: Flow<List<PurchaseQuotationItem>> = purchaseQuotationDao.observeAllLines()
    suspend fun nextMrnNo(): String = "MRN-" + (materialReceiptDao.count() + 1).toString().padStart(4, '0')
    suspend fun saveMaterialReceipt(m: MaterialReceipt, lines: List<MaterialReceiptItem>): Long = materialReceiptDao.save(m, lines)
    suspend fun updateMaterialReceipt(m: MaterialReceipt, lines: List<MaterialReceiptItem>) = materialReceiptDao.update(m, lines)
    suspend fun deleteMaterialReceipt(m: MaterialReceipt) = materialReceiptDao.delete(m)
    suspend fun materialReceiptById(id: Long): MaterialReceipt? = materialReceiptDao.byId(id)
    suspend fun materialReceiptLines(id: Long): List<MaterialReceiptItem> = materialReceiptDao.linesFor(id)
    suspend fun returnedQtyForSupplier(supplierId: Long): List<NameQty> = purchaseDao.returnedQtyForSupplier(supplierId)

    /**
     * name (lowercased) -> net live-stock delta = stock-purchases + material receipts
     * - sales - material out. Openings are added per item on top of this.
     */
    val stockByName: Flow<Map<String, Double>> =
        kotlinx.coroutines.flow.combine(
            purchaseDao.observePurchaseStockQty(), materialReceivedByItem, soldQty, materialOutByItem
        ) { pur, recv, sold, out ->
            val m = HashMap<String, Double>()
            fun apply(list: List<NameQty>, sign: Double) = list.forEach { nq ->
                val k = nq.name.lowercase(); m[k] = (m[k] ?: 0.0) + sign * nq.qty
            }
            apply(pur, 1.0); apply(recv, 1.0); apply(sold, -1.0); apply(out, -1.0)
            m
        }

    /** All material-out lines belonging to vouchers that reference [billNo]. */
    suspend fun usedMaterialsForBill(billNo: String): List<MaterialOutItem> {
        if (billNo.isBlank()) return emptyList()
        return materialOutDao.all().filter { it.resultRef.contains(billNo, ignoreCase = true) }
            .flatMap { materialOutDao.linesFor(it.id) }
    }
    /** Latest purchase rate per item name (primary unit) — cost basis. */
    suspend fun lastPurchaseRates(): Map<String, Double> =
        purchaseDao.purchaseLinesOnce().groupBy { it.name.lowercase() }
            .mapValues { (_, l) -> l.maxByOrNull { it.dateMillis }?.price ?: 0.0 }

    // ---- item movement report sources ----
    val saleMovements: Flow<List<MoveRow>> = billDao.observeSaleMovements()
    val purchaseMovements: Flow<List<MoveRow>> = purchaseDao.observePurchaseMovements()
    val materialMovements: Flow<List<MoveRow>> = materialOutDao.observeMovements()

    // ---- item sizes (variants with their own price) ----
    private val itemSizeDao = db.itemSizeDao()
    val itemSizes: Flow<List<ItemSize>> = itemSizeDao.observeAll()
    suspend fun sizesForItem(itemId: Long): List<ItemSize> = itemSizeDao.forItem(itemId)
    suspend fun replaceSizes(itemId: Long, sizes: List<ItemSize>) {
        itemSizeDao.deleteForItem(itemId)
        sizes.forEach { itemSizeDao.insert(it.copy(id = 0, itemId = itemId)) }
    }

    // ---- item attachments (photos / location photo / PDF catalogue) ----
    suspend fun itemAttachmentsFor(itemId: Long): List<ItemAttachment> = itemAttachmentDao.forItem(itemId)
    suspend fun addItemAttachment(attachment: ItemAttachment): Long = itemAttachmentDao.insert(attachment)
    suspend fun deleteItemAttachment(attachment: ItemAttachment) {
        itemAttachmentDao.delete(attachment)
        com.billing.pos.items.ItemAttachmentStore.delete(attachment)
    }

    // ---- bill attachments (documents attached to an invoice) ----
    suspend fun billAttachmentsFor(billId: Long): List<BillAttachment> = billAttachmentDao.forBill(billId)
    suspend fun addBillAttachment(attachment: BillAttachment): Long = billAttachmentDao.insert(attachment)
    suspend fun deleteBillAttachment(attachment: BillAttachment) {
        billAttachmentDao.delete(attachment)
        com.billing.pos.bills.BillAttachmentStore.delete(attachment)
    }

    /** Bill number for the next locally-created bill, e.g. A-INV-0001 (the A- prefix is this device's tag). */
    suspend fun nextBillNo(): String {
        val n = billDao.localCount() + 1
        return tagPrefix() + "INV-" + n.toString().padStart(4, '0')
    }

    // ---- bills ----
    suspend fun saveBill(bill: Bill, lines: List<BillItem>): Long = billDao.saveBill(bill, lines)
    suspend fun updateBill(bill: Bill, lines: List<BillItem>) = billDao.updateBill(bill, lines)
    suspend fun deleteBill(bill: Bill) {
        billAttachmentDao.forBill(bill.id).forEach { com.billing.pos.bills.BillAttachmentStore.delete(it) }
        billAttachmentDao.deleteForBill(bill.id)
        billDao.deleteBill(bill)
    }
    suspend fun linesFor(billId: Long): List<BillItem> = billDao.linesFor(billId)
    suspend fun billById(id: Long): Bill? = billDao.byId(id)
    fun billsBetween(from: Long, to: Long): Flow<List<Bill>> = billDao.observeBetween(from, to)

    // ---- receipts (money received against credit invoices) ----
    suspend fun nextReceiptNo(): String =
        tagPrefix() + "RV-" + (receiptDao.localCount() + 1).toString().padStart(4, '0')

    /** Records a receipt against [bill] and increases the invoice's paid amount. */
    suspend fun addReceipt(bill: Bill, amount: Double, mode: PayMode, dateMillis: Long = System.currentTimeMillis(), toAccountId: Long = 0): Receipt {
        ensureCustomerHead(bill.customerName)
        val receipt = Receipt(
            receiptNo = nextReceiptNo(),
            billId = bill.id,
            billNo = bill.billNo,
            customerName = bill.customerName,
            dateMillis = dateMillis,
            amount = amount,
            paymentMode = mode.label,
            payFrom = bill.customerName,
            toAccountId = if (toAccountId != 0L) toAccountId else ensureModeAccount(mode.label)
        )
        val newId = receiptDao.insert(receipt)
        val newPaid = (bill.paidAmount + amount).coerceAtMost(bill.grandTotal)
        billDao.updateBillHeader(bill.copy(paidAmount = newPaid))
        return receipt.copy(id = newId)
    }

    /** Records a receipt from any source (no invoice link; [refNo] labels it, e.g. a job card no). */
    suspend fun addStandaloneReceipt(payFrom: String, amount: Double, mode: PayMode, dateMillis: Long = System.currentTimeMillis(), toAccountId: Long = 0, partyIsSupplier: Boolean = false, refNo: String = ""): Receipt {
        if (payFrom.isNotBlank()) { if (partyIsSupplier) ensureSupplierHead(payFrom) else ensureCustomerHead(payFrom) }
        val receipt = Receipt(
            receiptNo = nextReceiptNo(),
            billId = 0,
            billNo = refNo,
            customerName = payFrom.trim(),
            dateMillis = dateMillis,
            amount = amount,
            paymentMode = mode.label,
            payFrom = payFrom.trim(),
            toAccountId = if (toAccountId != 0L) toAccountId else ensureModeAccount(mode.label)
        )
        val newId = receiptDao.insert(receipt)
        return receipt.copy(id = newId)
    }

    /** Distinct "pay from" names previously used, for the dropdown. */
    suspend fun payFromNames(): List<String> = receiptDao.payFromNames()

    // ---- expenses / payment vouchers (money paid out) ----
    suspend fun nextVoucherNo(): String =
        tagPrefix() + "PV-" + (expenseDao.localCount() + 1).toString().padStart(4, '0')

    suspend fun addExpense(description: String, amount: Double, mode: PayMode, dateMillis: Long = System.currentTimeMillis(), fromAccountId: Long = 0): Expense {
        val expense = Expense(
            voucherNo = nextVoucherNo(),
            dateMillis = dateMillis,
            description = description.trim(),
            amount = amount,
            paymentMode = mode.label,
            fromAccountId = if (fromAccountId != 0L) fromAccountId else ensureModeAccount(mode.label)
        )
        // Return the row with its generated id — callers attach files to it, and an id of 0
        // silently orphans them.
        val id = expenseDao.insert(expense)
        return expense.copy(id = id)
    }

    /** Like [addExpense] but also records the party paid to. */
    suspend fun addExpenseFull(description: String, amount: Double, mode: PayMode, dateMillis: Long, payTo: String, fromAccountId: Long = 0, partyIsCustomer: Boolean = false): Expense {
        if (payTo.isNotBlank()) { if (partyIsCustomer) ensureCustomerHead(payTo) else ensureSupplierHead(payTo) }
        val expense = Expense(
            voucherNo = nextVoucherNo(),
            dateMillis = dateMillis,
            description = description.trim(),
            amount = amount,
            paymentMode = mode.label,
            payTo = payTo.trim(),
            fromAccountId = if (fromAccountId != 0L) fromAccountId else ensureModeAccount(mode.label)
        )
        // Return the row with its generated id — callers attach files to it, and an id of 0
        // silently orphans them.
        val id = expenseDao.insert(expense)
        return expense.copy(id = id)
    }

    /** Edits a receipt and re-applies the difference to the linked invoice's paid amount. */
    suspend fun updateReceipt(old: Receipt, newAmount: Double, mode: PayMode) {
        if (old.billId > 0) {
            billDao.byId(old.billId)?.let { bill ->
                val adjusted = (bill.paidAmount - old.amount + newAmount)
                    .coerceIn(0.0, bill.grandTotal)
                billDao.updateBillHeader(bill.copy(paidAmount = adjusted))
            }
        }
        receiptDao.update(old.copy(amount = newAmount, paymentMode = mode.label))
    }

    /** Deletes a receipt and reduces the linked invoice's paid amount. */
    suspend fun deleteReceipt(receipt: Receipt) {
        if (receipt.billId > 0) {
            billDao.byId(receipt.billId)?.let { bill ->
                val adjusted = (bill.paidAmount - receipt.amount).coerceAtLeast(0.0)
                billDao.updateBillHeader(bill.copy(paidAmount = adjusted))
            }
        }
        receiptAttachmentDao.forReceipt(receipt.id).forEach { ReceiptAttachmentStore.delete(it) }
        receiptAttachmentDao.deleteForReceipt(receipt.id)
        receiptDao.delete(receipt)
    }

    /** Records a payment against a (credit) purchase and increases its paid amount. */
    suspend fun addPaymentForPurchase(purchase: Purchase, amount: Double, mode: PayMode, fromAccountId: Long = 0): Expense {
        ensureSupplierHead(purchase.supplierName)
        val expense = Expense(
            voucherNo = nextVoucherNo(),
            dateMillis = System.currentTimeMillis(),
            description = "Payment to ${purchase.supplierName}",
            amount = amount,
            paymentMode = mode.label,
            purchaseId = purchase.id,
            purchaseNo = purchase.purchaseNo,
            payTo = purchase.supplierName,
            fromAccountId = if (fromAccountId != 0L) fromAccountId else ensureModeAccount(mode.label)
        )
        val id = expenseDao.insert(expense)
        val newPaid = (purchase.paidAmount + amount).coerceAtMost(purchase.grandTotal)
        purchaseDao.updateHeader(purchase.copy(paidAmount = newPaid))
        return expense.copy(id = id)
    }

    suspend fun updateExpense(expense: Expense, description: String, amount: Double, mode: PayMode) {
        if (expense.purchaseId > 0) {
            purchaseDao.byId(expense.purchaseId)?.let { pur ->
                val adjusted = (pur.paidAmount - expense.amount + amount).coerceIn(0.0, pur.grandTotal)
                purchaseDao.updateHeader(pur.copy(paidAmount = adjusted))
            }
        }
        expenseDao.update(expense.copy(description = description.trim(), amount = amount, paymentMode = mode.label))
    }

    suspend fun deleteExpense(expense: Expense) {
        if (expense.purchaseId > 0) {
            purchaseDao.byId(expense.purchaseId)?.let { pur ->
                val adjusted = (pur.paidAmount - expense.amount).coerceAtLeast(0.0)
                purchaseDao.updateHeader(pur.copy(paidAmount = adjusted))
            }
        }
        expenseDao.delete(expense)
    }

    // ---- suppliers ----
    suspend fun addSupplier(name: String, phone: String, address: String, gstin: String = ""): Supplier {
        val s = Supplier(name = name.trim(), phone = phone.trim(), address = address.trim(), gstin = gstin.trim())
        val id = supplierDao.insert(s)
        ensureSupplierHead(name)   // create the supplier's ledger head immediately
        return s.copy(id = id)
    }

    /** One-time: make sure every existing customer/supplier has a ledger head. */
    suspend fun syncPartyHeads() {
        customerDao.all().filter { !it.isDefault }.forEach { ensureCustomerHead(it.name) }
        supplierDao.all().filter { !it.isDefault }.forEach { ensureSupplierHead(it.name) }
    }

    // ---- VAT / tax report data ----
    suspend fun billsAll(): List<Bill> = billDao.all()
    suspend fun purchasesAll(): List<Purchase> = purchaseDao.all()
    suspend fun saleTaxLines(): List<TaxLineInfo> = billDao.taxLines()
    suspend fun purchaseTaxLines(): List<TaxLineInfo> = purchaseDao.taxLines()

    suspend fun updateSupplier(supplier: Supplier) = supplierDao.update(supplier)

    suspend fun deleteSupplier(supplier: Supplier): Result<Unit> {
        if (supplier.isDefault) return Result.failure(IllegalStateException("Cannot delete the default supplier"))
        if (purchaseDao.countForSupplier(supplier.id, supplier.name) > 0)
            return Result.failure(IllegalStateException("Supplier has purchases"))
        supplierDao.delete(supplier)
        return Result.success(Unit)
    }

    // ---- purchases ----
    suspend fun nextPurchaseNo(): String =
        "PUR-" + (purchaseDao.localCount() + 1).toString().padStart(4, '0')

    suspend fun savePurchase(purchase: Purchase, lines: List<PurchaseItem>): Long =
        purchaseDao.savePurchase(purchase, lines)

    suspend fun updatePurchase(purchase: Purchase, lines: List<PurchaseItem>) =
        purchaseDao.updatePurchase(purchase, lines)

    suspend fun deletePurchase(purchase: Purchase) = purchaseDao.deletePurchase(purchase)

    suspend fun purchaseById(id: Long): Purchase? = purchaseDao.byId(id)

    suspend fun purchaseLinesFor(id: Long): List<PurchaseItem> = purchaseDao.linesFor(id)

    // ---- data export / import (invoices, customers, items — NOT users) ----

    /** Serialises this device's own data to a JSON string, stamped with [sourceLabel]. */
    suspend fun exportJson(sourceLabel: String): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("source", sourceLabel)
        root.put("exportedAt", System.currentTimeMillis())

        val custArr = JSONArray()
        customerDao.all().filter { !it.isDefault }.forEach { c ->
            custArr.put(JSONObject().put("name", c.name).put("phone", c.phone).put("address", c.address))
        }
        root.put("customers", custArr)

        val itemArr = JSONArray()
        itemDao.all().forEach { i ->
            itemArr.put(JSONObject().put("name", i.name).put("price", i.price).put("taxPercent", i.taxPercent))
        }
        root.put("items", itemArr)

        val billArr = JSONArray()
        billDao.all().filter { it.source.isEmpty() }.forEach { b ->
            val bo = JSONObject()
                .put("billNo", b.billNo)
                .put("dateMillis", b.dateMillis)
                .put("customerName", b.customerName)
                .put("paymentMethod", b.paymentMethod)
                .put("subTotal", b.subTotal)
                .put("taxTotal", b.taxTotal)
                .put("additionalCharge", b.additionalCharge)
                .put("discount", b.discount)
                .put("grandTotal", b.grandTotal)
                .put("paidAmount", b.paidAmount)
            val lineArr = JSONArray()
            billDao.linesFor(b.id).forEach { l ->
                lineArr.put(
                    JSONObject().put("name", l.name).put("qty", l.qty)
                        .put("price", l.price).put("taxPercent", l.taxPercent).put("lineTotal", l.lineTotal)
                )
            }
            bo.put("lines", lineArr)
            billArr.put(bo)
        }
        root.put("bills", billArr)

        val receiptArr = JSONArray()
        receiptDao.all().filter { it.source.isEmpty() }.forEach { r ->
            receiptArr.put(
                JSONObject().put("receiptNo", r.receiptNo).put("billNo", r.billNo)
                    .put("customerName", r.customerName).put("dateMillis", r.dateMillis)
                    .put("amount", r.amount).put("paymentMode", r.paymentMode)
            )
        }
        root.put("receipts", receiptArr)

        val expenseArr = JSONArray()
        expenseDao.all().filter { it.source.isEmpty() }.forEach { e ->
            expenseArr.put(
                JSONObject().put("voucherNo", e.voucherNo).put("dateMillis", e.dateMillis)
                    .put("description", e.description).put("amount", e.amount)
                    .put("paymentMode", e.paymentMode)
            )
        }
        root.put("expenses", expenseArr)

        return root.toString()
    }

    /**
     * Merges an exported JSON into this database. Users/roles are never touched.
     * Bills are deduped by (source, billNo) so re-importing the same file is safe.
     */
    suspend fun importJson(json: String): ImportResult {
        val root = JSONObject(json)
        val source = root.optString("source", "imported").ifBlank { "imported" }

        root.optJSONArray("customers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                if (name.isNotBlank() && customerDao.byName(name) == null) {
                    customerDao.insert(
                        Customer(name = name, phone = o.optString("phone"), address = o.optString("address"))
                    )
                }
            }
        }

        root.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                if (name.isNotBlank() && itemDao.byName(name) == null) {
                    itemDao.insert(Item(name = name, price = o.optDouble("price", 0.0), taxPercent = o.optDouble("taxPercent", 0.0)))
                }
            }
        }

        var added = 0
        var skipped = 0
        root.optJSONArray("bills")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val billNo = o.optString("billNo")
                if (billNo.isBlank()) { skipped++; continue }
                if (billDao.findBySourceAndNo(source, billNo) != null) { skipped++; continue }

                val bill = Bill(
                    billNo = billNo,
                    dateMillis = o.optLong("dateMillis", System.currentTimeMillis()),
                    customerId = 0,
                    customerName = o.optString("customerName", "Cash Customer"),
                    paymentMethod = o.optString("paymentMethod", "Cash"),
                    subTotal = o.optDouble("subTotal", 0.0),
                    taxTotal = o.optDouble("taxTotal", 0.0),
                    additionalCharge = o.optDouble("additionalCharge", 0.0),
                    discount = o.optDouble("discount", 0.0),
                    grandTotal = o.optDouble("grandTotal", 0.0),
                    paidAmount = o.optDouble(
                        "paidAmount",
                        if (o.optString("paymentMethod") == PaymentMethod.CREDIT.label) 0.0
                        else o.optDouble("grandTotal", 0.0)
                    ),
                    source = source
                )
                val lines = mutableListOf<BillItem>()
                o.optJSONArray("lines")?.let { la ->
                    for (j in 0 until la.length()) {
                        val lo = la.getJSONObject(j)
                        lines.add(
                            BillItem(
                                billId = 0,
                                name = lo.optString("name"),
                                qty = lo.optDouble("qty", 0.0),
                                price = lo.optDouble("price", 0.0),
                                taxPercent = lo.optDouble("taxPercent", 0.0),
                                lineTotal = lo.optDouble("lineTotal", 0.0)
                            )
                        )
                    }
                }
                billDao.saveBill(bill, lines)
                added++
            }
        }

        var receiptsAdded = 0
        root.optJSONArray("receipts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val no = o.optString("receiptNo")
                if (no.isBlank() || receiptDao.findBySourceAndNo(source, no) != null) continue
                receiptDao.insert(
                    Receipt(
                        receiptNo = no,
                        billId = 0,
                        billNo = o.optString("billNo"),
                        customerName = o.optString("customerName"),
                        dateMillis = o.optLong("dateMillis", System.currentTimeMillis()),
                        amount = o.optDouble("amount", 0.0),
                        paymentMode = o.optString("paymentMode", "Cash"),
                        source = source
                    )
                )
                receiptsAdded++
            }
        }

        var expensesAdded = 0
        root.optJSONArray("expenses")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val no = o.optString("voucherNo")
                if (no.isBlank() || expenseDao.findBySourceAndNo(source, no) != null) continue
                expenseDao.insert(
                    Expense(
                        voucherNo = no,
                        dateMillis = o.optLong("dateMillis", System.currentTimeMillis()),
                        description = o.optString("description"),
                        amount = o.optDouble("amount", 0.0),
                        paymentMode = o.optString("paymentMode", "Cash"),
                        source = source
                    )
                )
                expensesAdded++
            }
        }

        return ImportResult(added, skipped, receiptsAdded, expensesAdded, source)
    }
}
