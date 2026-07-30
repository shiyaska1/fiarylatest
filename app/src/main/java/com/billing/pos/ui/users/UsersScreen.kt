package com.billing.pos.ui.users

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billing.pos.data.Repository
import com.billing.pos.data.Role
import com.billing.pos.data.User
import com.billing.pos.ui.billing.collectAsStateSafe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UsersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val users: StateFlow<List<User>> =
        repo.users.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val message = MutableStateFlow<String?>(null)
    fun consumeMessage() { message.value = null }

    fun create(user: User, password: String, onDone: () -> Unit) {
        if (user.username.isBlank() || password.isBlank()) { message.value = "Username and password required"; return }
        viewModelScope.launch {
            val result = repo.createUser(user, password)
            if (result.isSuccess) { message.value = "User created"; onDone() }
            else message.value = "Username already exists"
        }
    }

    fun update(user: User, newPassword: String, onDone: () -> Unit) {
        if (user.username.isBlank()) { message.value = "Username required"; return }
        viewModelScope.launch {
            val result = repo.updateUser(user, newPassword)
            if (result.isSuccess) { message.value = "User updated"; onDone() }
            else message.value = "Username already exists"
        }
    }

    fun delete(user: User) {
        if (user.role == Role.SUPER_USER) { message.value = "Cannot delete a super user"; return }
        viewModelScope.launch { repo.deleteUser(user); message.value = "User deleted" }
    }
}

/** Default permission set implied by a role (super user may still tweak). */
private fun defaultPerms(role: Role): User = when (role) {
    Role.SUPER_USER -> User(
        username = "", passwordHash = "", role = role,
        canCreateInvoice = true, canEditInvoice = true, canDeleteInvoice = true, canViewInvoice = true,
        canCreateReceipt = true, canEditReceipt = true, canDeleteReceipt = true, canViewReceipt = true,
        canCreatePayment = true, canEditPayment = true, canDeletePayment = true, canViewPayment = true,
        canViewCashbook = true, canExport = true, canImport = true, canManageUsers = true
    )
    Role.ADMIN -> User(
        username = "", passwordHash = "", role = role,
        canCreateInvoice = true, canEditInvoice = true, canDeleteInvoice = true, canViewInvoice = true,
        canCreateReceipt = true, canEditReceipt = true, canDeleteReceipt = true, canViewReceipt = true,
        canCreatePayment = true, canEditPayment = true, canDeletePayment = true, canViewPayment = true,
        canViewCashbook = true, canExport = true, canImport = true, canManageUsers = false
    )
    Role.SALESMAN -> User(
        username = "", passwordHash = "", role = role,
        canCreateInvoice = true, canEditInvoice = false, canDeleteInvoice = false, canViewInvoice = true,
        canCreateReceipt = false, canEditReceipt = false, canDeleteReceipt = false, canViewReceipt = false,
        canCreatePayment = false, canEditPayment = false, canDeletePayment = false, canViewPayment = false,
        canViewCashbook = false, canExport = true, canImport = false, canManageUsers = false
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(
    onBack: () -> Unit,
    vm: UsersViewModel = viewModel()
) {
    val users by vm.users.collectAsStateSafe()
    var editing by remember { mutableStateOf<User?>(null) }   // the user being edited
    var showDialog by remember { mutableStateOf(false) }
    var creatingNew by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Users") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            FloatingActionButton(onClick = { editing = null; creatingNew = true; showDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add user")
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
        ) {
            items(users, key = { it.id }) { u ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editing = u; creatingNew = false; showDialog = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(u.username, fontWeight = FontWeight.Bold)
                        Text(u.role.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text(permSummary(u), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (u.role != Role.SUPER_USER) {
                        IconButton(onClick = { vm.delete(u) }) {
                            Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Divider()
            }
        }
    }

    if (showDialog) {
        UserDialog(
            existing = editing,
            onDismiss = { showDialog = false },
            onSubmit = { user, password ->
                if (creatingNew) vm.create(user, password) { showDialog = false }
                else vm.update(user, password) { showDialog = false }
            }
        )
    }
}

private fun permSummary(u: User): String = buildList {
    if (u.canViewInvoice) add("inv:view")
    if (u.canCreateInvoice) add("inv:new")
    if (u.canEditInvoice) add("inv:edit")
    if (u.canDeleteInvoice) add("inv:del")
    if (u.canViewReceipt) add("rcpt:view")
    if (u.canCreateReceipt) add("rcpt:new")
    if (u.canEditReceipt) add("rcpt:edit")
    if (u.canDeleteReceipt) add("rcpt:del")
    if (u.canViewPayment) add("pay:view")
    if (u.canCreatePayment) add("pay:new")
    if (u.canEditPayment) add("pay:edit")
    if (u.canDeletePayment) add("pay:del")
    if (u.canViewCashbook) add("cashbook")
    if (u.canExport) add("export")
    if (u.canImport) add("import")
    if (u.canManageUsers) add("users")
}.joinToString(", ").ifEmpty { "no permissions" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserDialog(
    existing: User?,
    onDismiss: () -> Unit,
    onSubmit: (User, String) -> Unit
) {
    val isEdit = existing != null
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(existing?.role ?: Role.SALESMAN) }
    var perms by remember { mutableStateOf(existing ?: defaultPerms(Role.SALESMAN)) }
    var roleExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit User" else "New User") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Username *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(if (isEdit) "New password (blank = keep)" else "Password *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )

                ExposedDropdownMenuBox(
                    expanded = roleExpanded,
                    onExpandedChange = { roleExpanded = it },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = role.label,
                        onValueChange = {},
                        label = { Text("Role") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(roleExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = roleExpanded, onDismissRequest = { roleExpanded = false }) {
                        Role.values().forEach { r ->
                            DropdownMenuItem(
                                text = { Text(r.label) },
                                onClick = { role = r; perms = defaultPerms(r).copy(username = username); roleExpanded = false }
                            )
                        }
                    }
                }

                Section("Invoice")
                PermRow("View invoices", perms.canViewInvoice) { perms = perms.copy(canViewInvoice = it) }
                PermRow("Create invoice", perms.canCreateInvoice) { perms = perms.copy(canCreateInvoice = it) }
                PermRow("Edit invoice", perms.canEditInvoice) { perms = perms.copy(canEditInvoice = it) }
                PermRow("Delete invoice", perms.canDeleteInvoice) { perms = perms.copy(canDeleteInvoice = it) }

                Section("Receipt")
                PermRow("View receipts", perms.canViewReceipt) { perms = perms.copy(canViewReceipt = it) }
                PermRow("Create receipt", perms.canCreateReceipt) { perms = perms.copy(canCreateReceipt = it) }
                PermRow("Edit receipt", perms.canEditReceipt) { perms = perms.copy(canEditReceipt = it) }
                PermRow("Delete receipt", perms.canDeleteReceipt) { perms = perms.copy(canDeleteReceipt = it) }

                Section("Payment / expense")
                PermRow("View payments", perms.canViewPayment) { perms = perms.copy(canViewPayment = it) }
                PermRow("Create payment", perms.canCreatePayment) { perms = perms.copy(canCreatePayment = it) }
                PermRow("Edit payment", perms.canEditPayment) { perms = perms.copy(canEditPayment = it) }
                PermRow("Delete payment", perms.canDeletePayment) { perms = perms.copy(canDeletePayment = it) }

                Section("Reports")
                PermRow("View cash book", perms.canViewCashbook) { perms = perms.copy(canViewCashbook = it) }

                Section("Data & admin")
                PermRow("Export / backup", perms.canExport) { perms = perms.copy(canExport = it) }
                PermRow("Import data", perms.canImport) { perms = perms.copy(canImport = it) }
                PermRow("Manage users", perms.canManageUsers) { perms = perms.copy(canManageUsers = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSubmit(
                    perms.copy(id = existing?.id ?: 0, username = username.trim(), role = role),
                    password
                )
            }) { Text(if (isEdit) "Save" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun PermRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
}
