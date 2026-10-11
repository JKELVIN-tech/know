package com.knowapp.android.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knowapp.android.data.model.UserOut
import com.knowapp.android.data.model.resolveMediaUrl
import com.knowapp.android.ui.components.FilterPill
import com.knowapp.android.ui.components.SkeletonRows
import com.knowapp.android.ui.components.UserAvatar
import com.knowapp.android.ui.components.generatePassword
import com.knowapp.android.ui.components.lastSeen
import com.knowapp.android.ui.components.relativeDay
import com.knowapp.android.ui.theme.DangerRed
import com.knowapp.android.ui.theme.DisplayFontFamily
import com.knowapp.android.ui.theme.IncomeGreen
import com.knowapp.android.ui.theme.PillShape
import com.knowapp.android.ui.theme.SmallRadius

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val state = viewModel.uiState
    val snackbar = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }
    val rows = state.visible

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold) },
                navigationIcon = { if (showBack) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.clearSubmitError(); creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New account") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        label = { Text("Search name or username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill(state.roleFilter.label, RoleFilter.values().map { it.label }, { l -> RoleFilter.values().firstOrNull { it.label == l }?.let(viewModel::setRoleFilter) }, Modifier.width(140.dp))
                        FilterPill(state.statusFilter.label, StatusFilter.values().map { it.label }, { l -> StatusFilter.values().firstOrNull { it.label == l }?.let(viewModel::setStatusFilter) }, Modifier.width(150.dp))
                        if (state.isSuperAdmin) {
                            val all = "All institutions"
                            FilterPill(
                                state.institutions.firstOrNull { it.id == state.institutionFilter }?.name ?: all,
                                listOf(all) + state.institutions.map { it.name },
                                { l -> viewModel.setInstitutionFilter(state.institutions.firstOrNull { it.name == l }?.id) },
                                Modifier.width(190.dp),
                            )
                        }
                    }
                    Text(
                        "${rows.size} ${if (rows.size == 1) "account" else "accounts"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                state.isLoading && state.users.isEmpty() -> item { SkeletonRows() }
                state.errorMessage != null && state.users.isEmpty() -> item {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.errorMessage, color = DangerRed)
                        TextButton(onClick = viewModel::refresh) { Text("Try again") }
                    }
                }
                rows.isEmpty() -> item { Text("No accounts match.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }
                else -> {
                    // The super admin sees accounts grouped under their institution
                    val groups = if (state.isSuperAdmin) rows.groupBy { it.institutionName ?: "Platform team" }.toSortedMap() else mapOf("" to rows)
                    groups.forEach { (group, members) ->
                        item(key = "group-$group") {
                            Column {
                                if (group.isNotEmpty()) {
                                    Text(
                                        "${group.uppercase()}  ·  ${members.size}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                                        .padding(horizontal = 14.dp),
                                ) {
                                    members.forEachIndexed { index, user ->
                                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                        AccountRow(user) { viewModel.select(user.id) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        CreateAccountSheet(
            title = "New account",
            roleOptions = if (state.isSuperAdmin) listOf("staff", "institution_admin", "super_admin") else listOf("staff"),
            institutions = state.institutions.map { it.id to it.name },
            fixedInstitutionId = null,
            submitting = state.isSubmitting,
            error = state.submitError,
            onDismiss = { creating = false },
            onSubmit = { u, n, p, r, t, i, result -> viewModel.create(u, n, p, r, t, i, result) },
        )
    }

    state.selected?.let { user ->
        AccountSheet(
            user = user,
            canManage = true,
            onDismiss = { viewModel.select(null) },
            onToggle = { viewModel.setActive(user.id, !user.isActive) },
            onReset = { password, done -> viewModel.resetPassword(user.id, password, done) },
            onRename = { name -> viewModel.rename(user.id, name) },
        )
    }
}

@Composable
private fun AccountRow(user: UserOut, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(user.fullName, resolveMediaUrl(user.avatarPath), 42.dp)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(user.fullName, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("@${user.username} · ${ROLE_LABELS[user.role] ?: user.role}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(if (user.isActive) IncomeGreen else DangerRed, CircleShape))
                Text(if (user.isActive) "Active" else "Off", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
            }
            Text(lastSeen(user.lastActiveAt).removePrefix("Active "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    user: UserOut,
    canManage: Boolean,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onReset: (String, (Boolean) -> Unit) -> Unit,
    onRename: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmToggle by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(user.fullName, resolveMediaUrl(user.avatarPath), 64.dp)
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(user.fullName, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("@${user.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailRow("Role", (ROLE_LABELS[user.role] ?: user.role) + if (user.role == "staff" && user.staffType == "teacher") " · Teacher" else "")
                DetailRow("Institution", user.institutionName ?: "Platform team")
                DetailRow("Status", if (user.isActive) "Active" else "Deactivated")
                DetailRow("Last active", lastSeen(user.lastActiveAt).removePrefix("Active "))
                DetailRow("Created", relativeDay(user.createdAt))
            }
            if (canManage) {
                Button(onClick = { resetting = true }, shape = PillShape, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Reset password", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = { renaming = true }, shape = PillShape, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Edit name", fontWeight = FontWeight.SemiBold) }
                OutlinedButton(
                    onClick = { confirmToggle = true },
                    shape = PillShape,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (user.isActive) "Deactivate account" else "Reactivate account", color = if (user.isActive) DangerRed else IncomeGreen, fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (confirmToggle) {
        AlertDialog(
            onDismissRequest = { confirmToggle = false },
            title = { Text(if (user.isActive) "Deactivate ${user.fullName}?" else "Reactivate ${user.fullName}?") },
            text = { Text(if (user.isActive) "They won't be able to sign in until you reactivate them. Their records stay." else "They will be able to sign in again.") },
            confirmButton = { TextButton(onClick = { confirmToggle = false; onToggle(); onDismiss() }) { Text(if (user.isActive) "Deactivate" else "Reactivate", color = if (user.isActive) DangerRed else IncomeGreen) } },
            dismissButton = { TextButton(onClick = { confirmToggle = false }) { Text("Cancel") } },
        )
    }

    if (renaming) {
        var name by remember { mutableStateOf(user.fullName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Edit name") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, shape = SmallRadius) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { renaming = false; onRename(name.trim()) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    if (resetting) {
        ResetPasswordSheet(user = user, onDismiss = { resetting = false }, onReset = onReset)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResetPasswordSheet(user: UserOut, onDismiss: () -> Unit, onReset: (String, (Boolean) -> Unit) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var password by remember { mutableStateOf(generatePassword()) }
    var done by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Reset password", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            if (done) {
                Text("${user.fullName}'s password is now:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(password, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.fillMaxWidth().background(IncomeGreen.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(16.dp))
                Text("Share it with them now. It won't be shown again.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { clipboard.setText(AnnotatedString("KNOW login\nUsername: ${user.username}\nPassword: $password")) }, shape = PillShape, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Copy login details", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = onDismiss, shape = PillShape, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
            } else {
                Text("Choose a new temporary password for ${user.fullName}. They'll be signed out of other devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("New password") }, supportingText = { Text("At least 8 characters") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
                TextButton(onClick = { password = generatePassword() }) { Text("Generate a new password") }
                Button(
                    onClick = { busy = true; password = password.trim(); onReset(password) { ok -> busy = false; if (ok) done = true } },
                    enabled = !busy && password.trim().length >= 8,
                    shape = PillShape,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (busy) "Resetting…" else "Reset password", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
