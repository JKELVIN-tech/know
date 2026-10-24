package com.knowapp.android.ui.billing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knowapp.android.data.model.BillingStatusOut
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HeroTop = Color(0xFF0F766E)
private val HeroBottom = Color(0xFF134E4A)
private val Mint = Color(0xFF5EEAD4)

private fun niceDate(iso: String?, plusDays: Long = 0): String = runCatching {
    OffsetDateTime.parse(iso).plusDays(plusDays).atZoneSameInstant(ZoneId.of("Africa/Nairobi")).format(DateTimeFormatter.ofPattern("d MMMM yyyy"))
}.getOrDefault("")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(viewModel: SubscriptionViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscription", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val status = state.status
            when {
                state.loading -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error != null -> {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::load) { Text("Try again") }
                }
                status == null || !status.applies -> Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    Column(modifier = Modifier.padding(22.dp)) {
                        Text("Subscription", style = MaterialTheme.typography.titleMedium)
                        Text("Your account is managed by your institution. There is nothing for you to pay here.", modifier = Modifier.padding(top = 6.dp))
                    }
                }
                else -> {
                    TrialHero(status)
                    Timeline(status)
                    PlanCard(status)
                    if (status.plan == "owner") AccountsCard(status)
                    PayCard(status)
                }
            }
            Box(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TrialHero(status: BillingStatusOut) {
    val trial = status.state == "trial"
    val progress = if (trial) status.daysLeft.coerceIn(0, status.trialDays).toFloat() / status.trialDays else 0f
    Box(
        modifier = Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(HeroTop, HeroBottom)), RoundedCornerShape(28.dp)).padding(24.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (trial) "FREE TRIAL" else "TRIAL ENDED", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Box(modifier = Modifier.padding(vertical = 18.dp).size(168.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 14.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(color = Color.White.copy(alpha = 0.16f), startAngle = -90f, sweepAngle = 360f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (progress > 0f) drawArc(color = Mint, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (trial) "${status.daysLeft}" else "0", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (status.daysLeft == 1) "day left" else "days left", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
            }
            Text(
                if (trial) "Enjoy everything free for ${status.trialDays} days" else "Payments are not switched on yet, so you can keep using KNOW for now.",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Box(modifier = Modifier.padding(top = 14.dp).background(Color.White.copy(alpha = 0.14f), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    if (trial) "Billing begins on ${niceDate(status.trialEndsAt)}" else "Trial ended on ${niceDate(status.trialEndsAt)}",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun Timeline(status: BillingStatusOut) {
    val price = "KES ${status.total} / month"
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(modifier = Modifier.padding(22.dp)) {
            Text("How billing works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(14)
            TimelineRow("Trial starts", niceDate(status.trialEndsAt, -status.trialDays.toLong()), done = true, last = false)
            TimelineRow("Trial ends", niceDate(status.trialEndsAt), done = status.state != "trial", last = false)
            TimelineRow("Payments begin", "$price from ${niceDate(status.trialEndsAt)}", done = false, last = true)
        }
    }
}

@Composable
private fun Spacer(h: Int) = Box(modifier = Modifier.height(h.dp))

@Composable
private fun TimelineRow(title: String, sub: String, done: Boolean, last: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
            Box(
                modifier = Modifier.size(22.dp).then(if (done) Modifier.background(accent, CircleShape) else Modifier.border(2.dp, accent, CircleShape)),
                contentAlignment = Alignment.Center,
            ) { if (done) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
            if (!last) Box(modifier = Modifier.width(2.dp).height(34.dp).background(accent.copy(alpha = 0.35f)))
        }
        Column(modifier = Modifier.padding(start = 12.dp, bottom = if (last) 0.dp else 10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlanCard(status: BillingStatusOut) {
    val owner = status.plan == "owner"
    val features = if (owner) listOf(
        "Sales, stock, audits and finance records",
        "Create individual accounts for your team",
        "${status.includedAccounts} individual accounts included",
        "KES ${status.extraRate} for each extra account",
    ) else listOf(
        "Sales, stock and finance records",
        "Works offline, syncs when you are back online",
        "Daily summaries and audit trail",
        "Cannot create other accounts",
    )
    Card(
        modifier = Modifier.fillMaxWidth().border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape).padding(horizontal = 12.dp, vertical = 5.dp)) {
                Text("${status.planLabel.uppercase()} PLAN", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("KES ${status.baseAmount}", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                Text(" / month", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            features.forEach { f ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(modifier = Modifier.padding(top = 2.dp).size(20.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                    }
                    Text(f, modifier = Modifier.padding(start = 12.dp), fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun AccountsCard(status: BillingStatusOut) {
    val used = status.accountsUsed
    val fill = (used.toFloat() / status.includedAccounts.coerceAtLeast(1)).coerceIn(0f, 1f)
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your accounts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Individual accounts")
                Text("$used of ${status.includedAccounts} included", fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(progress = { fill }, modifier = Modifier.fillMaxWidth().height(10.dp), strokeCap = StrokeCap.Round)
            if (status.extraAccounts > 0) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${status.extraAccounts} extra x KES ${status.extraRate}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("KES ${status.extraAmount}", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("${status.includedAccounts - used} more included before extra charges apply", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total per month", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("KES ${status.total}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PayCard(status: BillingStatusOut) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pay with", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            MethodTile("M-Pesa", "Pay straight from your phone")
            MethodTile("Card", "Visa or Mastercard")
            Text(status.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun MethodTile(name: String, sub: String) {
    Row(
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text("Soon", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
