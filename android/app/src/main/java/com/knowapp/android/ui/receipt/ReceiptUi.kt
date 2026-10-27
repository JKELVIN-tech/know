package com.knowapp.android.ui.receipt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val Paper = Color(0xFFFFFFFF)
private val PaperInk = Color(0xFF10201D)
private val PaperMuted = Color(0xFF5D6F6B)
private val PaperTeal = Color(0xFF0F766E)

// The receipt as it looks on paper: always white with dark text, in light or dark mode
@Composable
fun ReceiptPaper(d: ReceiptData, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().background(Paper, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(d.business, color = PaperInk, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, textAlign = TextAlign.Center)
        Text(d.title, color = PaperTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 4.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("No. ${d.number}", color = PaperMuted, fontSize = 12.sp)
            Text(d.whenText, color = PaperMuted, fontSize = 12.sp)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Served by", color = PaperMuted, fontSize = 12.sp)
            Text(d.servedBy, color = PaperMuted, fontSize = 12.sp)
        }
        if (d.pending) Text("Saved on this phone, not synced yet", color = Color(0xFFB45309), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFFD9E2E0))
        d.lines.forEach { l ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(l.name, color = PaperInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (l.quantity != null) Text("${l.quantity}${l.unitPrice?.let { " x " + receiptMoney(it) } ?: ""}", color = PaperMuted, fontSize = 12.sp)
                }
                Text(receiptMoney(l.amount), color = PaperInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = PaperInk)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("TOTAL", color = PaperInk, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Text(receiptMoney(d.total), color = PaperInk, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFFD9E2E0))
        PayRow(if (d.isIncome) "Paid by" else "Paid through", d.method)
        d.reference?.let { PayRow("Reference", it, mono = true) }
        d.payer?.let { PayRow(if (d.isIncome) "From" else "To", it) }
        d.note?.let { PayRow("Note", it) }
        Text(if (d.isIncome) "Thank you!" else "Payment recorded", color = PaperInk, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
        Text("Made by KNOW", color = PaperMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PayRow(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = PaperMuted, fontSize = 12.sp)
        Text(value, color = PaperInk, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSheet(d: ReceiptData, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ReceiptPaper(d)
            Button(onClick = { ReceiptPdf.print(context, d) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(50)) {
                Text("Print receipt", fontWeight = FontWeight.Bold)
            }
            Text("Choose a printer, or Save as PDF to share it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

// Shown after saving: the entry is stored, and the receipt can be printed straight away
@Composable
fun SavedDialog(d: ReceiptData, onView: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDone) {
        Column(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(56.dp))
            Text(if (d.pending) "Saved on this phone" else "Saved", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(top = 10.dp))
            Text(
                "${d.lines.size} ${if (d.lines.size == 1) "item" else "items"} · ${receiptMoney(d.total)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = { ReceiptPdf.print(context, d) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(50)) {
                Text("Print receipt", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onView, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp), shape = RoundedCornerShape(50)) { Text("View receipt") }
            TextButton(onClick = onDone, modifier = Modifier.padding(top = 4.dp)) { Text("Done") }
        }
    }
}
