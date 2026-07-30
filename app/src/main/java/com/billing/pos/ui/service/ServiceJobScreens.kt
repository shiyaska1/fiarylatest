package com.billing.pos.ui.service

import android.app.Application
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.AppPrefs
import com.billing.pos.data.Customer
import com.billing.pos.data.DownloadSaver
import com.billing.pos.data.ServiceAttachmentStore
import com.billing.pos.data.ServiceEmployee
import com.billing.pos.data.ServiceJobAttachment
import com.billing.pos.data.ServiceJobCard
import com.billing.pos.data.ServiceJobLine
import com.billing.pos.data.ServiceJobMaster
import com.billing.pos.data.ServiceRepository
import com.billing.pos.data.ServiceStatus
import com.billing.pos.data.ServiceType
import com.billing.pos.pdf.DocumentPdf
import com.billing.pos.print.ThermalPrinter
import com.billing.pos.pdf.PdfDoc
import com.billing.pos.pdf.PdfLine
import com.billing.pos.ui.billing.BillPrefillLine
import com.billing.pos.ui.billing.NewCustomerDialog
import com.billing.pos.ui.billing.OrderToBillLink
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.common.CustomerPickField
import com.billing.pos.ui.common.DocumentPdfAction
import com.billing.pos.ui.common.rememberPdfDownloader
import com.billing.pos.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** One job being edited on the card. [uid] keys the row; ids are re-issued on save. */
data class JobLineDraft(
    val uid: Long,
    val jobId: Long,
    val name: String,
    val price: Double,
    val status: String = "Pending",
    val expectedMillis: Long = 0
)

class ServiceJobViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceRepository(app)

    val customers: StateFlow<List<Customer>> = repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val types: StateFlow<List<ServiceType>> = repo.types.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val statuses: StateFlow<List<ServiceStatus>> = repo.statuses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val jobsMaster: StateFlow<List<ServiceJobMaster>> = repo.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val employees: StateFlow<List<ServiceEmployee>> = repo.employees.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val models: StateFlow<List<com.billing.pos.data.ServiceModel>> = repo.models.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val cards: StateFlow<List<ServiceJobCard>> = repo.cards.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // form state
    var jobNo by mutableStateOf("JC-0001"); private set
    var cardName by mutableStateOf("")
    var dateMillis by mutableStateOf(System.currentTimeMillis())
    var selectedCustomer by mutableStateOf<Customer?>(null)
    var customerPhone by mutableStateOf("")
    var typeId by mutableStateOf(0L)
    var typeName by mutableStateOf("")
    var modelId by mutableStateOf(0L)
    var modelName by mutableStateOf("")
    var employeeId by mutableStateOf(0L)
    var employeeName by mutableStateOf("")
    var priority by mutableStateOf("Medium")
    var status by mutableStateOf("Pending")
    var expectedMillis by mutableStateOf(0L)
    var advanceText by mutableStateOf("")
    var otherChargesText by mutableStateOf("")
    var otherChargesNote by mutableStateOf("")
    var remarks by mutableStateOf("")
    val lines: SnapshotStateList<JobLineDraft> = mutableStateListOf()
    val attachments: SnapshotStateList<ServiceJobAttachment> = mutableStateListOf()
    var editingId by mutableStateOf<Long?>(null); private set
    private var loaded = false
    private var uidSeq = 0L

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    init {
        viewModelScope.launch { repo.ensureDefaults(); jobNo = repo.nextJobNo() }
        // Walk-in work is mostly cash: start on the default cash customer.
        viewModelScope.launch {
            customers.collect { list ->
                if (selectedCustomer == null) list.firstOrNull { it.isDefault }?.let { pickCustomer(it) }
            }
        }
    }

    // Rejected jobs stay on the card for the record but don't count toward the money.
    val jobsTotal get() = lines.filter { !it.status.equals("Rejected", true) }.sumOf { it.price }
    val otherCharges get() = otherChargesText.toDoubleOrNull() ?: 0.0
    val advance get() = advanceText.toDoubleOrNull() ?: 0.0
    val grandTotal get() = jobsTotal + otherCharges
    val balance get() = grandTotal - advance

    fun addLine(job: ServiceJobMaster) {
        lines.add(JobLineDraft(uid = ++uidSeq, jobId = job.id, name = job.name, price = job.price, expectedMillis = expectedMillis))
    }
    fun setLinePrice(i: Int, p: Double) { val l = lines.getOrNull(i) ?: return; lines[i] = l.copy(price = p) }
    fun setLineStatus(i: Int, s: String) { val l = lines.getOrNull(i) ?: return; lines[i] = l.copy(status = s) }
    fun setLineExpected(i: Int, m: Long) { val l = lines.getOrNull(i) ?: return; lines[i] = l.copy(expectedMillis = m) }
    fun removeLine(i: Int) { lines.removeAt(i) }

    fun saveJobMaster(j: ServiceJobMaster) { viewModelScope.launch { repo.saveJob(j) } }
    fun addServiceType(name: String) {
        viewModelScope.launch {
            val id = repo.saveType(com.billing.pos.data.ServiceType(name = name))
            if (id > 0) { typeId = id; typeName = name.trim() }
        }
    }
    /** Adds the typed model to the master and selects it. */
    fun addModel(name: String) {
        viewModelScope.launch {
            val id = repo.saveModel(name)
            if (id > 0) { modelId = id; modelName = name.trim() }
        }
    }
    fun saveEmployee(e: ServiceEmployee) {
        viewModelScope.launch {
            val id = repo.saveEmployee(e)
            if (id > 0) { employeeId = id; employeeName = e.name.trim() }
        }
    }

    /** Selecting a customer fills the phone field; it stays editable for one-off numbers. */
    fun pickCustomer(c: Customer) {
        selectedCustomer = c
        customerPhone = c.phone
    }

    fun addCustomer(name: String, phone: String, address: String, type: String = "General", onCreated: () -> Unit) {
        if (name.isBlank()) { message.value = "Enter customer name"; return }
        viewModelScope.launch {
            pickCustomer(repo.addCustomer(name, phone, address, type))
            message.value = "Customer added"
            onCreated()
        }
    }

    fun load(id: Long) {
        if (loaded || id <= 0) { loaded = true; return }
        loaded = true
        viewModelScope.launch {
            val c = repo.cardById(id) ?: return@launch
            editingId = c.id; jobNo = c.jobNo; cardName = c.name; dateMillis = c.dateMillis
            typeId = c.typeId; typeName = c.typeName; status = c.status; expectedMillis = c.expectedMillis
            modelId = c.modelId; modelName = c.modelName
            employeeId = c.employeeId; employeeName = c.employeeName; priority = c.priority
            advanceText = if (c.advance != 0.0) Format.money(c.advance) else ""
            otherChargesText = if (c.otherCharges != 0.0) Format.money(c.otherCharges) else ""
            otherChargesNote = c.otherChargesNote; remarks = c.remarks
            selectedCustomer = customers.value.firstOrNull { it.id == c.customerId }
                ?: Customer(c.customerId, c.customerName, c.customerPhone)
            customerPhone = c.customerPhone
            lines.clear()
            repo.linesFor(id).forEach { lines.add(JobLineDraft(++uidSeq, it.jobId, it.name, it.price, it.status, it.expectedMillis)) }
            attachments.clear()
            attachments.addAll(repo.attachmentsFor(id))
        }
    }

    fun newCard() {
        cardName = ""; lines.clear(); attachments.clear()
        advanceText = ""; otherChargesText = ""; otherChargesNote = ""; remarks = ""
        typeId = 0; typeName = ""; modelId = 0; modelName = ""; status = "Pending"; expectedMillis = 0
        employeeId = 0; employeeName = ""; customerPhone = ""; priority = "Medium"
        dateMillis = System.currentTimeMillis(); editingId = null; selectedCustomer = null
        viewModelScope.launch { jobNo = repo.nextJobNo() }
    }

    fun save(onDone: () -> Unit) {
        if (selectedCustomer == null) { message.value = "Select a customer"; return }
        // A card may be opened before any job is agreed — jobs are not mandatory.
        viewModelScope.launch {
            // A different number typed against the default cash customer is a real person:
            // switch to (or create) "Cash Customer - <number>" so the card is identifiable.
            var customer = selectedCustomer!!
            val phone = customerPhone.trim()
            if (customer.isDefault && phone.isNotBlank() && phone != customer.phone.trim()) {
                customer = repo.resolveCashCustomer(phone)
                selectedCustomer = customer
                message.value = "Customer: ${customer.name}"
            }
            // A card whose jobs are all completed or rejected is itself completed.
            val allDone = lines.isNotEmpty() &&
                lines.all { it.status.equals("Completed", true) || it.status.equals("Rejected", true) }
            if (allDone) status = "Completed"
            if (modelName.isNotBlank() && modelId == 0L) modelId = repo.saveModel(modelName)
            val c = ServiceJobCard(
                id = editingId ?: 0, jobNo = jobNo, name = cardName.trim(), dateMillis = dateMillis,
                customerId = customer.id, customerName = customer.name, customerPhone = phone,
                typeId = typeId, typeName = typeName, modelId = modelId, modelName = modelName.trim(),
                employeeId = employeeId, employeeName = employeeName,
                status = status, priority = priority, expectedMillis = expectedMillis,
                jobsTotal = jobsTotal, otherCharges = otherCharges, otherChargesNote = otherChargesNote.trim(),
                grandTotal = grandTotal, advance = advance, remarks = remarks.trim()
            )
            val ls = lines.map { ServiceJobLine(0, c.id, it.jobId, it.name, it.price, it.status, it.expectedMillis) }
            val id: Long
            if (editingId != null) { repo.updateCard(c, ls); id = c.id; message.value = "Job card $jobNo updated" }
            else { id = repo.saveCard(c, ls); editingId = id; message.value = "Job card $jobNo saved" + if (advance > 0) " • advance receipt posted" else "" }
            repo.replaceAttachments(id, attachments.toList())
            onDone()
        }
    }

    fun delete(c: ServiceJobCard) { viewModelScope.launch { repo.deleteCard(c); message.value = "Job card ${c.jobNo} deleted" } }
    fun setCardStatus(c: ServiceJobCard, s: String) { viewModelScope.launch { repo.setCardStatus(c.id, s); message.value = "${c.jobNo}: $s" } }
    fun setCardExpected(c: ServiceJobCard, millis: Long) {
        viewModelScope.launch { repo.setCardExpected(c.id, millis); message.value = "${c.jobNo}: expected ${Format.date(millis)}" }
    }
    fun updateLine(l: ServiceJobLine) { viewModelScope.launch { repo.updateLine(l) } }

    suspend fun linesForCard(id: Long): List<ServiceJobLine> = repo.linesFor(id)

    /** The current form as an A4 document. */
    fun pdfDocFromForm(): PdfDoc = pdfDoc(
        jobNo, dateMillis, selectedCustomer?.name ?: "", customerPhone, cardName, typeName,
        modelName, employeeName, status, priority, expectedMillis,
        lines.map { Triple(it.name, it.price, it.status to it.expectedMillis) },
        jobsTotal, otherCharges, grandTotal, advance, remarks
    )

    /** A saved card as an A4 document, lines fetched from the DB. */
    suspend fun pdfDocFor(c: ServiceJobCard): PdfDoc = pdfDoc(
        c.jobNo, c.dateMillis, c.customerName, c.customerPhone, c.name, c.typeName,
        c.modelName, c.employeeName, c.status, c.priority, c.expectedMillis,
        repo.linesFor(c.id).map { Triple(it.name, it.price, it.status to it.expectedMillis) },
        c.jobsTotal, c.otherCharges, c.grandTotal, c.advance, c.remarks
    )

    private fun pdfDoc(
        no: String, date: Long, customer: String, phone: String, card: String, type: String,
        model: String, employee: String, cardStatus: String, prio: String, expected: Long,
        jobLines: List<Triple<String, Double, Pair<String, Long>>>,
        jobs: Double, other: Double, grand: Double, adv: Double, rem: String
    ): PdfDoc {
        // One detail per line — a single crowded meta row is unreadable on paper.
        val meta = buildList {
            if (card.isNotBlank()) add("Job card: $card")
            if (phone.isNotBlank()) add("Phone: $phone")
            if (type.isNotBlank()) add("Service type: $type")
            if (model.isNotBlank()) add("Model: $model")
            if (employee.isNotBlank()) add("Employee: $employee")
            if (!prio.equals("Medium", true)) add("Priority: $prio")
            if (expected > 0) add("Expected: ${Format.date(expected)}")
        }
        return PdfDoc(
            docTitle = "JOB CARD", docNo = no, dateMillis = date,
            partyLabel = "Customer", partyName = customer, metaLines = meta,
            // Jobs print one per row, no status; a rejected job stays listed for the
            // record but contributes nothing to the amount column.
            lines = jobLines.map { (name, price, st) ->
                val rejected = st.first.equals("Rejected", true)
                PdfLine(if (rejected) "$name (rejected)" else name, 1.0, price, if (rejected) 0.0 else price)
            },
            subTotal = jobs, taxTotal = 0.0, additionalCharge = other, discount = 0.0,
            grandTotal = grand, grandLabel = "TOTAL", remarks = rem,
            paid = adv, paidLabel = "Advance Received", filePrefix = "jobcard"
        )
    }

    /** The current form as a thermal slip. */
    fun thermalFromForm(): ThermalPrinter.JobCardPrint = ThermalPrinter.JobCardPrint(
        jobNo = jobNo, dateMillis = dateMillis, name = cardName.trim(),
        customerName = selectedCustomer?.name ?: "", customerPhone = customerPhone.trim(),
        modelName = modelName.trim(),
        employeeName = employeeName, typeName = typeName, status = status,
        expectedMillis = expectedMillis,
        lines = lines.map { Triple(it.name, it.price, it.status) },
        jobsTotal = jobsTotal, otherCharges = otherCharges, otherChargesNote = otherChargesNote.trim(),
        grandTotal = grandTotal, advance = advance, remarks = remarks.trim()
    )

    /** A saved card as a thermal slip, lines fetched from the DB. */
    suspend fun thermalFor(c: ServiceJobCard): ThermalPrinter.JobCardPrint = ThermalPrinter.JobCardPrint(
        jobNo = c.jobNo, dateMillis = c.dateMillis, name = c.name,
        customerName = c.customerName, customerPhone = c.customerPhone,
        modelName = c.modelName,
        employeeName = c.employeeName, typeName = c.typeName, status = c.status,
        expectedMillis = c.expectedMillis,
        lines = repo.linesFor(c.id).map { Triple(it.name, it.price, it.status) },
        jobsTotal = c.jobsTotal, otherCharges = c.otherCharges, otherChargesNote = c.otherChargesNote,
        grandTotal = c.grandTotal, advance = c.advance, remarks = c.remarks
    )
}

private fun pickDate(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = if (current > 0) current else System.currentTimeMillis() }
    DatePickerDialog(
        context,
        { _, y, m, d ->
            val cal = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            onPicked(cal.timeInMillis)
        },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
    ).show()
}

@Composable
fun statusColor(name: String) = when {
    name.equals("Completed", true) -> MaterialTheme.colorScheme.primary
    name.equals("Rejected", true) -> MaterialTheme.colorScheme.error
    name.contains("Partial", true) -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

private fun sharePdf(context: Context, doc: PdfDoc) {
    val uri = DocumentPdf.make(context, AppPrefs(context).company, doc)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share ${doc.docTitle}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ---------------------------------------------------------------- create / edit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceJobScreen(editId: Long?, onBack: () -> Unit, vm: ServiceJobViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val customers by vm.customers.collectAsStateSafe()
    val types by vm.types.collectAsStateSafe()
    val statuses by vm.statuses.collectAsStateSafe()
    val jobsMaster by vm.jobsMaster.collectAsStateSafe()
    val employees by vm.employees.collectAsStateSafe()
    val models by vm.models.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(Unit) { if (editId != null && editId > 0) vm.load(editId) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    var showJobPicker by remember { mutableStateOf(false) }
    var showNewJob by remember { mutableStateOf(false) }
    var showNewCustomer by remember { mutableStateOf(false) }
    var showNewEmployee by remember { mutableStateOf(false) }
    var showNewType by remember { mutableStateOf(false) }

    // Thermal print, same permission dance as the sales entry.
    fun doPrint() {
        val jc = vm.thermalFromForm()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ThermalPrinter.printJobCard(context, AppPrefs(context).company, jc) }
            }
            vm.message.value = if (result.isSuccess) "Printed ${jc.jobNo}"
            else result.exceptionOrNull()?.message ?: "Print failed"
        }
    }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doPrint() else vm.message.value = "Bluetooth permission denied"
    }
    fun printCard() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !ThermalPrinter.hasConnectPermission(context)) {
            btPerm.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else doPrint()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.editingId != null) "Edit ${vm.jobNo}" else "Job Card ${vm.jobNo}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { printCard() }) { Icon(Icons.Filled.Print, "Print") }
                    DocumentPdfAction(onMessage = { vm.message.value = it }) { vm.pdfDocFromForm() }
                    IconButton(onClick = { vm.newCard() }) { Icon(Icons.Filled.NoteAdd, "New") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Card name with suggestions from names already used (five at a time);
                // anything new typed simply becomes a new name.
                val cards by vm.cards.collectAsStateSafe()
                var nameMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = nameMenu, onExpandedChange = { nameMenu = it }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = vm.cardName, onValueChange = { vm.cardName = it; nameMenu = true },
                        label = { Text("Job card name") }, singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(nameMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    val nameSuggestions = cards.map { it.name }.filter { it.isNotBlank() }.distinct()
                        .filter { vm.cardName.isBlank() || it.contains(vm.cardName, true) }.take(5)
                    if (nameSuggestions.isNotEmpty()) ExposedDropdownMenu(expanded = nameMenu, onDismissRequest = { nameMenu = false }) {
                        nameSuggestions.forEach { n ->
                            DropdownMenuItem(text = { Text(n) }, onClick = { vm.cardName = n; nameMenu = false })
                        }
                    }
                }
                OutlinedButton(onClick = { pickDate(context, vm.dateMillis) { vm.dateMillis = it } }, modifier = Modifier.align(Alignment.CenterVertically)) {
                    Icon(Icons.Filled.CalendarMonth, null); Text(" ${Format.date(vm.dateMillis)}", maxLines = 1)
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CustomerPickField(
                    customers = customers,
                    selectedName = vm.selectedCustomer?.name ?: "",
                    onPick = { vm.pickCustomer(it) },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showNewCustomer = true }) { Icon(Icons.Filled.PersonAdd, "New customer") }
            }
            OutlinedTextField(
                value = vm.customerPhone, onValueChange = { vm.customerPhone = it.filter { c -> c.isDigit() } },
                label = { Text("Phone number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )

            // Model / vehicle / item being serviced: type to search the master (five
            // suggestions), or add what was typed as a new master entry.
            var modelMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = modelMenu, onExpandedChange = { modelMenu = it }, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = vm.modelName,
                    onValueChange = { vm.modelName = it; vm.modelId = 0; modelMenu = true },
                    label = { Text("Model / Vehicle / Item") },
                    placeholder = { Text("Search or type new") },
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                val matches = models.filter { vm.modelName.isBlank() || it.name.contains(vm.modelName, true) }.take(5)
                val exact = models.any { it.name.equals(vm.modelName.trim(), true) }
                ExposedDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    matches.forEach { m ->
                        DropdownMenuItem(text = { Text(m.name) }, onClick = { vm.modelId = m.id; vm.modelName = m.name; modelMenu = false })
                    }
                    if (vm.modelName.isNotBlank() && !exact) {
                        DropdownMenuItem(
                            text = { Text("Add \"${vm.modelName.trim()}\"") },
                            leadingIcon = { Icon(Icons.Filled.Add, null) },
                            onClick = { vm.addModel(vm.modelName); modelMenu = false }
                        )
                    }
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                var empMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = empMenu, onExpandedChange = { empMenu = !empMenu }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        readOnly = true, value = vm.employeeName, onValueChange = {},
                        label = { Text("Assigned employee") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(empMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = empMenu, onDismissRequest = { empMenu = false }) {
                        DropdownMenuItem(text = { Text("— none —") }, onClick = { vm.employeeId = 0; vm.employeeName = ""; empMenu = false })
                        employees.forEach { e ->
                            DropdownMenuItem(
                                text = { Text(e.name + if (e.phone.isNotBlank()) "  (${e.phone})" else "") },
                                onClick = { vm.employeeId = e.id; vm.employeeName = e.name; empMenu = false }
                            )
                        }
                    }
                }
                IconButton(onClick = { showNewEmployee = true }) { Icon(Icons.Filled.PersonAdd, "New employee") }
            }

            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var typeMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = !typeMenu }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        readOnly = true, value = vm.typeName, onValueChange = {},
                        label = { Text("Service type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        DropdownMenuItem(text = { Text("— none —") }, onClick = { vm.typeId = 0; vm.typeName = ""; typeMenu = false })
                        types.forEach { t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { vm.typeId = t.id; vm.typeName = t.name; typeMenu = false }) }
                    }
                }
                IconButton(onClick = { showNewType = true }, modifier = Modifier.align(Alignment.CenterVertically)) {
                    Icon(Icons.Filled.Add, "New service type")
                }
                var statusMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = statusMenu, onExpandedChange = { statusMenu = !statusMenu }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        readOnly = true, value = vm.status, onValueChange = {},
                        label = { Text("Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        statuses.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { vm.status = s.name; statusMenu = false }) }
                    }
                }
            }
            OutlinedButton(onClick = { pickDate(context, vm.expectedMillis) { vm.expectedMillis = it } }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Icon(Icons.Filled.CalendarMonth, null)
                Text(if (vm.expectedMillis > 0) " Expected completion: ${Format.date(vm.expectedMillis)}" else " Expected completion date", maxLines = 1)
            }

            // Priority: high jobs jump to the top of the list, coloured to match.
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Priority", style = MaterialTheme.typography.labelLarge)
                listOf("High", "Medium", "Low").forEach { p ->
                    FilterChip(
                        selected = vm.priority == p,
                        onClick = { vm.priority = p },
                        label = { Text(p, color = priorityColor(p), fontWeight = if (vm.priority == p) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            Button(onClick = { showJobPicker = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Filled.Add, null); Text("  Add job") }

            // jobs on the card
            if (vm.lines.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Text("No jobs added", color = MaterialTheme.colorScheme.outline)
                }
            } else Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(8.dp)) {
                    vm.lines.forEachIndexed { i, line ->
                        var priceText by remember(line.uid) { mutableStateOf(if (line.price != 0.0) Format.money(line.price) else "") }
                        var lineStatusMenu by remember(line.uid) { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(line.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Box {
                                    Text(
                                        line.status,
                                        color = statusColor(line.status),
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.clickable { lineStatusMenu = true }.padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                    DropdownMenu(expanded = lineStatusMenu, onDismissRequest = { lineStatusMenu = false }) {
                                        statuses.forEach { s ->
                                            DropdownMenuItem(text = { Text(s.name) }, onClick = { vm.setLineStatus(i, s.name); lineStatusMenu = false })
                                        }
                                    }
                                }
                                IconButton(onClick = { vm.removeLine(i) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = priceText,
                                    onValueChange = { v -> val f = v.filter { it.isDigit() || it == '.' }; priceText = f; vm.setLinePrice(i, f.toDoubleOrNull() ?: 0.0) },
                                    label = { Text("Price") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.width(110.dp)
                                )
                                OutlinedButton(onClick = { pickDate(context, line.expectedMillis) { vm.setLineExpected(i, it) } }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp))
                                    Text(if (line.expectedMillis > 0) " ${Format.date(line.expectedMillis)}" else " Expected", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Divider(Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.otherChargesText, onValueChange = { v -> vm.otherChargesText = v.filter { it.isDigit() || it == '.' } },
                    label = { Text("Other charges") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(130.dp)
                )
                OutlinedTextField(
                    value = vm.otherChargesNote, onValueChange = { vm.otherChargesNote = it },
                    label = { Text("Charge note") }, singleLine = true, modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = vm.advanceText, onValueChange = { v -> vm.advanceText = v.filter { it.isDigit() || it == '.' } },
                label = { Text("Advance received") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )

            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Jobs total"); Text(Format.rupee(vm.jobsTotal))
                    }
                    if (vm.otherCharges != 0.0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Other charges"); Text(Format.rupee(vm.otherCharges))
                    }
                    Divider(Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("TOTAL", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(Format.rupee(vm.grandTotal), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                    if (vm.advance != 0.0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Advance"); Text(Format.rupee(vm.advance))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Balance", fontWeight = FontWeight.SemiBold)
                            Text(Format.rupee(vm.balance), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            var drawRemarks by remember { mutableStateOf(false) }
            if (drawRemarks) {
                com.billing.pos.ui.common.HandwriteTextDialog(
                    onResult = { t -> if (t.isNotBlank()) vm.remarks = (vm.remarks.trimEnd() + " " + t).trim(); drawRemarks = false },
                    onDismiss = { drawRemarks = false }
                )
            }
            OutlinedTextField(
                value = vm.remarks, onValueChange = { vm.remarks = it },
                label = { Text("Remarks") },
                trailingIcon = { IconButton(onClick = { drawRemarks = true }) { Icon(Icons.Filled.Gesture, "Write remarks") } },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )

            ServiceJobAttachments(vm.attachments, onMessage = { vm.message.value = it })

            Button(onClick = { vm.save { } }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Save job card") }
        }
    }

    if (showJobPicker) {
        JobPickerDialog(
            jobs = jobsMaster,
            onDismiss = { showJobPicker = false },
            onPick = { vm.addLine(it); showJobPicker = false },
            onNewJob = { showJobPicker = false; showNewJob = true }
        )
    }
    if (showNewJob) JobMasterDialog(null, onDismiss = { showNewJob = false }, onSave = {
        vm.saveJobMaster(it); vm.addLine(it); showNewJob = false
    })
    if (showNewCustomer) {
        NewCustomerDialog(
            onDismiss = { showNewCustomer = false },
            onSave = { n, p, a, t -> vm.addCustomer(n, p, a, t) { showNewCustomer = false } }
        )
    }
    if (showNewEmployee) {
        EmployeeDialog(null, onDismiss = { showNewEmployee = false }, onSave = {
            vm.saveEmployee(it); showNewEmployee = false
        })
    }
    if (showNewType) {
        NameEditDialog(title = "New service type", initial = "", onDismiss = { showNewType = false }) {
            vm.addServiceType(it); showNewType = false
        }
    }
}

@Composable
fun priorityColor(p: String) = when {
    p.equals("High", true) -> MaterialTheme.colorScheme.error
    p.equals("Medium", true) -> androidx.compose.ui.graphics.Color(0xFFE65100)
    else -> androidx.compose.ui.graphics.Color.Unspecified
}

private fun priorityRank(p: String) = when {
    p.equals("High", true) -> 0
    p.equals("Medium", true) -> 1
    else -> 2
}

/** Pick a job from the master, or jump to creating a new one. */
@Composable
private fun JobPickerDialog(
    jobs: List<ServiceJobMaster>,
    onDismiss: () -> Unit,
    onPick: (ServiceJobMaster) -> Unit,
    onNewJob: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, jobs) {
        if (query.isBlank()) jobs else jobs.filter { it.name.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add job") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 6.dp)) {
                    items(filtered, key = { it.id }) { j ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(j) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(j.name, Modifier.weight(1f))
                            Text(Format.rupee(j.price), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Divider()
                    }
                    if (filtered.isEmpty()) item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            Text("No jobs in the master yet", color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNewJob) { Icon(Icons.Filled.Add, null); Text(" New job") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Camera / gallery / file attachments on a job card; rows open, download, share, remove. */
@Composable
private fun ServiceJobAttachments(
    attachments: SnapshotStateList<ServiceJobAttachment>,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var viewing by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    fun addFrom(uri: android.net.Uri?) {
        if (uri == null) return
        ServiceAttachmentStore.copyIn(context, uri)?.let { attachments.add(it) }
    }

    val camera = com.billing.pos.ocr.rememberImageCamera { uri -> addFrom(uri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { addFrom(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { addFrom(it) }

    viewing?.let { (paths, start) ->
        com.billing.pos.ui.common.ImageViewerDialog(paths = paths, startIndex = start, onDismiss = { viewing = null })
    }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("Attachments", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { camera() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PhotoCamera, "Camera", Modifier.size(18.dp))
            }
            OutlinedButton(
                onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.weight(1f)
            ) { Icon(Icons.Filled.PhotoLibrary, "Gallery", Modifier.size(18.dp)) }
            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.UploadFile, "File", Modifier.size(18.dp))
            }
        }
        attachments.forEachIndexed { i, att ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    att.name,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).clickable {
                        if (att.isImage) {
                            val images = attachments.filter { it.isImage }
                            viewing = images.map { it.path } to images.indexOf(att).coerceAtLeast(0)
                        } else ServiceAttachmentStore.open(context, att)
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                IconButton(onClick = {
                    val f = File(att.path)
                    onMessage(
                        if (f.exists() && DownloadSaver.save(context, f, att.name, att.mime.ifBlank { "*/*" }))
                            "Saved to Downloads: ${att.name}" else "Could not save the file"
                    )
                }) { Icon(Icons.Filled.Download, "Download", Modifier.size(20.dp)) }
                IconButton(onClick = {
                    runCatching {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = att.mime.ifBlank { "*/*" }
                            putExtra(Intent.EXTRA_STREAM, ServiceAttachmentStore.uriFor(context, att))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(Intent.createChooser(send, "Share attachment").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Icon(Icons.Filled.Share, "Share", Modifier.size(20.dp)) }
                IconButton(onClick = { attachments.removeAt(i) }) {
                    Icon(Icons.Filled.Delete, "Remove", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- list

private fun jcStartOfDay(m: Long) = Calendar.getInstance().apply {
    timeInMillis = m; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun jcEndOfDay(m: Long) = Calendar.getInstance().apply {
    timeInMillis = m; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceJobListScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onConvertToBill: () -> Unit,
    vm: ServiceJobViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val cards by vm.cards.collectAsStateSafe()
    val statuses by vm.statuses.collectAsStateSafe()
    val types by vm.types.collectAsStateSafe()
    val models by vm.models.collectAsStateSafe()
    val message by vm.message.collectAsStateSafe()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    val downloadPdf = rememberPdfDownloader { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    // Thermal print from the list; permission asked once, then the queued card prints.
    var printQueued by remember { mutableStateOf<ServiceJobCard?>(null) }
    fun doPrint(c: ServiceJobCard) {
        scope.launch {
            val jc = vm.thermalFor(c)
            val result = withContext(Dispatchers.IO) {
                runCatching { ThermalPrinter.printJobCard(context, AppPrefs(context).company, jc) }
            }
            vm.message.value = if (result.isSuccess) "Printed ${c.jobNo}"
            else result.exceptionOrNull()?.message ?: "Print failed"
        }
    }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val c = printQueued; printQueued = null
        if (granted && c != null) doPrint(c) else if (!granted) vm.message.value = "Bluetooth permission denied"
    }
    fun requestPrint(c: ServiceJobCard) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !ThermalPrinter.hasConnectPermission(context)) {
            printQueued = c; btPerm.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else doPrint(c)
    }

    // Filters: text (job no / card name / customer / mobile), date range, status, service type.
    var query by remember { mutableStateOf("") }
    var fromMillis by remember { mutableStateOf(Calendar.getInstance().apply { add(Calendar.MONTH, -3) }.timeInMillis) }
    var toMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var statusFilter by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("") }
    var modelFilter by remember { mutableStateOf("") }
    var deleteFor by remember { mutableStateOf<ServiceJobCard?>(null) }
    var jobsFor by remember { mutableStateOf<ServiceJobCard?>(null) }

    // High priority floats to the top, newest first within each priority.
    val filtered = cards.filter { c ->
        (query.isBlank() || c.jobNo.contains(query, true) || c.name.contains(query, true) ||
            c.customerName.contains(query, true) || c.customerPhone.contains(query) ||
            c.modelName.contains(query, true)) &&
            (statusFilter.isBlank() || c.status.equals(statusFilter, true)) &&
            (typeFilter.isBlank() || c.typeName.equals(typeFilter, true)) &&
            (modelFilter.isBlank() || c.modelName.equals(modelFilter, true)) &&
            c.dateMillis >= jcStartOfDay(fromMillis) && c.dateMillis <= jcEndOfDay(toMillis)
    }.sortedWith(compareBy({ priorityRank(it.priority) }, { -it.dateMillis }))

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Job Cards") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New") } }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search job no / name / customer / mobile") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(context, fromMillis) { fromMillis = it } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp)); Text(" ${Format.date(fromMillis)}", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = { pickDate(context, toMillis) { toMillis = it } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp)); Text(" ${Format.date(toMillis)}", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var statusMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = statusMenu, onExpandedChange = { statusMenu = !statusMenu }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        readOnly = true, value = statusFilter.ifBlank { "All statuses" }, onValueChange = {},
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        DropdownMenuItem(text = { Text("All statuses") }, onClick = { statusFilter = ""; statusMenu = false })
                        statuses.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { statusFilter = s.name; statusMenu = false }) }
                    }
                }
                var typeMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = !typeMenu }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        readOnly = true, value = typeFilter.ifBlank { "All types" }, onValueChange = {},
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        DropdownMenuItem(text = { Text("All types") }, onClick = { typeFilter = ""; typeMenu = false })
                        types.forEach { t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { typeFilter = t.name; typeMenu = false }) }
                    }
                }
                var modelMenu by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = modelMenu, onExpandedChange = { modelMenu = !modelMenu }, modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        readOnly = true, value = modelFilter.ifBlank { "All models" }, onValueChange = {},
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        DropdownMenuItem(text = { Text("All models") }, onClick = { modelFilter = ""; modelMenu = false })
                        models.forEach { m -> DropdownMenuItem(text = { Text(m.name) }, onClick = { modelFilter = m.name; modelMenu = false }) }
                    }
                }
            }
            Divider(Modifier.padding(top = 6.dp))

            if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No job cards", color = MaterialTheme.colorScheme.outline)
            }
            else LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { c ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.jobNo + if (c.name.isNotBlank()) "  ${c.name}" else "",
                                Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1,
                                color = priorityColor(c.priority)
                            )
                            // Tap the status to move the card along without opening it.
                            var statusMenu by remember(c.id) { mutableStateOf(false) }
                            Box {
                                Text(
                                    c.status,
                                    color = statusColor(c.status),
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.clickable { statusMenu = true }.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                                DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                                    statuses.forEach { s ->
                                        DropdownMenuItem(text = { Text(s.name) }, onClick = { vm.setCardStatus(c, s.name); statusMenu = false })
                                    }
                                }
                            }
                        }
                        Text(
                            listOfNotNull(
                                c.customerName.ifBlank { null }, c.typeName.ifBlank { null },
                                c.modelName.ifBlank { null }, c.employeeName.ifBlank { null },
                                Format.date(c.dateMillis),
                                if (c.expectedMillis > 0) "exp ${Format.date(c.expectedMillis)}" else null
                            ).joinToString("  •  "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Adv ${Format.rupee(c.advance)}  •  Bal ${Format.rupee(c.balance)}",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold
                            )
                            if (c.customerPhone.isNotBlank()) {
                                IconButton(onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${c.customerPhone}"))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }) { Icon(Icons.Filled.Call, "Call customer", Modifier.size(20.dp)) }
                            }
                            IconButton(onClick = { jobsFor = c }) {
                                Icon(Icons.AutoMirrored.Filled.ListAlt, "Jobs / expected date", Modifier.size(20.dp))
                            }
                            var pdfMenu by remember(c.id) { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { pdfMenu = true }) { Icon(Icons.Filled.PictureAsPdf, "PDF", Modifier.size(20.dp)) }
                                DropdownMenu(expanded = pdfMenu, onDismissRequest = { pdfMenu = false }) {
                                    DropdownMenuItem(text = { Text("Print (thermal)") }, onClick = {
                                        pdfMenu = false
                                        requestPrint(c)
                                    })
                                    DropdownMenuItem(text = { Text("Share / Print PDF") }, onClick = {
                                        pdfMenu = false
                                        scope.launch { sharePdf(context, vm.pdfDocFor(c)) }
                                    })
                                    DropdownMenuItem(text = { Text("Save to Downloads") }, onClick = {
                                        pdfMenu = false
                                        scope.launch {
                                            val doc = vm.pdfDocFor(c)
                                            downloadPdf { DocumentPdf.file(context, AppPrefs(context).company, doc) }
                                        }
                                    })
                                }
                            }
                            IconButton(onClick = {
                                // Hand the card's jobs to a new sales bill.
                                scope.launch {
                                    val lines = vm.linesForCard(c.id).map { BillPrefillLine(0, it.name, 1.0, it.price, "") }
                                    val all = if (c.otherCharges != 0.0)
                                        lines + BillPrefillLine(0, c.otherChargesNote.ifBlank { "Other charges" }, 1.0, c.otherCharges, "")
                                    else lines
                                    if (all.isEmpty()) { vm.message.value = "No jobs on this card" }
                                    else { OrderToBillLink.set(c.customerId, c.customerName, all); onConvertToBill() }
                                }
                            }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Convert to bill", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                            IconButton(onClick = { deleteFor = c }) { Icon(Icons.Filled.Delete, "Delete", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                    Divider()
                }
            }
        }
    }

    deleteFor?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete ${c.jobNo}?") },
            confirmButton = { TextButton(onClick = { vm.delete(c); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
    jobsFor?.let { c -> JobsQuickDialog(card = c, statuses = statuses.map { it.name }, vm = vm, onDismiss = { jobsFor = null }) }
}

/**
 * Job-wise updates without opening the card: each job's status and expected date,
 * plus the card's own expected date. Changes save immediately; totals and the card
 * status refresh themselves (all jobs completed/rejected → card Completed).
 */
@Composable
private fun JobsQuickDialog(
    card: ServiceJobCard,
    statuses: List<String>,
    vm: ServiceJobViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var dlgLines by remember(card.id) { mutableStateOf<List<ServiceJobLine>>(emptyList()) }
    var expected by remember(card.id) { mutableStateOf(card.expectedMillis) }
    LaunchedEffect(card.id) { dlgLines = vm.linesForCard(card.id) }

    fun change(updated: ServiceJobLine) {
        dlgLines = dlgLines.map { if (it.id == updated.id) updated else it }
        vm.updateLine(updated)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${card.jobNo} — jobs") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { pickDate(context, expected) { m -> expected = m; vm.setCardExpected(card, m) } }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp))
                    Text(if (expected > 0) " Card expected: ${Format.date(expected)}" else " Set card expected date", maxLines = 1)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 6.dp)) {
                    items(dlgLines, key = { it.id }) { l ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    Format.rupee(l.price) + if (l.expectedMillis > 0) "  •  by ${Format.date(l.expectedMillis)}" else "",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline
                                )
                            }
                            var menu by remember(l.id) { mutableStateOf(false) }
                            Box {
                                Text(
                                    l.status,
                                    color = statusColor(l.status),
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.clickable { menu = true }.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    statuses.forEach { s ->
                                        DropdownMenuItem(text = { Text(s) }, onClick = { change(l.copy(status = s)); menu = false })
                                    }
                                }
                            }
                            IconButton(onClick = { pickDate(context, l.expectedMillis) { m -> change(l.copy(expectedMillis = m)) } }) {
                                Icon(Icons.Filled.CalendarMonth, "Expected date", Modifier.size(18.dp))
                            }
                        }
                        Divider()
                    }
                    if (dlgLines.isEmpty()) item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            Text("No jobs on this card", color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
