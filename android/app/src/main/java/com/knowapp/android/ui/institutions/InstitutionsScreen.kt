package com.knowapp.android.ui.institutions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.knowapp.android.data.KENYA_COUNTIES
import com.knowapp.android.data.model.InstitutionStatsOut
import com.knowapp.android.data.model.UserOut
import com.knowapp.android.data.model.resolveMediaUrl
import com.knowapp.android.ui.accounts.CreateAccountSheet
import com.knowapp.android.ui.accounts.ROLE_LABELS
import com.knowapp.android.ui.components.FilterPill
import com.knowapp.android.ui.components.SimpleDropdown
import com.knowapp.android.ui.components.SkeletonRows
import com.knowapp.android.ui.components.UserAvatar
import com.knowapp.android.ui.components.daysSince
import com.knowapp.android.ui.components.lastSeen
import com.knowapp.android.ui.theme.DangerRed
import com.knowapp.android.ui.theme.DisplayFontFamily
import com.knowapp.android.ui.theme.IncomeGreen
import com.knowapp.android.ui.theme.PillShape
import com.knowapp.android.ui.theme.SmallRadius
import com.knowapp.android.ui.theme.WarnAmber

private val TYPE_SUGGESTIONS = listOf("School", "Shop", "Supermarket", "Canteen", "Pharmacy", "Other")

private fun isQuiet(inst: InstitutionStatsOut): Boolean = (daysSince(inst.lastActivityAt) ?: Long.MAX_VALUE) > 14

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstitutionsScreen(
    viewModel: InstitutionsViewModel,
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
                title = { Text("Institutions", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold) },
                navigationIcon = { if (showBack) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.clearSubmitError(); creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add institution") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        label = { Text("Search name, type or county") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterPill(
                            state.sort.label,
                            InstitutionSort.values().map { it.label },
                            { l -> InstitutionSort.values().firstOrNull { it.label == l }?.let(viewModel::setSort) },
                            Modifier.weight(1f),
                        )
                        Text("${rows.size} ${if (rows.size == 1) "institution" else "institutions"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when {
                state.isLoading && state.items.isEmpty() -> item { SkeletonRows() }
                state.errorMessage != null && state.items.isEmpty() -> item {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.errorMessage, color = DangerRed)
                        TextButton(onClick = viewModel::refresh) { Text("Try again") }
                    }
                }
                rows.isEmpty() -> item { Text("No institutions match.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }
                else -> items(rows, key = { it.id }) { inst -> InstitutionCard(inst) { viewModel.select(inst.id) } }
            }
        }
    }

    if (creating) {
        CreateInstitutionSheet(
            submitting = state.isSubmitting,
            error = state.submitError,
            onDismiss = { creating = false },
            onSubmit = { name, type, address, region, partnerCode -> viewModel.create(name, type, address, region, partnerCode) { ok -> if (ok) creating = false } },
        )
    }

    state.selected?.let { inst ->
        InstitutionSheet(
            inst = inst,
            members = state.users.filter { it.institutionId == inst.id }.sortedWith(compareBy({ it.role == "staff" }, { it.fullName.lowercase() })),
            submitting = state.isSubmitting,
            error = state.submitError,
            onDismiss = { viewModel.select(null) },
            onCreateAccount = { u, n, p, r, t, i, e, result -> viewModel.createAccount(u, n, p, r, t, i, e, result) },
        )
    }
}

@Composable
private fun Seal(inst: InstitutionStatsOut, size: Int = 48) {
    val logo = resolveMediaUrl(inst.logoPath)
    if (logo != null) {
        AsyncImage(model = logo, contentDescription = inst.name, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)))
    } else {
        Box(modifier = Modifier.size(size.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text(inst.name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = (size * 0.4f).sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun InstitutionCard(inst: InstitutionStatsOut, onClick: () -> Unit) {
    val quiet = isQuiet(inst)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Seal(inst)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(inst.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text(listOfNotNull(inst.type.replaceFirstChar { it.uppercase() }, inst.region).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(if (quiet) WarnAmber else IncomeGreen, CircleShape))
                Text(if (quiet) "Quiet" else "Active", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("${inst.staffCount}", "staff", Modifier.weight(1f))
            MiniStat("${inst.adminCount}", if (inst.adminCount == 1) "admin" else "admins", Modifier.weight(1f))
            MiniStat("${inst.entries30d}", "entries / 30d", Modifier.weight(1.3f))
        }
        Text(lastSeen(inst.lastActivityAt), fontSize = 12.sp, color = if (quiet) WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstitutionSheet(
    inst: InstitutionStatsOut,
    members: List<UserOut>,
    submitting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreateAccount: (String, String, String, String, String?, Int?, String, (Boolean) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var addingAccount by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Seal(inst, 64)
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(inst.name, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(listOfNotNull(inst.type.replaceFirstChar { it.uppercase() }, inst.region).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            inst.address?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("${inst.activeAccounts}", "active accounts", Modifier.weight(1f))
                MiniStat("${inst.inactiveAccounts}", "deactivated", Modifier.weight(1f))
                MiniStat("${inst.entries30d}", "entries / 30d", Modifier.weight(1f))
            }
            Text(lastSeen(inst.lastActivityAt), fontSize = 13.sp, color = if (isQuiet(inst)) WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { addingAccount = true }, shape = PillShape, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Add an account here", fontWeight = FontWeight.Bold)
            }
            Text("PEOPLE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (members.isEmpty()) {
                Text("No accounts yet. Add an owner first so they can set up their own staff.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp),
                ) {
                    members.forEachIndexed { index, u ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            UserAvatar(u.fullName, resolveMediaUrl(u.avatarPath), 36.dp)
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(u.fullName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                                Text("${ROLE_LABELS[u.role] ?: u.role} · ${lastSeen(u.lastActiveAt).removePrefix("Active ")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Box(modifier = Modifier.size(8.dp).background(if (u.isActive) IncomeGreen else DangerRed, CircleShape))
                        }
                    }
                }
            }
        }
    }

    if (addingAccount) {
        CreateAccountSheet(
            title = "New account for ${inst.name}",
            roleOptions = listOf("institution_admin", "staff"),
            institutions = listOf(inst.id to inst.name),
            fixedInstitutionId = inst.id,
            submitting = submitting,
            error = error,
            onDismiss = { addingAccount = false },
            onSubmit = onCreateAccount,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CreateInstitutionSheet(
    submitting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (name: String, type: String, address: String?, region: String?, partnerCode: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("School") }
    var customType by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var partnerCode by remember { mutableStateOf("") }
    val finalType = if (type == "Other") customType.trim() else type

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Add institution", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
            Text("TYPE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TYPE_SUGGESTIONS.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) }) }
            }
            if (type == "Other") {
                OutlinedTextField(value = customType, onValueChange = { customType = it }, label = { Text("What kind of institution?") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
            }
            SimpleDropdown("County (optional)", listOf("Not set") + KENYA_COUNTIES, region.ifBlank { "Not set" }, { region = if (it == "Not set") "" else it })
            OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address (optional)") }, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
            error?.let {
                Text(it, color = DangerRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().background(DangerRed.copy(alpha = 0.12f), SmallRadius).padding(12.dp))
            }
            OutlinedTextField(value = partnerCode, onValueChange = { partnerCode = it.uppercase() }, label = { Text("Partner code (optional)") }, supportingText = { Text("The agent who brought this business earns a share of its monthly fee") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
            Button(
                onClick = { onSubmit(name.trim(), finalType, address.trim().ifBlank { null }, region.ifBlank { null }, partnerCode.trim().ifBlank { null }) },
                enabled = !submitting && name.isNotBlank() && finalType.isNotBlank(),
                shape = PillShape,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text(if (submitting) "Adding…" else "Add institution", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
}
