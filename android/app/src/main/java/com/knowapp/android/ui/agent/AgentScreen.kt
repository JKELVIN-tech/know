package com.knowapp.android.ui.agent

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knowapp.android.data.model.PartnerBusiness
import com.knowapp.android.ui.components.kes
import com.knowapp.android.ui.theme.DangerRed
import com.knowapp.android.ui.theme.DisplayFontFamily
import com.knowapp.android.ui.theme.IncomeGreen
import com.knowapp.android.ui.theme.WarnAmber
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private fun statusText(s: String) = when (s) { "active" -> "Paying"; "trial" -> "Trial"; "overdue" -> "Overdue"; else -> "Not paid yet" }

private fun statusColor(s: String) = when (s) { "active" -> IncomeGreen; "overdue" -> DangerRed; "unpaid" -> WarnAmber; else -> Color(0xFF2563EB) }

@Composable
fun AgentScreen(viewModel: AgentViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var filter by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val data = state.data
        LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (data == null) {
                item { Text(if (state.loading) "Loading\u2026" else "Could not load your dashboard.", modifier = Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!state.loading) item { Button(onClick = { viewModel.load(null) }) { Text("Try again") } }
                return@LazyColumn
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B3D38)), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Hello, ${data.profile.name}", color = Color(0xCCF4FAF8), fontSize = 14.sp)
                        Text("Your partner code", color = Color(0xCCF4FAF8), fontSize = 12.sp)
                        Text(data.profile.code, color = Color.White, fontSize = 30.sp, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Text(if (data.profile.active) "You earn ${data.profile.commissionRate.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: data.profile.commissionRate}% of every monthly fee your businesses pay." else "This agent account is switched off. Contact KNOW.", color = Color(0xCCF4FAF8), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { clipboard.setText(AnnotatedString(data.profile.code)) }) { Text("Copy code") }
                            OutlinedButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Register your business on K.N.O.W. and use my partner code ${data.profile.code}.") }
                                context.startActivity(Intent.createChooser(send, "Share your code"))
                            }) { Text("Share", color = Color.White) }
                        }
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month") }
                    Text(YearMonth.parse(data.month).format(DateTimeFormatter.ofPattern("MMMM yyyy")), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { viewModel.shift(1) }, enabled = data.month < data.currentMonth) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month") }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Kpi("Businesses", data.totals.businesses.toString(), Modifier.weight(1f))
                        Kpi("Paying this month", data.totals.payingThisMonth.toString(), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Kpi("Earned this month", kes(data.totals.earnedMonth), Modifier.weight(1f))
                        Kpi("Balance due to you", kes(data.totals.balanceDue), Modifier.weight(1f), accent = true)
                    }
                    Text("Earned in total ${kes(data.totals.earnedTotal)} \u00B7 Paid out to you ${kes(data.totals.paidOut)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                val top = (data.series.maxOfOrNull { it.earned.toDoubleOrNull() ?: 0.0 } ?: 0.0).coerceAtLeast(1.0)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Earnings \u00B7 last 6 months", fontWeight = FontWeight.SemiBold)
                        Row(modifier = Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                            data.series.forEach { s ->
                                val v = s.earned.toDoubleOrNull() ?: 0.0
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                                    Box(modifier = Modifier.fillMaxWidth().height((8 + (v / top) * 84).dp).background(if (s.period == data.month) IncomeGreen else IncomeGreen.copy(alpha = 0.35f), RoundedCornerShape(6.dp)))
                                    Text(YearMonth.parse(s.period).format(DateTimeFormatter.ofPattern("MMM")), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            item { Text("My businesses (${data.businesses.size})", style = MaterialTheme.typography.titleMedium, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold) }
            if (data.businesses.isEmpty()) {
                item { Text("No businesses yet. Give your partner code to a business when it registers. Every business that registers with your code shows up here, and you earn a share of its monthly fee.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all", "active", "trial", "overdue", "unpaid").filter { k -> k == "all" || data.businesses.any { it.status == k } }.forEach { k ->
                            FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(if (k == "all") "All" else statusText(k)) })
                        }
                    }
                }
                item { OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                val rows = data.businesses.filter { (filter == "all" || it.status == filter) && (query.isBlank() || it.name.contains(query, true) || (it.region ?: "").contains(query, true)) }
                items(rows, key = { it.name + it.registeredAt }) { BusinessRow(it) }
            }
            item { Text("Commission history", style = MaterialTheme.typography.titleMedium, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold) }
            if (data.recentCommissions.isEmpty()) item { Text("Your commissions will appear here once a business you registered pays its monthly fee.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(data.recentCommissions) { c ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.business, fontWeight = FontWeight.SemiBold)
                        Text("Fee for ${YearMonth.parse(c.period).format(DateTimeFormatter.ofPattern("MMM yyyy"))} \u00B7 paid ${c.paidOn} \u00B7 ${kes(c.fee)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("+ ${kes(c.commission)}", color = IncomeGreen, fontWeight = FontWeight.Bold)
                }
            }
            item { Text("Payouts to you", style = MaterialTheme.typography.titleMedium, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold) }
            if (data.payouts.isEmpty()) item { Text("No payouts yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(data.payouts) { p ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${p.paidOn}${p.reference?.let { " \u00B7 $it" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
                    Text(kes(p.amount), fontWeight = FontWeight.Bold)
                }
            }
            item { Box(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier, accent: Boolean = false) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = if (accent) IncomeGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = DisplayFontFamily)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BusinessRow(b: PartnerBusiness) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(40.dp).height(40.dp).background(IncomeGreen.copy(alpha = 0.14f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(b.name.take(1).uppercase(), color = IncomeGreen, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(b.name, fontWeight = FontWeight.SemiBold)
                Text("${b.type}${b.region?.let { " \u00B7 $it" } ?: ""} \u00B7 registered ${b.registeredAt?.take(10) ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${b.monthlyFee?.let { kes(it) + " / month" } ?: "Fee not set"} \u00B7 you earned ${kes(b.earnedTotal)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(statusText(b.status), color = statusColor(b.status), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
    }
}
