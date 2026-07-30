package com.billing.pos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.Icons
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.navArgument
import com.billing.pos.auth.PendingDiaryOpen
import com.billing.pos.auth.PendingImport
import com.billing.pos.auth.PendingSharedMedia
import com.billing.pos.auth.Session
import com.billing.pos.diary.EXTRA_OPEN_DIARY_ID
import com.billing.pos.data.AppPrefs
import com.billing.pos.ui.auth.BootScreen
import com.billing.pos.ui.auth.LoginScreen
import com.billing.pos.ui.backup.BackupScreen
import com.billing.pos.ui.accounts.ChartOfAccountsScreen
import com.billing.pos.ui.billing.BillingScreen
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.ui.cashbook.CashBookScreen
import com.billing.pos.ui.customers.CustomersScreen
import com.billing.pos.ui.dashboard.DashboardScreen
import com.billing.pos.ui.diary.DiaryEditScreen
import com.billing.pos.ui.diary.DiaryListScreen
import com.billing.pos.ui.expenses.ExpensesScreen
import com.billing.pos.ui.invoices.InvoiceListScreen
import com.billing.pos.ui.items.ItemsScreen
import com.billing.pos.ui.journal.JournalEntryScreen
import com.billing.pos.ui.journal.JournalListScreen
import com.billing.pos.ui.license.LicenseScreen
import com.billing.pos.ui.outstanding.OutstandingScreen
import com.billing.pos.ui.pricesearch.PriceSearchScreen
import com.billing.pos.ui.printer.PrinterSetupScreen
import com.billing.pos.ui.quickbill.QuickBillScreen
import com.billing.pos.ui.quotation.QuotationListScreen
import com.billing.pos.ui.quotation.QuotationScreen
import com.billing.pos.ui.salesreturn.SalesReturnListScreen
import com.billing.pos.ui.salesreturn.SalesReturnScreen
import com.billing.pos.ui.purchasereturn.PurchaseReturnListScreen
import com.billing.pos.ui.purchasereturn.PurchaseReturnScreen
import com.billing.pos.ui.lpo.PurchaseQuotationListScreen
import com.billing.pos.ui.lpo.PurchaseQuotationScreen
import com.billing.pos.ui.hire.HireInvoiceListScreen
import com.billing.pos.ui.hire.HireInvoiceScreen
import com.billing.pos.ui.hire.HireReturnListScreen
import com.billing.pos.ui.hire.HireReturnScreen
import com.billing.pos.ui.hire.HireItemReportScreen
import com.billing.pos.ui.hire.HireExpiryReportScreen
import com.billing.pos.ui.lab.LabTestListScreen
import com.billing.pos.ui.lab.LabTestEditScreen
import com.billing.pos.ui.lab.LabMasterScreen
import com.billing.pos.ui.lab.PatientListScreen
import com.billing.pos.ui.lab.LabBillScreen
import com.billing.pos.ui.lab.LabBillListScreen
import com.billing.pos.ui.lab.LabResultScreen
import com.billing.pos.ui.materialout.MaterialOutScreen
import com.billing.pos.ui.materialout.MaterialOutListScreen
import com.billing.pos.ui.materialreceipt.MaterialReceiptScreen
import com.billing.pos.ui.materialreceipt.MaterialReceiptListScreen
import com.billing.pos.ui.reports.LpoMaterialReportScreen
import com.billing.pos.ui.materialout.MaterialOutLink
import com.billing.pos.ui.materialout.ItemMovementScreen
import com.billing.pos.ui.reports.StockReportScreen
import com.billing.pos.ui.reports.SalesProfitScreen
import com.billing.pos.ui.reports.SalesItemReportScreen
import com.billing.pos.ui.purchase.PurchaseListScreen
import com.billing.pos.ui.purchase.PurchaseScreen
import com.billing.pos.ui.purchase.SuppliersScreen
import com.billing.pos.ui.receipts.ReceiptsScreen
import com.billing.pos.ui.reports.ReportsScreen
import com.billing.pos.ui.settings.SettingsScreen
import com.billing.pos.ui.theme.POSTheme
import com.billing.pos.ui.vat.VatReportScreen
import com.billing.pos.ui.users.UsersScreen

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen awake while the app is in the foreground; normal lock resumes
        // once the app is closed/backgrounded (the flag only applies to this window).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        captureIncoming(intent)
        // Offer the Play update straight away, so customers are not left on an old build.
        com.billing.pos.update.AppUpdater.check(this)
        enableEdgeToEdge()
        setContent {
            POSTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val ctx = LocalContext.current
                    // App lock (Settings): ask for the phone's own lock once per app start.
                    val lockOn = androidx.compose.runtime.remember { AppPrefs(ctx).appLock }
                    var unlocked by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(!lockOn || com.billing.pos.ui.common.AppLockGate.unlocked)
                    }
                    if (unlocked) AppNav()
                    else com.billing.pos.ui.common.AppLockScreen(onUnlocked = {
                        com.billing.pos.ui.common.AppLockGate.unlocked = true
                        unlocked = true
                    })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        // Pick an update back up if it was interrupted last time.
        com.billing.pos.update.AppUpdater.resumeIfInterrupted(this)
    }

    /**
     * Pulls out of the launch intent: a backup .zip (import), media shared from another app
     * (attach to a new diary entry), or a diary id (reminder tap).
     */
    private fun captureIncoming(intent: Intent?) {
        intent ?: return
        val isZip = intent.type?.let { it == "application/zip" || it == "application/x-zip-compressed" } == true
        // Which app shared to us, so the diary title can name it. Often "" when unknown.
        val source = when (referrer?.host) {
            "com.whatsapp" -> "WhatsApp"
            "com.whatsapp.w4b" -> "WhatsApp Business"
            else -> ""
        }

        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    if (isZip) PendingImport.uri = uri
                    else PendingSharedMedia.set(listOf(uri), source)   // WhatsApp photo/voice/video/document
                } else {
                    // A forwarded message: text only, no file.
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    if (text.isNotBlank()) PendingSharedMedia.setText(text, source)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) PendingSharedMedia.set(uris, source)
            }
            Intent.ACTION_VIEW -> intent.data?.let { PendingImport.uri = it }
        }

        val diaryId = intent.getLongExtra(EXTRA_OPEN_DIARY_ID, 0L)
        if (diaryId > 0L) PendingDiaryOpen.id = diaryId
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val context = LocalContext.current

    // Media shared in from another app (WhatsApp etc.).
    //
    // Cold start is routed by boot / login / change-password below. This effect covers only a
    // WARM start (app already open, share arriving via onNewIntent). The bootDone guard is
    // essential: without it this also fires on a cold start, navigates to the diary, the diary
    // consumes the files, and boot then routes to the dashboard on top of it.
    var bootDone by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val sharedGeneration = PendingSharedMedia.generation
    androidx.compose.runtime.LaunchedEffect(sharedGeneration) {
        if (bootDone && PendingSharedMedia.awaitingDiary && Session.isLoggedIn) {
            PendingSharedMedia.markRouted()
            nav.navigate("diary/edit/0") { launchSingleTop = true }
        }
    }

    // Reminder tap → once logged in, open that diary entry in edit mode.
    val pendingDiaryId = PendingDiaryOpen.id
    androidx.compose.runtime.LaunchedEffect(pendingDiaryId, Session.current) {
        val did = PendingDiaryOpen.id
        if (did != null && Session.isLoggedIn) {
            PendingDiaryOpen.consume()
            nav.navigate("diary/edit/$did") { launchSingleTop = true }
        }
    }

    val logout: () -> Unit = {
        AppPrefs(context).clearSession()
        Session.logout()
        nav.navigate("login") { popUpTo(0) { inclusive = true } }
    }

    @Composable
    fun billing(editId: Long?, estimate: Boolean = false) {
        BillingScreen(
            estimate = estimate,
            editBillId = editId,
            onBack = { nav.popBackStack() },
            onOpenReports = { nav.navigate("reports") },
            onOpenInvoices = { nav.navigate("invoices") },
            onOpenUsers = { nav.navigate("users") },
            onOpenDiary = { nav.navigate("diary") },
            onOpenReceipts = { nav.navigate("receipts") },
            onOpenExpenses = { nav.navigate("expenses") },
            onOpenCashbook = { nav.navigate("cashbook") },
            onOpenCustomers = { nav.navigate("customers") },
            onOpenSettings = { nav.navigate("settings") },
            onOpenBackup = { nav.navigate("backup") },
            onLogout = logout
        )
    }

    // ---- Minimise / restore ----
    //
    // A back stack cannot hold two background screens and switch freely between them, so a
    // parked screen is popped with its state saved and brought back by navigating to it
    // again with restoreState. One saved state per destination, hence one of each kind.
    val currentEntry by nav.currentBackStackEntryAsState()
    val currentPattern = currentEntry?.destination?.route

    fun concreteRoute(entry: androidx.navigation.NavBackStackEntry): String {
        var route = entry.destination.route ?: return ""
        entry.arguments?.let { args ->
            Regex("\\{(\\w+)\\}").findAll(route).map { it.groupValues[1] }.toList().forEach { key ->
                val value = args.get(key)?.toString() ?: ""
                route = route.replace("{" + key + "}", value)
            }
        }
        return route
    }

    androidx.compose.runtime.LaunchedEffect(com.billing.pos.ui.common.ParkedScreens.pendingLabel) {
        val label = com.billing.pos.ui.common.ParkedScreens.consumePending() ?: return@LaunchedEffect
        val entry = nav.currentBackStackEntry ?: return@LaunchedEffect
        val route = concreteRoute(entry)
        if (route.isBlank()) return@LaunchedEffect
        com.billing.pos.ui.common.ParkedScreens.remember(route, label)
        nav.navigate("dashboard") {
            popUpTo(entry.destination.id) { saveState = true; inclusive = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    NavHost(navController = nav, startDestination = "boot") {
        composable("boot") {
            BootScreen(onResolved = { route ->
                val dest = when {
                    route == "dashboard" && PendingSharedMedia.awaitingDiary -> {
                        PendingSharedMedia.markRouted(); "diary/edit/0"
                    }
                    route == "dashboard" && PendingImport.uri != null -> "invoices"
                    else -> route
                }
                bootDone = true
                nav.navigate(dest) { popUpTo("boot") { inclusive = true } }
            })
        }
        composable("mergelog") {
            com.billing.pos.ui.backup.MergeLogScreen(
                onBack = { nav.popBackStack() },
                onOpenGroup = { key -> nav.navigate("mergelog/$key") }
            )
        }
        composable(
            route = "mergelog/{key}",
            arguments = listOf(navArgument("key") { type = NavType.StringType })
        ) { entry ->
            com.billing.pos.ui.backup.MergeLogDetailScreen(
                groupKey = entry.arguments?.getString("key").orEmpty(),
                onBack = { nav.popBackStack() },
                onEdit = { route -> nav.navigate(route) }
            )
        }
        composable("chooseBusiness") {
            com.billing.pos.ui.auth.BusinessTypeScreen(onChosen = {
                nav.navigate("dashboard") { popUpTo(0) { inclusive = true } }
            })
        }
        composable("dashboard") {
            // Back from the dashboard means leaving the app, so make it deliberate — a
            // stray back press should not throw away a half-finished day.
            var confirmExit by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            androidx.activity.compose.BackHandler { confirmExit = true }
            if (confirmExit) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmExit = false },
                    title = { androidx.compose.material3.Text("Close the app?") },
                    text = { androidx.compose.material3.Text("Are you sure you want to close?") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            confirmExit = false
                            com.billing.pos.ui.diary.DiaryAudio.controller.stop()
                            activity?.finish()
                        }) { androidx.compose.material3.Text("Close") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { confirmExit = false }) {
                            androidx.compose.material3.Text("Stay")
                        }
                    }
                )
            }

            // Catch-all for an incoming share: whatever route led here, if a shared file is
            // still waiting, hand it to the diary. This does not depend on the boot/login
            // handshake winning a race, so it holds even if an earlier redirect was missed.
            androidx.compose.runtime.LaunchedEffect(PendingSharedMedia.generation) {
                if (PendingSharedMedia.awaitingDiary) {
                    PendingSharedMedia.markRouted()
                    nav.navigate("diary/edit/0")
                }
            }
            // Sticky note on launch (once per app start).
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (PendingSharedMedia.awaitingDiary) return@LaunchedEffect   // share wins
                if (!com.billing.pos.ui.sticky.StickyGate.shown && com.billing.pos.data.AppPrefs(context).stickyNoteOnLaunch) {
                    com.billing.pos.ui.sticky.StickyGate.shown = true
                    nav.navigate("stickynote")
                }
            }
            // Medical store: expiring-medicine popup, once per app start.
            val prefsNow = androidx.compose.runtime.remember { AppPrefs(context) }
            if (prefsNow.expiryAlert && prefsNow.businessType == "Medical store") {
                val expVm: com.billing.pos.ui.medical.ExpiryAlertViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel()
                val expRows by expVm.rows.collectAsStateSafe()
                var showExpiry by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                androidx.compose.runtime.LaunchedEffect(expRows) {
                    if (!com.billing.pos.ui.medical.ExpiryGate.shown && expRows.isNotEmpty()) {
                        com.billing.pos.ui.medical.ExpiryGate.shown = true
                        showExpiry = true
                    }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { com.billing.pos.medical.ExpiryAlarm.sync(context) }
                if (showExpiry) {
                    com.billing.pos.ui.medical.ExpiryAlertDialog(
                        rows = expRows,
                        onOpenPurchase = { id -> showExpiry = false; nav.navigate("purchase/edit/$id") },
                        onDismiss = { showExpiry = false }
                    )
                }
            }
            DashboardScreen(
                onStickyNote = { nav.navigate("stickynote") },
                onNewBill = { nav.navigate("billing") },
                onQuickBill = { nav.navigate("quickbill") },
                onOrders = { nav.navigate("orders") },
                onPriceSearch = { nav.navigate("pricesearch") },
                onInvoices = { nav.navigate("invoices") },
                onReceipts = { nav.navigate("receipts") },
                onExpenses = { nav.navigate("expenses") },
                onCashbook = { nav.navigate("cashbook") },
                onReports = { nav.navigate("reports") },
                onCustomers = { nav.navigate("customers") },
                onContacts = { nav.navigate("contacts") },
                onSendSms = { nav.navigate("sendsms") },
                onBulkSms = { nav.navigate("bulksms") },
                onAttendance = { nav.navigate("attendance") },
                onSmsTemplates = { nav.navigate("smstemplates") },
                onSmsReport = { nav.navigate("smsreport") },
                onSmsSettings = { nav.navigate("smssettings") },
                onItems = { nav.navigate("items") },
                onNewPurchase = { nav.navigate("purchase") },
                onPurchases = { nav.navigate("purchases") },
                onSuppliers = { nav.navigate("suppliers") },
                onQuotations = { nav.navigate("quotations") },
                onEstimates = { nav.navigate("estimates") },
                onPoster = { nav.navigate("poster") },
                onCalculatorToBill = { nav.navigate("billing") },
                onSalesReturns = { nav.navigate("salesreturns") },
                onPurchaseReturns = { nav.navigate("purchasereturns") },
                onLpos = { nav.navigate("lpos") },
                onPurchaseQuotes = { nav.navigate("pquotes") },
                onOpenChart = { metric -> nav.navigate("chart/$metric") },
                onOpenParked = { route ->
                    com.billing.pos.ui.common.ParkedScreens.forget(route)
                    nav.navigate(route) { restoreState = true; launchSingleTop = true }
                },
                onHireInvoices = { nav.navigate("hires") },
                onHireReturns = { nav.navigate("hirereturns") },
                onHireItemReport = { nav.navigate("hireitemreport") },
                onHireExpiryReport = { nav.navigate("hireexpiry") },
                onLabTests = { nav.navigate("labtests") },
                onPatients = { nav.navigate("patients") },
                onLabBills = { nav.navigate("labbills") },
                onMaterialOut = { nav.navigate("materialouts") },
                onMaterialReceipt = { nav.navigate("materialreceipts") },
                onLpoMaterialReport = { nav.navigate("lpomaterialreport") },
                onItemMovement = { nav.navigate("itemmovement") },
                onStockReport = { nav.navigate("stockreport") },
                onSalesProfit = { nav.navigate("salesprofit") },
                onSalesItemReport = { nav.navigate("salesitemreport") },
                onVatReport = { nav.navigate("vat") },
                onOutstanding = { nav.navigate("outstanding") },
                onAccounts = { nav.navigate("accounts") },
                onJournal = { nav.navigate("journal") },
                onLedger = { nav.navigate("ledger") },
                onTrialBalance = { nav.navigate("trialbalance") },
                onProfitLoss = { nav.navigate("profitloss") },
                onBalanceSheet = { nav.navigate("balancesheet") },
                onGymMembers = { nav.navigate("gym/members") },
                onGymDue = { nav.navigate("gym/due") },
                onGymSlots = { nav.navigate("gym/slots") },
                onCoachStudents = { nav.navigate("coach/students") },
                onCoachMasters = { nav.navigate("coach/masters") },
                onCoachEnquiry = { nav.navigate("coach/enquiries") },
                onCoachDue = { nav.navigate("coach/due") },
                onCoachAttendance = { nav.navigate("coach/attendance") },
                onCoachAttReport = { nav.navigate("coach/attreport") },
                onServiceJobs = { nav.navigate("service/jobs") },
                onServiceMasters = { nav.navigate("service/masters") },
                onServiceStatusReport = { nav.navigate("service/statusreport") },
                onServicePendingReport = { nav.navigate("service/pending") },
                onRpReport = { nav.navigate("rpreport") },
                onDiary = { nav.navigate("diary") },
                onUsers = { nav.navigate("users") },
                onSettings = { nav.navigate("settings") },
                onBackup = { nav.navigate("backup") },
                onLogout = logout
            )
        }
        composable("license") {
            LicenseScreen(onActivated = {
                // Re-run boot: it auto-logs in and routes to onboarding or the dashboard.
                // (The old flow went to the login screen, which no longer exists.)
                nav.navigate("boot") { popUpTo(0) { inclusive = true } }
            })
        }
        composable("login") {
            LoginScreen(onLoggedIn = { mustChangePassword ->
                val dest = when {
                    mustChangePassword -> "changepassword"
                    PendingSharedMedia.awaitingDiary -> {
                        PendingSharedMedia.markRouted(); "diary/edit/0"
                    }
                    PendingImport.uri != null -> "invoices"
                    else -> "dashboard"
                }
                nav.navigate(dest) { popUpTo(0) { inclusive = true } }
            })
        }
        composable("changepassword") {
            com.billing.pos.ui.auth.ChangePasswordScreen(onDone = {
                val dest = when {
                    PendingSharedMedia.awaitingDiary -> {
                        PendingSharedMedia.markRouted(); "diary/edit/0"
                    }
                    PendingImport.uri != null -> "invoices"
                    else -> "dashboard"
                }
                nav.navigate(dest) { popUpTo(0) { inclusive = true } }
            })
        }
        composable("billing") { billing(null) }
        composable(
            route = "billing/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> billing(entry.arguments?.getLong("id")) }
        // Estimate: the same sales-entry screen, saved to its own table.
        composable("estimate") { billing(null, estimate = true) }
        composable(
            route = "estimate/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> billing(entry.arguments?.getLong("id"), estimate = true) }
        composable("estimates") {
            com.billing.pos.ui.estimate.EstimateListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("estimate/edit/$id") },
                onNew = { nav.navigate("estimate") }
            )
        }
        composable("invoices") {
            InvoiceListScreen(
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate("billing/edit/$id") }
            )
        }
        composable("reports") {
            ReportsScreen(onBack = { nav.popBackStack() })
        }
        composable("receipts") {
            ReceiptsScreen(onBack = { nav.popBackStack() })
        }
        composable("expenses") {
            ExpensesScreen(onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() }, onOpenPrinter = { nav.navigate("printer") })
        }
        composable("printer") {
            PrinterSetupScreen(onBack = { nav.popBackStack() })
        }
        composable("quickbill") {
            QuickBillScreen(onBack = { nav.popBackStack() })
        }
        composable("orders") {
            com.billing.pos.ui.order.OrderListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("order/edit/$id") },
                onNew = { nav.navigate("order") },
                onReport = { nav.navigate("orderreport") },
                onConvertToBill = { nav.navigate("billing") }
            )
        }
        composable("order") { com.billing.pos.ui.order.OrderEntryScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "order/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> com.billing.pos.ui.order.OrderEntryScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("orderreport") { com.billing.pos.ui.order.OrderReportScreen(onBack = { nav.popBackStack() }) }
        composable("customers") {
            CustomersScreen(onBack = { nav.popBackStack() })
        }
        composable("contacts") {
            com.billing.pos.ui.sms.ContactsScreen(onBack = { nav.popBackStack() })
        }
        composable("sendsms") {
            com.billing.pos.ui.sms.SendSmsScreen(onBack = { nav.popBackStack() })
        }
        composable("bulksms") {
            com.billing.pos.ui.sms.BulkSmsScreen(onBack = { nav.popBackStack() })
        }
        composable("attendance") {
            com.billing.pos.ui.sms.AttendanceScreen(onBack = { nav.popBackStack() })
        }
        composable("smstemplates") {
            com.billing.pos.ui.sms.SmsTemplatesScreen(onBack = { nav.popBackStack() })
        }
        composable("smsreport") {
            com.billing.pos.ui.sms.SmsReportScreen(onBack = { nav.popBackStack() })
        }
        composable("smssettings") {
            com.billing.pos.ui.sms.SmsSettingsScreen(onBack = { nav.popBackStack() })
        }
        composable("purchase") {
            PurchaseScreen(editPurchaseId = null, onBack = { nav.popBackStack() })
        }
        composable(
            route = "purchase/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            PurchaseScreen(editPurchaseId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() })
        }
        composable("purchases") {
            PurchaseListScreen(
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate("purchase/edit/$id") }
            )
        }
        composable("suppliers") {
            SuppliersScreen(onBack = { nav.popBackStack() })
        }
        composable("quotations") {
            QuotationListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("quotation/edit/$id") },
                onNew = { nav.navigate("quotation") }
            )
        }
        composable("quotation") { QuotationScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "quotation/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> QuotationScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("salesreturns") {
            SalesReturnListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("salesreturn/edit/$id") },
                onNew = { nav.navigate("salesreturn") }
            )
        }
        composable("salesreturn") { SalesReturnScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "salesreturn/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> SalesReturnScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("purchasereturns") {
            PurchaseReturnListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("purchasereturn/edit/$id") },
                onNew = { nav.navigate("purchasereturn") }
            )
        }
        composable("purchasereturn") { PurchaseReturnScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "purchasereturn/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> PurchaseReturnScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("lpos") {
            PurchaseQuotationListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("lpo/edit/$id") },
                onNew = { nav.navigate("lpo") }
            )
        }
        composable("lpo") { PurchaseQuotationScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "chart/{metric}",
            arguments = listOf(navArgument("metric") { type = NavType.StringType })
        ) { entry ->
            com.billing.pos.ui.dashboard.ChartDetailScreen(
                metric = entry.arguments?.getString("metric") ?: "cash",
                onBack = { nav.popBackStack() },
                onOpen = { route -> if (route.isNotBlank()) nav.navigate(route) }
            )
        }
        composable("pquotes") {
            com.billing.pos.ui.purchasequote.PurchaseQuoteListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("pquote/edit/$id") },
                onNew = { nav.navigate("pquote") }
            )
        }
        composable("pquote") {
            com.billing.pos.ui.purchasequote.PurchaseQuoteScreen(editId = null, onBack = { nav.popBackStack() })
        }
        composable(
            route = "pquote/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            com.billing.pos.ui.purchasequote.PurchaseQuoteScreen(
                editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }
            )
        }
        composable(
            route = "lpo/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> PurchaseQuotationScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("hires") {
            HireInvoiceListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("hire/edit/$id") },
                onNew = { nav.navigate("hire") }
            )
        }
        composable("hire") { HireInvoiceScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "hire/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> HireInvoiceScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("hirereturns") {
            HireReturnListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("hirereturn/edit/$id") },
                onNew = { nav.navigate("hirereturn") }
            )
        }
        composable("hirereturn") { HireReturnScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "hirereturn/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> HireReturnScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("hireitemreport") {
            HireItemReportScreen(onBack = { nav.popBackStack() }, onOpenHire = { id -> nav.navigate("hire/edit/$id") })
        }
        composable("hireexpiry") {
            HireExpiryReportScreen(onBack = { nav.popBackStack() }, onOpenHire = { id -> nav.navigate("hire/edit/$id") })
        }
        composable("labtests") {
            LabTestListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("labtest/edit/$id") },
                onNew = { nav.navigate("labtest") },
                onMasters = { nav.navigate("labmasters") }
            )
        }
        composable("labmasters") { LabMasterScreen(onBack = { nav.popBackStack() }) }
        composable("labtest") { LabTestEditScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "labtest/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> LabTestEditScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("patients") { PatientListScreen(onBack = { nav.popBackStack() }) }
        composable("labbills") {
            LabBillListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("labbill/edit/$id") },
                onResult = { id -> nav.navigate("labresult/$id") },
                onNew = { nav.navigate("labbill") },
                onUsedMaterials = { nav.navigate("materialout") }
            )
        }
        composable("labbill") { LabBillScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "labbill/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> LabBillScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }, onNewMaterial = { nav.navigate("materialout") }) }
        composable(
            route = "labresult/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> LabResultScreen(billId = entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() }) }
        composable("materialouts") {
            MaterialOutListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("materialout/edit/$id") },
                onNew = { nav.navigate("materialout") }
            )
        }
        composable("materialout") {
            val (refs, tests) = remember { MaterialOutLink.take() }
            MaterialOutScreen(editId = null, resultRefs = refs, resultTests = tests, onBack = { nav.popBackStack() })
        }
        composable(
            route = "materialout/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> MaterialOutScreen(editId = entry.arguments?.getLong("id"), resultRefs = null, resultTests = null, onBack = { nav.popBackStack() }) }

        // Material Receipt Note (goods received against an LPO).
        composable("materialreceipts") {
            MaterialReceiptListScreen(onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("materialreceipt/edit/$id") }, onNew = { nav.navigate("materialreceipt") })
        }
        composable("materialreceipt") { MaterialReceiptScreen(editId = null, onBack = { nav.popBackStack() }) }
        composable(
            route = "materialreceipt/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry -> MaterialReceiptScreen(editId = entry.arguments?.getLong("id"), onBack = { nav.popBackStack() }) }
        composable("lpomaterialreport") {
            LpoMaterialReportScreen(onBack = { nav.popBackStack() }, onOpenLpo = { id -> nav.navigate("lpo/edit/$id") })
        }
        composable("stickynote") {
            com.billing.pos.ui.sticky.StickyNoteScreen(
                onClose = { nav.popBackStack() },
                onOcrToSales = { nav.navigate("billing") }
            )
        }
        composable("stockreport") { StockReportScreen(onBack = { nav.popBackStack() }) }
        composable("salesprofit") {
            SalesProfitScreen(onBack = { nav.popBackStack() }, onOpenInvoice = { id -> nav.navigate("billing/edit/$id") })
        }
        composable("salesitemreport") {
            SalesItemReportScreen(
                onBack = { nav.popBackStack() },
                onOpenItem = { id -> nav.navigate("items/edit/$id") },
                onOpenInvoice = { id -> nav.navigate("billing/edit/$id") }
            )
        }
        composable("itemmovement") {
            ItemMovementScreen(
                onBack = { nav.popBackStack() },
                onOpenVoucher = { kind, id ->
                    when (kind) {
                        "SALE" -> nav.navigate("billing/edit/$id")
                        "PURCHASE" -> nav.navigate("purchase/edit/$id")
                        "MATERIAL" -> nav.navigate("materialout/edit/$id")
                    }
                }
            )
        }
        composable("items") {
            ItemsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = "items/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            ItemsScreen(onBack = { nav.popBackStack() }, initialEditItemId = entry.arguments?.getLong("id"))
        }
        composable("poster") {
            com.billing.pos.ui.poster.PosterScreen(onBack = { nav.popBackStack() })
        }
        composable("pricesearch") {
            PriceSearchScreen(
                onBack = { nav.popBackStack() },
                onEditItem = { id -> nav.navigate("items/edit/$id") },
                onAddToSale = { nav.navigate("billing") }
            )
        }
        composable("vat") {
            VatReportScreen(onBack = { nav.popBackStack() })
        }
        composable("outstanding") {
            OutstandingScreen(onBack = { nav.popBackStack() })
        }
        composable("accounts") {
            ChartOfAccountsScreen(onBack = { nav.popBackStack() })
        }
        composable("ledger") {
            com.billing.pos.ui.ledger.LedgerReportScreen(onBack = { nav.popBackStack() })
        }
        composable("trialbalance") {
            com.billing.pos.ui.report.TrialBalanceScreen(onBack = { nav.popBackStack() })
        }
        composable("profitloss") {
            com.billing.pos.ui.report.ProfitLossScreen(onBack = { nav.popBackStack() })
        }
        composable("balancesheet") {
            com.billing.pos.ui.report.BalanceSheetScreen(onBack = { nav.popBackStack() })
        }
        composable("gym/members") {
            com.billing.pos.ui.gym.GymMembersScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("gym/member/$id") },
                onNew = { nav.navigate("gym/member/0") }
            )
        }
        composable("gym/member/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            com.billing.pos.ui.gym.GymMemberScreen(memberId = e.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
        }
        composable("gym/due") {
            com.billing.pos.ui.gym.GymDueReportScreen(onBack = { nav.popBackStack() })
        }
        composable("gym/slots") {
            com.billing.pos.ui.gym.GymSlotReportScreen(onBack = { nav.popBackStack() })
        }
        composable("coach/students") {
            com.billing.pos.ui.coaching.CoachingStudentsScreen(onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("coach/student/$id") }, onNew = { nav.navigate("coach/student/0") })
        }
        composable("coach/student/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            com.billing.pos.ui.coaching.CoachStudentScreen(studentId = e.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
        }
        composable("coach/masters") {
            com.billing.pos.ui.coaching.CoachingMastersScreen(onBack = { nav.popBackStack() })
        }
        composable("coach/enquiries") {
            com.billing.pos.ui.coaching.CoachingEnquiryScreen(onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("coach/enquiry/$id") }, onNew = { nav.navigate("coach/enquiry/0") })
        }
        composable("coach/enquiry/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            com.billing.pos.ui.coaching.CoachingEnquiryDetailScreen(enquiryId = e.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() }, onOpenStudent = { sid -> nav.navigate("coach/student/$sid") })
        }
        composable("coach/due") {
            com.billing.pos.ui.coaching.CoachDueReportScreen(onBack = { nav.popBackStack() })
        }
        composable("coach/attendance") {
            com.billing.pos.ui.coaching.CoachAttendanceScreen(onBack = { nav.popBackStack() })
        }
        composable("coach/attreport") {
            com.billing.pos.ui.coaching.CoachAttendanceReportScreen(onBack = { nav.popBackStack() })
        }
        composable("service/jobs") {
            com.billing.pos.ui.service.ServiceJobListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("service/job/$id") },
                onNew = { nav.navigate("service/job/0") },
                onConvertToBill = { nav.navigate("billing") }
            )
        }
        composable(
            route = "service/job/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            com.billing.pos.ui.service.ServiceJobScreen(
                editId = entry.arguments?.getLong("id"),
                onBack = { nav.popBackStack() }
            )
        }
        composable("service/masters") {
            com.billing.pos.ui.service.ServiceMastersScreen(onBack = { nav.popBackStack() })
        }
        composable("rpreport") {
            com.billing.pos.ui.reports.ReceiptPaymentReportScreen(onBack = { nav.popBackStack() })
        }
        composable("service/statusreport") {
            com.billing.pos.ui.service.ServiceStatusReportScreen(
                onBack = { nav.popBackStack() },
                onOpenCard = { id -> nav.navigate("service/job/$id") }
            )
        }
        composable("service/pending") {
            com.billing.pos.ui.service.ServiceDueReportScreen(
                onBack = { nav.popBackStack() },
                onOpenCard = { id -> nav.navigate("service/job/$id") }
            )
        }
        composable("journal") {
            JournalListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("journal/edit/$id") }
            )
        }
        composable(
            route = "journal/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            JournalEntryScreen(
                entryId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { nav.popBackStack() }
            )
        }
        composable("cashbook") {
            CashBookScreen(
                onBack = { nav.popBackStack() },
                onEditInvoice = { id -> nav.navigate("billing/edit/$id") },
                onEditPurchase = { id -> nav.navigate("purchase/edit/$id") },
                onEditJournal = { id -> nav.navigate("journal/edit/$id") }
            )
        }
        composable("backup") {
            BackupScreen(
                onBack = { nav.popBackStack() },
                // Restore replaces the database, so re-run boot: it re-logs the
                // super-admin in and lands on the dashboard. (It used to sign out to a
                // login screen that no longer exists.)
                onRestored = { nav.navigate("boot") { popUpTo(0) { inclusive = true } } },
                onOpenMergeLog = { nav.navigate("mergelog") }
            )
        }
        composable("users") {
            UsersScreen(onBack = { nav.popBackStack() })
        }
        composable("diary") {
            DiaryListScreen(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("diary/edit/$id") }
            )
        }
        composable(
            route = "diary/edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            DiaryEditScreen(
                entryId = entry.arguments?.getLong("id") ?: 0L,
                onBackRequested = { nav.popBackStack() }
            )
        }
    }

    // A small handle on the right edge, only on the screens worth parking. Deliberately
    // off to the side: entry screens already use the top bar and the bottom of the screen.
    if (com.billing.pos.ui.common.ParkedScreens.isMinimizable(currentPattern)) {
        androidx.compose.material3.Surface(
            onClick = {
                com.billing.pos.ui.common.ParkedScreens.minimize(
                    com.billing.pos.ui.common.ParkedScreens.labelFor(currentPattern ?: "")
                )
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = 16.dp, bottomStart = 16.dp
            ),
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.CenterEnd)
                .padding(vertical = 2.dp)
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                contentDescription = "Minimise this screen",
                tint = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 10.dp)
            )
        }
    }
    }
}
