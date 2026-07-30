package com.billing.pos.ui.purchase

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.billing.pos.data.Item
import com.billing.pos.data.PaymentMethod
import com.billing.pos.data.Purchase
import com.billing.pos.data.PurchaseItem
import com.billing.pos.data.Repository
import com.billing.pos.data.primaryChoice
import com.billing.pos.data.primaryCostChoice
import com.billing.pos.data.Supplier
import com.billing.pos.ui.billing.CartLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PurchaseWithItems(val purchase: Purchase, val lines: List<PurchaseItem>)

class PurchaseViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository(app)

    val suppliers: StateFlow<List<Supplier>> =
        repo.suppliers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> =
        repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** All item batches, for the purchase batch dialog (pick existing / create new). */
    val allBatches: StateFlow<List<com.billing.pos.data.ItemBatch>> =
        repo.itemBatches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var selectedSupplier by mutableStateOf<Supplier?>(null); private set
    val cart: SnapshotStateList<CartLine> = mutableStateListOf()
    var payment by mutableStateOf(PaymentMethod.CASH); private set
    var additionalChargeText by mutableStateOf(""); private set
    var discountText by mutableStateOf(""); private set
    var purchaseNo by mutableStateOf("PUR-0001"); private set
    var dateMillis by mutableStateOf(System.currentTimeMillis()); private set
    /** When on, this purchase is booked against an LPO whose stock was already received (VAT only). */
    var againstLpo by mutableStateOf(false)
    var lpoNo by mutableStateOf("")
    val lpos: StateFlow<List<com.billing.pos.data.PurchaseQuotation>> =
        repo.purchaseQuotations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Fills the cart from an LPO's lines (VAT booking; no stock). */
    fun loadFromLpo(lpo: com.billing.pos.data.PurchaseQuotation) {
        lpoNo = lpo.lpoNo
        selectedSupplier = suppliers.value.firstOrNull { it.id == lpo.supplierId } ?: selectedSupplier
        viewModelScope.launch {
            val lines = repo.purchaseQuotationLines(lpo.id)
            cart.clear()
            lines.forEach {
                val itemId = items.value.firstOrNull { m -> m.name.equals(it.name, ignoreCase = true) }?.id ?: it.itemId
                cart.add(CartLine(itemId, it.name, it.price, it.taxPercent, it.qty, unit = it.unit))
            }
            dirty = true
            if (lines.isEmpty()) _message.value = "That LPO has no items"
        }
    }

    var editingId by mutableStateOf<Long?>(null); private set
    private var editingSource: String = ""
    private var editingPaidAmount: Double = 0.0
    private var editingWasCredit: Boolean = false
    private var dirty = true
    private var lastSaved: PurchaseWithItems? = null

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun consumeMessage() { _message.value = null }

    init {
        viewModelScope.launch {
            repo.ensureDefaults()
            purchaseNo = repo.nextPurchaseNo()
        }
        viewModelScope.launch {
            suppliers.collect { list ->
                if (selectedSupplier == null && list.isNotEmpty()) {
                    selectedSupplier = list.firstOrNull { it.isDefault } ?: list.first()
                }
            }
        }
    }

    val subTotal: Double get() = cart.sumOf { it.base }
    val taxTotal: Double get() = cart.sumOf { it.tax }
    val additionalCharge: Double get() = additionalChargeText.toDoubleOrNull() ?: 0.0
    val discount: Double get() = discountText.toDoubleOrNull() ?: 0.0
    val grandTotal: Double get() = subTotal + taxTotal + additionalCharge - discount

    fun selectSupplier(s: Supplier) { selectedSupplier = s; dirty = true }
    fun selectPayment(m: PaymentMethod) { payment = m; dirty = true }
    fun setAdditionalCharge(v: String) { additionalChargeText = v; dirty = true }
    fun setDiscount(v: String) { discountText = v; dirty = true }

    fun addItemToCart(item: Item) = addItemWithUnit(item, item.primaryCostChoice())

    fun addItemWithUnit(item: Item, choice: com.billing.pos.data.UnitChoice) {
        val idx = cart.indexOfFirst { it.itemId == item.id && it.unit == choice.unit && item.id != 0L }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + 1)
        else cart.add(CartLine(item.id, item.name, choice.price, item.taxPercent, 1.0, unit = choice.unit, primaryPerUnit = choice.primaryPerUnit))
        dirty = true
    }

    /** Adds a purchase line for a batch (existing or new); stock is received on save. */
    fun addBatchLine(
        item: Item, batchNo: String, expiryMillis: Long, qty: Double, price: Double,
        choice: com.billing.pos.data.UnitChoice = item.primaryCostChoice()
    ) {
        cart.add(
            CartLine(
                item.id, item.name, price, item.taxPercent, qty.takeIf { it > 0 } ?: 1.0,
                batchNo = batchNo.trim(), batchExpiry = expiryMillis,
                unit = choice.unit, primaryPerUnit = choice.primaryPerUnit
            )
        )
        dirty = true
    }

    fun addCustomLine(
        description: String, price: Double, taxPercent: Double,
        saveToMaster: Boolean = false, sellingPrice: Double = 0.0
    ) {
        val name = description.trim().ifBlank { "Item" }
        cart.add(CartLine(0, name, price, taxPercent, 1.0))
        dirty = true
        if (saveToMaster && description.isNotBlank()) {
            val masterPrice = sellingPrice.takeIf { it > 0.0 } ?: price
            viewModelScope.launch {
                if (repo.itemByName(name) == null) {
                    repo.addItem(name, masterPrice, taxPercent)
                    _message.value = "Saved \"$name\" to items"
                }
            }
        }
    }

    /** Adds items read from a photo (region OCR → review) to the purchase cart. */
    fun addOcrItemsToCart(scanned: List<com.billing.pos.ocr.ScannedItem>) {
        if (scanned.isEmpty()) { _message.value = "No items found in the photo"; return }
        viewModelScope.launch {
            var added = 0
            scanned.forEach { s ->
                val name = s.name.trim()
                if (name.isBlank() && s.price <= 0.0) return@forEach
                if (name.isBlank()) { cart.add(CartLine(0, "", s.price, 0.0, 1.0)); added++; return@forEach }
                val match = items.value.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: repo.itemByName(name)
                if (match != null) {
                    val rate = if (s.price > 0.0) s.price else match.price
                    cart.add(CartLine(match.id, match.name, rate, match.taxPercent, 1.0))
                } else {
                    val id = repo.addItem(name, s.price, 0.0)
                    cart.add(CartLine(id, name, s.price, 0.0, 1.0))
                }
                added++
            }
            dirty = true
            _message.value = "Added $added item(s) from photo"
        }
    }

    fun changeQty(index: Int, delta: Double) {
        val line = cart.getOrNull(index) ?: return
        val q = line.qty + delta
        if (q <= 0) cart.removeAt(index) else cart[index] = line.copy(qty = q)
        dirty = true
    }

    fun removeLine(index: Int) { cart.removeAt(index); dirty = true }

    fun setLinePrice(index: Int, price: Double) {
        val line = cart.getOrNull(index) ?: return
        cart[index] = line.copy(price = price)
        dirty = true
    }

    fun addSupplier(name: String, phone: String, address: String, onCreated: () -> Unit) {
        if (name.isBlank()) { _message.value = "Enter supplier name"; return }
        viewModelScope.launch {
            selectedSupplier = repo.addSupplier(name, phone, address)
            dirty = true
            _message.value = "Supplier added"
            onCreated()
        }
    }

    fun addItem(form: com.billing.pos.ui.billing.NewItemForm, addToCart: Boolean, onCreated: () -> Unit) {
        if (form.name.isBlank()) { _message.value = "Enter item name"; return }
        viewModelScope.launch {
            val id = repo.addItem(
                name = form.name, price = form.price, taxPercent = form.taxPercent,
                barcode = form.barcode, hsn = form.hsn, category = form.category,
                openingStock = form.openingStock, unit = form.unit, storeLocation = form.storeLocation,
                secondaryUnit = form.secondaryUnit, conversionFactor = form.conversionFactor,
                purchasePrice = form.purchasePrice
            )
            form.attachments.forEach { repo.addItemAttachment(it.copy(itemId = id)) }
            if (addToCart) addItemToCart(
                Item(
                    id = id, name = form.name.trim(), price = form.price, taxPercent = form.taxPercent,
                    barcode = form.barcode.trim(), hsn = form.hsn.trim(), category = form.category.trim(),
                    openingStock = form.openingStock, unit = form.unit,
                    secondaryUnit = form.secondaryUnit, conversionFactor = form.conversionFactor,
                    storeLocation = form.storeLocation.trim()
                )
            )
            _message.value = "Item added"
            onCreated()
        }
    }

    fun onBarcodeScanned(barcode: String) {
        viewModelScope.launch {
            val item = repo.itemByBarcode(barcode)
            if (item != null) { addItemToCart(item); _message.value = "Added ${item.name}" }
            else _message.value = "No item for barcode $barcode"
        }
    }

    suspend fun saveCurrent(): PurchaseWithItems? {
        val supplier = selectedSupplier
        if (supplier == null) { _message.value = "Select a supplier"; return null }
        if (cart.isEmpty()) { _message.value = "Add at least one item"; return null }
        if (!dirty && lastSaved != null) return lastSaved

        val editId = editingId
        val paid = when {
            payment == PaymentMethod.CREDIT && editId != null && editingWasCredit -> editingPaidAmount
            payment == PaymentMethod.CREDIT -> 0.0
            else -> grandTotal
        }
        // Against an LPO whose goods were already received via an MRN → book for VAT only,
        // don't add stock again.
        val addsStock = !againstLpo
        val purchase = Purchase(
            id = editId ?: 0,
            purchaseNo = purchaseNo,
            dateMillis = dateMillis,
            supplierId = supplier.id,
            supplierName = supplier.name,
            paymentMethod = payment.label,
            subTotal = subTotal,
            taxTotal = taxTotal,
            additionalCharge = additionalCharge,
            discount = discount,
            grandTotal = grandTotal,
            paidAmount = paid,
            supplierGstin = supplier.gstin,
            source = editingSource,
            stockReceived = addsStock,
            lpoNo = if (againstLpo) lpoNo else ""
        )
        val lines = cart.map {
            PurchaseItem(0, editId ?: 0, it.name, it.qty, it.price, it.taxPercent, it.total, batchNo = it.batchNo, unit = it.unit, primaryQty = it.primaryQty)
        }
        val saved: PurchaseWithItems
        if (editId != null) {
            repo.updatePurchase(purchase, lines)
            saved = PurchaseWithItems(purchase, lines)
            _message.value = "Purchase $purchaseNo updated"
        } else {
            val id = repo.savePurchase(purchase, lines)
            saved = PurchaseWithItems(purchase.copy(id = id), lines)
            // Receive batch stock for new purchases — but NOT when booked against an already-received LPO.
            if (addsStock) cart.filter { it.batchNo.isNotBlank() && it.itemId > 0 }
                .forEach { repo.receiveBatch(it.itemId, it.batchNo, it.batchExpiry, it.primaryQty) }
            _message.value = if (addsStock) "Purchase $purchaseNo saved" else "Purchase $purchaseNo saved (VAT only, stock already received)"
        }
        lastSaved = saved
        dirty = false
        return saved
    }

    fun startEditing(id: Long) {
        if (editingId == id) return
        viewModelScope.launch {
            val purchase = repo.purchaseById(id) ?: return@launch
            val lines = repo.purchaseLinesFor(id)
            editingId = purchase.id
            editingSource = purchase.source
            editingPaidAmount = purchase.paidAmount
            editingWasCredit = purchase.paymentMethod == PaymentMethod.CREDIT.label
            purchaseNo = purchase.purchaseNo
            dateMillis = purchase.dateMillis
            payment = PaymentMethod.values().firstOrNull { it.label == purchase.paymentMethod } ?: PaymentMethod.CASH
            additionalChargeText = if (purchase.additionalCharge != 0.0) purchase.additionalCharge.toString() else ""
            discountText = if (purchase.discount != 0.0) purchase.discount.toString() else ""
            selectedSupplier = suppliers.value.firstOrNull { it.id == purchase.supplierId }
                ?: Supplier(id = purchase.supplierId, name = purchase.supplierName)
            cart.clear()
            lines.forEach {
                val perUnit = if (it.primaryQty > 0 && it.qty > 0) it.primaryQty / it.qty else 1.0
                cart.add(CartLine(0, it.name, it.price, it.taxPercent, it.qty, batchNo = it.batchNo, unit = it.unit, primaryPerUnit = perUnit))
            }
            dirty = false
            lastSaved = PurchaseWithItems(purchase, lines)
        }
    }

    fun newPurchase() {
        cart.clear()
        additionalChargeText = ""
        discountText = ""
        payment = PaymentMethod.CASH
        dateMillis = System.currentTimeMillis()
        selectedSupplier = suppliers.value.firstOrNull { it.isDefault } ?: suppliers.value.firstOrNull()
        editingId = null
        editingSource = ""
        lastSaved = null
        dirty = true
        viewModelScope.launch { purchaseNo = repo.nextPurchaseNo() }
    }
}
