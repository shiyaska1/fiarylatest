package com.billing.pos.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.AssignmentReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.AssignmentReturned
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoneyOff
import androidx.compose.material.icons.filled.MoveDown
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PriceCheck
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.billing.pos.auth.Session

private data class Tile(val label: String, val icon: ImageVector, val onClick: () -> Unit, val section: String = "Transactions")

/** In Personal mode the dashboard shows only these — the everyday, non-shop tools. */
private val PERSONAL_TILES = setOf(
    "Sticky Note", "Calculator", "My Diary", "Payments", "Receipts", "Cash Book", "Backup", "Settings"
)

/** The service job-card tiles, shown for every business type except Personal. */
private val SERVICE_TILES = setOf("Job Cards", "Service Masters", "Job Status Report", "Pending Jobs", "Receipt & Payment")

/** In Bulk SMS mode the dashboard shows only the SMS tools plus the requested keep-list. */
private val BULK_SMS_TILES = setOf(
    "Send SMS", "Bulk SMS", "Attendance", "Contacts", "SMS Templates", "SMS Settings", "SMS Report",
    "Calculator", "Mobile number", "My Diary", "Poster maker",
    "Receipts", "Payments", "Cash Book", "Outstanding", "Accounts", "Journal",
    "Ledger", "Trial Balance", "Profit & Loss", "Balance Sheet",
    "Settings", "Backup"
) + SERVICE_TILES

/** In Gym mode: the fitness tools + the accounts module (as requested), nothing shop-related. */
private val GYM_TILES = setOf(
    "Members", "Fees Due", "Slots", "Calculator", "My Diary",
    "Receipts", "Payments", "Cash Book", "Outstanding", "Accounts", "Journal",
    "Ledger", "Trial Balance", "Profit & Loss", "Balance Sheet",
    "Settings", "Backup"
) + SERVICE_TILES

/** In Coaching Center mode: students/enquiry/masters + the accounts module. */
private val COACHING_TILES = setOf(
    "Students", "Coaching Masters", "Enquiries", "Attendance", "Attendance Report", "Fees Due", "Calculator", "My Diary",
    "Receipts", "Payments", "Cash Book", "Outstanding", "Accounts", "Journal",
    "Ledger", "Trial Balance", "Profit & Loss", "Balance Sheet",
    "Settings", "Backup"
) + SERVICE_TILES

private val SECTION_ORDER = listOf("Transactions", "Masters", "Accounts", "Reports")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onStickyNote: () -> Unit,
    onNewBill: () -> Unit,
    onQuickBill: () -> Unit,
    onPriceSearch: () -> Unit,
    onInvoices: () -> Unit,
    onReceipts: () -> Unit,
    onExpenses: () -> Unit,
    onCashbook: () -> Unit,
    onReports: () -> Unit,
    onCustomers: () -> Unit,
    onContacts: () -> Unit,
    onSendSms: () -> Unit,
    onBulkSms: () -> Unit,
    onAttendance: () -> Unit,
    onSmsTemplates: () -> Unit,
    onSmsReport: () -> Unit,
    onSmsSettings: () -> Unit,
    onItems: () -> Unit,
    onNewPurchase: () -> Unit,
    onPurchases: () -> Unit,
    onSuppliers: () -> Unit,
    onQuotations: () -> Unit,
    onEstimates: () -> Unit,
    onPoster: () -> Unit,
    onCalculatorToBill: () -> Unit,
    onSalesReturns: () -> Unit,
    onPurchaseReturns: () -> Unit,
    onPurchaseQuotes: () -> Unit,
    onOrders: () -> Unit,
    onOpenChart: (String) -> Unit,
    onOpenParked: (String) -> Unit,
    onLpos: () -> Unit,
    onHireInvoices: () -> Unit,
    onHireReturns: () -> Unit,
    onHireItemReport: () -> Unit,
    onHireExpiryReport: () -> Unit,
    onLabTests: () -> Unit,
    onPatients: () -> Unit,
    onLabBills: () -> Unit,
    onMaterialOut: () -> Unit,
    onMaterialReceipt: () -> Unit,
    onLpoMaterialReport: () -> Unit,
    onItemMovement: () -> Unit,
    onStockReport: () -> Unit,
    onSalesProfit: () -> Unit,
    onSalesItemReport: () -> Unit,
    onVatReport: () -> Unit,
    onOutstanding: () -> Unit,
    onAccounts: () -> Unit,
    onJournal: () -> Unit,
    onLedger: () -> Unit,
    onTrialBalance: () -> Unit,
    onProfitLoss: () -> Unit,
    onBalanceSheet: () -> Unit,
    onGymMembers: () -> Unit,
    onGymDue: () -> Unit,
    onGymSlots: () -> Unit,
    onCoachStudents: () -> Unit,
    onCoachMasters: () -> Unit,
    onCoachEnquiry: () -> Unit,
    onCoachDue: () -> Unit,
    onCoachAttendance: () -> Unit,
    onCoachAttReport: () -> Unit,
    onServiceJobs: () -> Unit,
    onServiceMasters: () -> Unit,
    onServiceStatusReport: () -> Unit,
    onServicePendingReport: () -> Unit,
    onRpReport: () -> Unit,
    onDiary: () -> Unit,
    onUsers: () -> Unit,
    onSettings: () -> Unit,
    onBackup: () -> Unit,
    onLogout: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val businessType = remember { com.billing.pos.data.AppPrefs(context).businessType }
    val isRental = businessType == "Rental"
    val isPersonal = businessType == "Personal"
    // The two counter tools, available here as well as inside a sale.
    var showCalculator by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showMobileBoard by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val isLab = businessType == "Medical lab"
    val isBulkSms = businessType == "Bulk SMS"
    val isGym = businessType == "Gym"
    val isCoaching = businessType == "Coaching Center"
    val tiles = buildList {
        if (isGym) {
            add(Tile("Members", Icons.Filled.People, onGymMembers, "Masters"))
            add(Tile("Fees Due", Icons.Filled.EventBusy, onGymDue, "Reports"))
            add(Tile("Slots", Icons.Filled.Schedule, onGymSlots, "Reports"))
        }
        if (isCoaching) {
            add(Tile("Students", Icons.Filled.People, onCoachStudents, "Masters"))
            add(Tile("Coaching Masters", Icons.Filled.MenuBook, onCoachMasters, "Masters"))
            add(Tile("Enquiries", Icons.Filled.Contacts, onCoachEnquiry, "Transactions"))
            add(Tile("Attendance", Icons.Filled.HowToReg, onCoachAttendance, "Transactions"))
            add(Tile("Fees Due", Icons.Filled.EventBusy, onCoachDue, "Reports"))
            add(Tile("Attendance Report", Icons.Filled.Schedule, onCoachAttReport, "Reports"))
        }
        // ---- Transactions ----
        add(Tile("Sticky Note", Icons.Filled.EditNote, onStickyNote, "Transactions"))
        add(Tile("New Bill", Icons.Filled.PointOfSale, onNewBill, "Transactions"))
        add(Tile("Quick Bill", Icons.Filled.Restaurant, onQuickBill, "Transactions"))
        if (Session.canViewInvoice) add(Tile("Invoices", Icons.Filled.ReceiptLong, onInvoices, "Transactions"))
        add(Tile("Calculator", Icons.Filled.Calculate, { showCalculator = true }, "Transactions"))
        add(Tile("Mobile number", Icons.Filled.Phone, { showMobileBoard = true }, "Transactions"))
        add(Tile("Poster maker", Icons.Filled.Campaign, onPoster, "Transactions"))
        add(Tile("Quotations", Icons.Filled.Description, onQuotations, "Transactions"))
        add(Tile("Estimates", Icons.Filled.RequestQuote, onEstimates, "Transactions"))
        if (Session.canViewInvoice) add(Tile("Sales Return", Icons.Filled.AssignmentReturn, onSalesReturns, "Transactions"))
        add(Tile("New Purchase", Icons.Filled.ShoppingCart, onNewPurchase, "Transactions"))
        if (Session.canViewInvoice) add(Tile("Purchases", Icons.Filled.Inventory2, onPurchases, "Transactions"))
        if (Session.canViewInvoice) add(Tile("Purchase Return", Icons.Filled.AssignmentReturned, onPurchaseReturns, "Transactions"))
        add(Tile("Purchase Order", Icons.Filled.PlaylistAddCheck, onLpos, "Transactions"))
        add(Tile("Purchase Quotation", Icons.Filled.RequestQuote, onPurchaseQuotes, "Transactions"))
        add(Tile("Orders", Icons.Filled.ListAlt, onOrders, "Transactions"))
        if (Session.canViewInvoice) add(Tile("Material Receipt", Icons.Filled.MoveToInbox, onMaterialReceipt, "Transactions"))
        if (isLab) {
            add(Tile("Lab Bill", Icons.Filled.Science, onLabBills, "Transactions"))
            add(Tile("Material Out", Icons.Filled.MoveDown, onMaterialOut, "Transactions"))
        }
        if (isRental) {
            add(Tile("Hire Invoice", Icons.Filled.Handshake, onHireInvoices, "Transactions"))
            add(Tile("Hire Return", Icons.Filled.AssignmentReturn, onHireReturns, "Transactions"))
        }
        // Job cards are useful to any shop that takes in service work, so every
        // business type gets them except Personal (which has no shop tools at all).
        if (!isPersonal) {
            add(Tile("Job Cards", Icons.Filled.Build, onServiceJobs, "Transactions"))
            add(Tile("Service Masters", Icons.Filled.Handyman, onServiceMasters, "Masters"))
            add(Tile("Job Status Report", Icons.Filled.Assessment, onServiceStatusReport, "Reports"))
            add(Tile("Pending Jobs", Icons.Filled.EventBusy, onServicePendingReport, "Reports"))
        }

        // ---- Masters ----
        add(Tile("Customers", Icons.Filled.People, onCustomers, "Masters"))
        add(Tile("Items", Icons.Filled.Category, onItems, "Masters"))
        add(Tile("Suppliers", Icons.Filled.LocalShipping, onSuppliers, "Masters"))
        if (isLab) {
            add(Tile("Patients", Icons.Filled.People, onPatients, "Masters"))
            add(Tile("Lab Tests", Icons.Filled.Biotech, onLabTests, "Masters"))
        }
        if (isBulkSms) {
            add(Tile("Send SMS", Icons.AutoMirrored.Filled.Send, onSendSms, "Transactions"))
            add(Tile("Bulk SMS", Icons.Filled.Campaign, onBulkSms, "Transactions"))
            add(Tile("Attendance", Icons.Filled.HowToReg, onAttendance, "Transactions"))
            add(Tile("Contacts", Icons.Filled.Contacts, onContacts, "Masters"))
            add(Tile("SMS Templates", Icons.Filled.Description, onSmsTemplates, "Masters"))
            add(Tile("SMS Settings", Icons.Filled.Settings, onSmsSettings, "Masters"))
            add(Tile("SMS Report", Icons.Filled.Assessment, onSmsReport, "Reports"))
        }
        add(Tile("My Diary", Icons.Filled.MenuBook, onDiary, "Masters"))
        if (Session.canManageUsers) add(Tile("Users", Icons.Filled.ManageAccounts, onUsers, "Masters"))
        if (Session.canManageUsers) add(Tile("Settings", Icons.Filled.Settings, onSettings, "Masters"))
        if (Session.canExport || Session.canImport) add(Tile("Backup", Icons.Filled.Backup, onBackup, "Masters"))

        // ---- Accounts ----
        if (Session.canViewReceipt) add(Tile("Receipts", Icons.Filled.Payments, onReceipts, "Accounts"))
        if (Session.canViewPayment) add(Tile("Payments", Icons.Filled.MoneyOff, onExpenses, "Accounts"))
        if (Session.canViewCashbook) add(Tile("Cash Book", Icons.Filled.AccountBalanceWallet, onCashbook, "Accounts"))
        if (Session.canViewInvoice) add(Tile("Outstanding", Icons.Filled.AccountBalance, onOutstanding, "Accounts"))
        if (Session.canManageUsers) add(Tile("Accounts", Icons.Filled.AccountTree, onAccounts, "Accounts"))
        if (Session.canManageUsers) add(Tile("Journal", Icons.Filled.Book, onJournal, "Accounts"))
        if (Session.canViewCashbook) add(Tile("Ledger", Icons.Filled.Summarize, onLedger, "Accounts"))
        if (Session.canViewCashbook) add(Tile("Receipt & Payment", Icons.Filled.Summarize, onRpReport, "Accounts"))

        // ---- Reports ----
        if (Session.canViewInvoice) add(Tile("Sales Report", Icons.Filled.Assessment, onReports, "Reports"))
        if (Session.canViewInvoice) add(Tile("Sales Profit", Icons.Filled.TrendingUp, onSalesProfit, "Reports"))
        if (Session.canViewInvoice) add(Tile("Sales (item-wise)", Icons.Filled.Inventory2, onSalesItemReport, "Reports"))
        if (Session.canViewInvoice) add(Tile("LPO Material", Icons.Filled.PlaylistAddCheck, onLpoMaterialReport, "Reports"))
        add(Tile("Stock on Date", Icons.Filled.Inventory, onStockReport, "Reports"))
        add(Tile("Item Movement", Icons.Filled.SwapVert, onItemMovement, "Reports"))
        add(Tile("Price Search", Icons.Filled.PriceCheck, onPriceSearch, "Reports"))
        if (Session.canViewInvoice) add(Tile("VAT Report", Icons.Filled.Description, onVatReport, "Reports"))
        if (Session.canManageUsers) {
            add(Tile("Trial Balance", Icons.Filled.Balance, onTrialBalance, "Reports"))
            add(Tile("Profit & Loss", Icons.Filled.TrendingUp, onProfitLoss, "Reports"))
            add(Tile("Balance Sheet", Icons.Filled.AccountBalance, onBalanceSheet, "Reports"))
        }
        if (isRental) {
            add(Tile("Hire Item Report", Icons.Filled.Inventory2, onHireItemReport, "Reports"))
            add(Tile("Hire Expiry", Icons.Filled.EventBusy, onHireExpiryReport, "Reports"))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        var query by remember { mutableStateOf("") }
        val visibleTiles = when {
            isPersonal -> tiles.filter { it.label in PERSONAL_TILES }
            isBulkSms -> tiles.filter { it.label in BULK_SMS_TILES }
            isGym -> tiles.filter { it.label in GYM_TILES }
            isCoaching -> tiles.filter { it.label in COACHING_TILES }
            else -> tiles
        }
        val shown = if (query.isBlank()) visibleTiles else visibleTiles.filter { it.label.contains(query, ignoreCase = true) }

        // How often each tile has been opened, so the five most-used can sit on top.
        val usage = remember { context.getSharedPreferences("dashboard_usage", android.content.Context.MODE_PRIVATE) }
        var useTick by remember { mutableStateOf(0) }
        fun record(label: String) {
            usage.edit().putInt(label, usage.getInt(label, 0) + 1).apply()
            useTick++
        }
        val frequent = remember(useTick, visibleTiles.size) {
            visibleTiles.filter { usage.getInt(it.label, 0) > 0 }
                .sortedByDescending { usage.getInt(it.label, 0) }
                .take(5)
        }
        // Sections start closed; opening one is remembered only for this visit.
        val openSections = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

        Column(Modifier.fillMaxSize().padding(pad)) {
            // Screens set aside with the minimise handle, waiting to be picked up again.
            val parked = com.billing.pos.ui.common.ParkedScreens.items
            if (parked.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "MINIMISED",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline
                    )
                    parked.toList().forEach { item ->
                        androidx.compose.material3.AssistChip(
                            onClick = { onOpenParked(item.route) },
                            label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Discard",
                                    modifier = Modifier.size(16.dp).clickable {
                                        com.billing.pos.ui.common.ParkedScreens.forget(item.route)
                                    }
                                )
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Search a feature…") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (query.isNotBlank()) {
                    // Searching shows the matches straight away, no section to open first.
                    items(shown) { tile -> TileCard(tile) { record(tile.label); tile.onClick() } }
                } else {
                    if (frequent.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text(
                                    "FREQUENTLY USED",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    frequent.forEach { tile ->
                                        Card(
                                            Modifier.weight(1f).height(76.dp)
                                                .clickable { record(tile.label); tile.onClick() }
                                        ) {
                                            Column(
                                                Modifier.fillMaxSize().padding(4.dp),
                                                verticalArrangement = Arrangement.Center,
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Icon(
                                                    tile.icon, tile.label,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                                Text(
                                                    tile.label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    textAlign = TextAlign.Center,
                                                    maxLines = 2
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!isPersonal) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            DashboardCharts(onOpenDetail = onOpenChart)
                        }
                    }

                    SECTION_ORDER.forEach { section ->
                        val secTiles = visibleTiles.filter { it.section == section }
                        if (secTiles.isNotEmpty()) {
                            val open = openSections[section] == true
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { openSections[section] = !open }
                                        .padding(top = 10.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = if (open) "Collapse" else "Expand",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        section.uppercase(),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                                    )
                                    Text(
                                        secTiles.size.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                            if (open) {
                                items(secTiles) { tile -> TileCard(tile) { record(tile.label); tile.onClick() } }
                            }
                        }
                    }
                }
            }
            Text(
                "Support: 9961128378",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:9961128378"))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }.padding(vertical = 6.dp)
            )
        }
    }

    if (showCalculator) {
        com.billing.pos.ui.billing.FastBillDialog(
            onSave = { amounts ->
                // Hand the tape to a new sale, same as saving it from inside a bill.
                com.billing.pos.ui.billing.FastBillLink.amounts = amounts
                showCalculator = false
                onCalculatorToBill()
            },
            onDismiss = { showCalculator = false }
        )
    }
    if (showMobileBoard) {
        com.billing.pos.ui.billing.MobileNumberDialog(onDismiss = { showMobileBoard = false })
    }
}

@Composable
private fun TileCard(tile: Tile, onClick: () -> Unit = tile.onClick) {
    Card(Modifier.fillMaxWidth().height(120.dp).clickable { onClick() }) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(tile.icon, contentDescription = tile.label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Text(tile.label, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
