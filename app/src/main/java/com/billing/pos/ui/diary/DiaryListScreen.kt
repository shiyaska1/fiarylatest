package com.billing.pos.ui.diary

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.DiaryAttachment
import com.billing.pos.data.DiaryEntry
import com.billing.pos.data.DiaryRepository
import com.billing.pos.ui.billing.collectAsStateSafe
import com.billing.pos.util.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The current list, plus whether the date filter had to be dropped to find matches. */
data class DiaryListResult(val entries: List<DiaryEntry>, val dateFilterBypassed: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DiaryRepository(app)

    val query = MutableStateFlow("")
    // Default window: the last 3 months.
    val fromMillis = MutableStateFlow(threeMonthsAgo())
    val toMillis = MutableStateFlow(endOfToday())

    /** 0 = All types. */
    val typeId = MutableStateFlow(0L)
    /** Date range on by default; unticking it searches every date. */
    val useDateRange = MutableStateFlow(true)

    val types: StateFlow<List<com.billing.pos.data.DiaryType>> =
        repo.types.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private data class Filter(
        val q: String, val from: Long, val to: Long, val typeId: Long, val useDate: Boolean
    )
    private val filter = combine(query, fromMillis, toMillis, typeId, useDateRange) { a ->
        Filter((a[0] as String).trim(), a[1] as Long, a[2] as Long, a[3] as Long, a[4] as Boolean)
    }

    /**
     * Entries within the date range, reminders first then newest first. When a search finds
     * nothing in the range, the date filter is dropped and the search runs over all dates —
     * so a match outside the window is still found, and the UI can say the filter was ignored.
     */
    private val baseResult = filter
        .flatMapLatest { f ->
            val anyType = f.typeId == 0L
            repo.searchFiltered(f.q, anyType, f.typeId, f.useDate, f.from, f.to)
                .flatMapLatest { inRange ->
                    if (f.q.isNotBlank() && f.useDate && inRange.isEmpty()) {
                        // Nothing in the range — drop the dates and search everything.
                        repo.searchFiltered(f.q, anyType, f.typeId, false, f.from, f.to)
                            .map { DiaryListResult(it, dateFilterBypassed = true) }
                    } else {
                        kotlinx.coroutines.flow.flowOf(DiaryListResult(inRange, dateFilterBypassed = false))
                    }
                }
        }

    // ---- customer filters (default: all) ----
    private val posRepo = com.billing.pos.data.Repository(app)
    val customers: StateFlow<List<com.billing.pos.data.Customer>> =
        posRepo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val customerFilter = MutableStateFlow("")
    val customerTypeFilter = MutableStateFlow("")

    val result: StateFlow<DiaryListResult> =
        combine(baseResult, customerFilter, customerTypeFilter, customers) { r, cf, ctf, custs ->
            if (cf.isBlank() && ctf.isBlank()) r
            else {
                val typeOf = custs.associate { it.name.lowercase() to it.customerType }
                DiaryListResult(
                    r.entries.filter { e ->
                        (cf.isBlank() || e.customerName.equals(cf, true)) &&
                            (ctf.isBlank() || (typeOf[e.customerName.lowercase()] ?: "").equals(ctf, true))
                    },
                    r.dateFilterBypassed
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DiaryListResult(emptyList(), false))

    fun setTypeId(id: Long) { typeId.value = id }
    fun setUseDateRange(on: Boolean) { useDateRange.value = on }
    fun setFrom(millis: Long) { fromMillis.value = millis }
    fun setTo(millis: Long) { toMillis.value = millis }

    private fun threeMonthsAgo(): Long = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.MONTH, -3)
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun endOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59)
        set(java.util.Calendar.SECOND, 59); set(java.util.Calendar.MILLISECOND, 999)
    }.timeInMillis

    /** Count of media (attachments + non-text blocks) per entry, for the list badge. */
    val mediaCounts: StateFlow<Map<Long, Int>> =
        combine(repo.allAttachments, repo.allBlocks) { atts, blocks ->
            val a = atts.groupingBy { it.entryId }.eachCount()
            val b = blocks.filter { it.type != com.billing.pos.data.BlockType.TEXT }.groupingBy { it.entryId }.eachCount()
            (a.keys + b.keys).associateWith { (a[it] ?: 0) + (b[it] ?: 0) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setQuery(q: String) { query.value = q }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryListScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    vm: DiaryListViewModel = viewModel()
) {
    val result by vm.result.collectAsStateSafe()
    val entries = result.entries
    val counts by vm.mediaCounts.collectAsStateSafe()
    val query by vm.query.collectAsStateSafe()
    val from by vm.fromMillis.collectAsStateSafe()
    val to by vm.toMillis.collectAsStateSafe()
    val typeId by vm.typeId.collectAsStateSafe()
    val useDates by vm.useDateRange.collectAsStateSafe()
    val types by vm.types.collectAsStateSafe()
    var showTypePicker by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Hourly background voice diary — APK build only (the Play AAB ships no such service).
    var autoVoice by remember { mutableStateOf(com.billing.pos.data.AppPrefs(context).autoVoiceDiary) }
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { autoVoice = true; com.billing.pos.audio.AutoDiaryService.start(context) }
    }
    fun toggleAutoVoice() {
        if (autoVoice) {
            autoVoice = false
            com.billing.pos.audio.AutoDiaryService.stop(context)
        } else if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            autoVoice = true
            com.billing.pos.audio.AutoDiaryService.start(context)
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    fun pickDate(current: Long, onPicked: (Long) -> Unit) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = current }
        android.app.DatePickerDialog(
            context,
            { _, y, m, d ->
                onPicked(java.util.Calendar.getInstance().apply {
                    set(y, m, d, 0, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis)
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Diary") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (com.billing.pos.BuildConfig.DEBUG) {
                        IconButton(onClick = { toggleAutoVoice() }) {
                            Icon(
                                if (autoVoice) Icons.Filled.Mic else Icons.Filled.MicOff,
                                contentDescription = if (autoVoice) "Hourly voice diary on" else "Hourly voice diary off"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpen(0) }) {
                Icon(Icons.Filled.Add, contentDescription = "New entry")
            }
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.setQuery(it) },
                label = { Text("Search text / remarks") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )

            // Diary type filter — All by default.
            androidx.compose.material3.OutlinedButton(
                onClick = { showTypePicker = true },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Text("Type: " + (types.firstOrNull { it.id == typeId }?.name ?: "All"), maxLines = 1)
            }

            // Customer / customer-type filters — All by default.
            val customers by vm.customers.collectAsStateSafe()
            val custFilter by vm.customerFilter.collectAsStateSafe()
            val custTypeFilter by vm.customerTypeFilter.collectAsStateSafe()
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Type to search customers — five suggestions at a time; blank = all.
                var custMenu by remember { mutableStateOf(false) }
                var custQuery by remember { mutableStateOf("") }
                androidx.compose.material3.ExposedDropdownMenuBox(
                    expanded = custMenu, onExpandedChange = { custMenu = it }, modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = if (custMenu) custQuery else custFilter.ifBlank { "All customers" },
                        onValueChange = { custQuery = it; custMenu = true },
                        singleLine = true,
                        trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(custMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                            .onFocusChanged { fs -> if (fs.isFocused) { custQuery = ""; custMenu = true } }
                    )
                    val custMatches = customers
                        .filter { custQuery.isBlank() || it.name.contains(custQuery, true) || it.phone.contains(custQuery) }
                        .take(5)
                    ExposedDropdownMenu(expanded = custMenu, onDismissRequest = { custMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("All customers") }, onClick = { vm.customerFilter.value = ""; custMenu = false })
                        custMatches.forEach { c ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(c.name) }, onClick = { vm.customerFilter.value = c.name; custMenu = false })
                        }
                    }
                }
                var ctMenu by remember { mutableStateOf(false) }
                val custTypes = remember(customers) { customers.map { it.customerType }.filter { it.isNotBlank() }.distinct().sorted() }
                androidx.compose.material3.ExposedDropdownMenuBox(
                    expanded = ctMenu, onExpandedChange = { ctMenu = !ctMenu }, modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        readOnly = true, value = custTypeFilter.ifBlank { "All cust. types" }, onValueChange = {},
                        trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(ctMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = ctMenu, onDismissRequest = { ctMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("All cust. types") }, onClick = { vm.customerTypeFilter.value = ""; ctMenu = false })
                        custTypes.forEach { t ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(t) }, onClick = { vm.customerTypeFilter.value = t; ctMenu = false })
                        }
                    }
                }
            }

            // Untick to ignore the dates entirely.
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Checkbox(
                    checked = useDates,
                    onCheckedChange = { vm.setUseDateRange(it) }
                )
                Text("Filter by date range", style = MaterialTheme.typography.labelLarge)
            }

            // Date range — defaults to the last 3 months.
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { pickDate(from) { vm.setFrom(it) } },
                    enabled = useDates,
                    modifier = Modifier.weight(1f)
                ) { Text("From: ${Format.date(from)}", maxLines = 1) }
                androidx.compose.material3.OutlinedButton(
                    onClick = { pickDate(to) { vm.setTo(it) } },
                    enabled = useDates,
                    modifier = Modifier.weight(1f)
                ) { Text("To: ${Format.date(to)}", maxLines = 1) }
            }
            if (result.dateFilterBypassed) {
                Text(
                    "No match in that date range — showing results from all dates.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) { Text("No diary entries", color = MaterialTheme.colorScheme.outline) }
            } else {
                LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        DiaryRow(entry, counts[entry.id] ?: 0) { onOpen(entry.id) }
                        Divider()
                    }
                }
            }
        }
    }

    if (showTypePicker) {
        com.billing.pos.ui.common.SearchablePickDialog(
            title = "Filter by diary type",
            options = types.map { it.id to it.name },
            selectedId = typeId,
            onPick = { id -> vm.setTypeId(id); showTypePicker = false },
            onAdd = { showTypePicker = false },   // creating types belongs in the entry screen
            onDismiss = { showTypePicker = false },
            noneLabel = "All types"
        )
    }
}

@Composable
private fun DiaryRow(entry: DiaryEntry, attachmentCount: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.title.ifBlank { "(untitled)" },
                fontSize = entry.titleSize.sp,
                color = if (entry.titleColor == 0) Color.Unspecified else Color(entry.titleColor),
                fontWeight = if (entry.titleBold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (entry.titleItalic) FontStyle.Italic else FontStyle.Normal,
                modifier = Modifier.weight(1f)
            )
            if (entry.reminderEnabled) {
                Icon(
                    Icons.Filled.Notifications, contentDescription = "Reminder",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (attachmentCount > 0) {
                Icon(
                    Icons.Filled.AttachFile, contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp).padding(start = 4.dp)
                )
                Text("$attachmentCount", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (entry.remarks.isNotBlank()) {
            Text(
                entry.remarks,
                maxLines = 2,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Text(
            Format.dateTime(entry.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
