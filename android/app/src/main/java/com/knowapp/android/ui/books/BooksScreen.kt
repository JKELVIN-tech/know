package com.knowapp.android.ui.books

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knowapp.android.data.model.AccountBalanceOut
import com.knowapp.android.data.model.JournalEntryOut
import com.knowapp.android.data.model.LedgerLineOut
import com.knowapp.android.ui.components.FilterPill
import com.knowapp.android.ui.components.HeroCard
import com.knowapp.android.ui.components.SkeletonRows
import com.knowapp.android.ui.components.kes
import com.knowapp.android.ui.components.kesNumber
import com.knowapp.android.ui.statements.dateOf
import com.knowapp.android.ui.theme.DangerRed
import com.knowapp.android.ui.theme.DisplayFontFamily
import com.knowapp.android.ui.theme.IncomeGreen
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_W = 46.dp
private val AMT_W = 78.dp

private fun amt(raw: String): BigDecimal = raw.toBigDecimalOrNull() ?: BigDecimal.ZERO

private fun shortDate(iso: String): String {
    val d = dateOf(iso)
    return d.format(DateTimeFormatter.ofPattern(if (d.year == LocalDate.now().year) "d MMM" else "d MMM yy"))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BooksScreen(
    viewModel: BooksViewModel,
    onBack: () -> Unit,
    showBack: Boolean = true,
    onViewAudits: () -> Unit = {},
    onViewStatements: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val data = state.data
    val collective = state.selectedStaffId == null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Books", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold) },
                navigationIcon = { if (showBack) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } } },
                actions = {
                    TextButton(onClick = onViewStatements) { Text("Statements") }
                    TextButton(onClick = onViewAudits) { Text("Audits") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.loading && data != null, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                        val everyone = "All staff (collective)"
                        FilterPill(
                            value = state.staffName ?: everyone,
                            options = listOf(everyone) + state.staff.map { it.fullName },
                            onSelect = { choice -> viewModel.selectStaff(state.staff.firstOrNull { it.fullName == choice }?.id) },
                            modifier = Modifier.weight(1.35f),
                        )
                        FilterPill(
                            value = state.period.label,
                            options = BooksPeriod.values().map { it.label },
                            onSelect = { label -> BooksPeriod.values().firstOrNull { it.label == label }?.let(viewModel::selectPeriod) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (data == null) {
                    item {
                        if (state.error != null) {
                            Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(state.error ?: "", color = DangerRed)
                                TextButton(onClick = viewModel::refresh) { Text("Try again") }
                            }
                        } else SkeletonRows(count = 4)
                    }
                } else {
                    item {
                        HeroCard {
                            Text(
                                "NET  ·  ${state.period.label.uppercase()}${state.staffName?.let { "  ·  ${it.uppercase()}" } ?: ""}",
                                color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, maxLines = 1,
                            )
                            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)) {
                                Text("KES", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp, bottom = 5.dp))
                                Text(kesNumber(amt(data.summary.net)), color = Color.White, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, maxLines = 1)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BooksTile("Income", kesNumber(amt(data.summary.income)), Modifier.weight(1f))
                                BooksTile("Expense", kesNumber(amt(data.summary.expenses)), Modifier.weight(1f))
                            }
                            Text("HELD NOW  ·  KES ${kesNumber(amt(data.summary.totalMoney))}", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                data.summary.money.forEach { BooksTile(it.name, kesNumber(amt(it.balance)), Modifier.weight(1f)) }
                            }
                        }
                    }

                    item {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp)) {
                            BooksView.values().forEachIndexed { index, v ->
                                SegmentedButton(
                                    selected = state.view == v,
                                    onClick = { viewModel.selectView(v) },
                                    shape = SegmentedButtonDefaults.itemShape(index, BooksView.values().size),
                                    label = { Text(v.label, maxLines = 1, softWrap = false, fontSize = 13.sp) },
                                )
                            }
                        }
                    }

                    when (state.view) {
                        BooksView.JOURNAL -> {
                            if (data.journal.isEmpty()) {
                                item { EmptyNote("No entries in this period.") }
                            } else {
                                stickyHeader { TableHeader("Date", "Details", "Debit", "Credit", topRounded = true) }
                                // Oldest first, as a journal is kept
                                items(data.journal.sortedWith(compareBy({ it.entryDate }, { it.id })), key = { it.id }) { entry ->
                                    JournalEntryRow(entry, showWho = collective)
                                }
                                item {
                                    val debit = data.journal.flatMap { it.lines }.fold(BigDecimal.ZERO) { a, l -> a + amt(l.debit) }
                                    val credit = data.journal.flatMap { it.lines }.fold(BigDecimal.ZERO) { a, l -> a + amt(l.credit) }
                                    TotalsRow("Total", debit, credit)
                                }
                            }
                        }
                        BooksView.TRIAL -> item { TrialBalanceCard(data.trialBalance.accounts) }
                        BooksView.ACCOUNTS -> {
                            val order = listOf("asset" to "Assets", "liability" to "Liabilities", "equity" to "Equity", "income" to "Income", "expense" to "Expenses")
                            order.forEach { (type, title) ->
                                val accounts = data.trialBalance.accounts.filter { it.type == type }
                                if (accounts.isNotEmpty()) {
                                    item(key = "acct-$type") {
                                        Column(modifier = Modifier.padding(bottom = 14.dp)) {
                                            Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                                                    .padding(horizontal = 14.dp),
                                            ) {
                                                accounts.forEachIndexed { i, a ->
                                                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().clickable { viewModel.openLedger(a) }.padding(vertical = 14.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                            Text("Account ${a.code}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                        Text(kes(amt(a.balance)), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            item { Text("Tap an account to see its T-account.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }

    state.ledgerAccount?.let { account ->
        LedgerSheet(account = account, lines = state.ledger?.lines, loading = state.ledgerLoading, onDismiss = viewModel::closeLedger)
    }
}

@Composable
private fun BooksTile(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier.background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 10.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp), textAlign = TextAlign.Center)
}

@Composable
private fun TableHeader(date: String, details: String, debit: String, credit: String, topRounded: Boolean = false) {
    val shape = if (topRounded) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp) else RoundedCornerShape(0.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.colorScheme.onSecondaryContainer
        Text(date, modifier = Modifier.width(DATE_W), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = style)
        Text(details, modifier = Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = style)
        Text(debit, modifier = Modifier.width(AMT_W), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = style, textAlign = TextAlign.End)
        Text(credit, modifier = Modifier.width(AMT_W), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = style, textAlign = TextAlign.End)
    }
}

@Composable
private fun JournalEntryRow(entry: JournalEntryOut, showWho: Boolean) {
    val debits = entry.lines.filter { amt(it.debit).signum() > 0 }
    val credits = entry.lines.filter { amt(it.credit).signum() > 0 }
    val narration = listOfNotNull(
        entry.memo?.takeIf { it.isNotBlank() },
        entry.reference?.takeIf { it.isNotBlank() }?.let { "Ref $it" },
        if (showWho) entry.recordedByName?.let { "by $it" } else null,
    ).joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        debits.forEachIndexed { i, line ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(if (i == 0) shortDate(entry.entryDate) else "", modifier = Modifier.width(DATE_W), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${line.accountName}  Dr", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(kesNumber(amt(line.debit)), modifier = Modifier.width(AMT_W), fontSize = 12.sp, textAlign = TextAlign.End)
                Spacer(Modifier.width(AMT_W))
            }
        }
        credits.forEach { line ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(DATE_W))
                Text("To ${line.accountName}", modifier = Modifier.weight(1f).padding(start = 16.dp), fontSize = 13.sp)
                Spacer(Modifier.width(AMT_W))
                Text(kesNumber(amt(line.credit)), modifier = Modifier.width(AMT_W), fontSize = 12.sp, textAlign = TextAlign.End)
            }
        }
        if (narration.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Spacer(Modifier.width(DATE_W))
                Text("($narration)", fontSize = 11.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}

@Composable
private fun TotalsRow(label: String, debit: BigDecimal, credit: BigDecimal) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(kesNumber(debit), modifier = Modifier.width(AMT_W), fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.End)
        Text(kesNumber(credit), modifier = Modifier.width(AMT_W), fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.End)
    }
}

@Composable
private fun TrialBalanceCard(accounts: List<AccountBalanceOut>) {
    // Each account's balance sits in its debit or its credit column, and the two columns must agree
    val rows = accounts.map { a -> a to (amt(a.debit) - amt(a.credit)) }.filter { it.second.signum() != 0 }
    val debitTotal = rows.filter { it.second.signum() > 0 }.fold(BigDecimal.ZERO) { a, r -> a + r.second }
    val creditTotal = rows.filter { it.second.signum() < 0 }.fold(BigDecimal.ZERO) { a, r -> a + r.second.negate() }
    val balanced = debitTotal.compareTo(creditTotal) == 0

    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Trial balance", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("As at ${LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy"))}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (balanced) "Balanced" else "Out of balance", color = if (balanced) IncomeGreen else DangerRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        TableHeader("", "Account", "Debit", "Credit", topRounded = true)
        rows.forEach { (a, bal) ->
            Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${a.code}", modifier = Modifier.width(DATE_W), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(a.name, modifier = Modifier.weight(1f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (bal.signum() > 0) kesNumber(bal) else "", modifier = Modifier.width(AMT_W), fontSize = 12.sp, textAlign = TextAlign.End)
                    Text(if (bal.signum() < 0) kesNumber(bal.negate()) else "", modifier = Modifier.width(AMT_W), fontSize = 12.sp, textAlign = TextAlign.End)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            }
        }
        TotalsRow("Total", debitTotal, creditTotal)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerSheet(account: AccountBalanceOut, lines: List<LedgerLineOut>?, loading: Boolean, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("${account.name} account", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Account ${account.code}  ·  T-account", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
            if (loading) {
                SkeletonRows(count = 2)
            } else if (lines == null) {
                Text("Couldn't load this account.", color = DangerRed)
            } else {
                val debits = lines.filter { amt(it.debit).signum() > 0 }
                val credits = lines.filter { amt(it.credit).signum() > 0 }
                val debitTotal = debits.fold(BigDecimal.ZERO) { a, l -> a + amt(l.debit) }
                val creditTotal = credits.fold(BigDecimal.ZERO) { a, l -> a + amt(l.credit) }
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    TSide("Debit", debits, true, debitTotal, Modifier.weight(1f))
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                    TSide("Credit", credits, false, creditTotal, Modifier.weight(1f))
                }
                val net = debitTotal - creditTotal
                Text(
                    "Balance  ${kes(net.abs())}  ${if (net.signum() >= 0) "Debit" else "Credit"}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)).padding(14.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TSide(title: String, lines: List<LedgerLineOut>, isDebit: Boolean, total: BigDecimal, modifier: Modifier) {
    Column(modifier = modifier.padding(horizontal = 6.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        if (lines.isEmpty()) Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
        lines.forEach { l ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(shortDate(l.entryDate), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(kesNumber(amt(if (isDebit) l.debit else l.credit)), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(listOfNotNull(l.memo, l.reference).joinToString(" · ").ifBlank { "Entry" }, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(kesNumber(total), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
