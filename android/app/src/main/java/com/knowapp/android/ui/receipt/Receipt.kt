package com.knowapp.android.ui.receipt

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.print.PrintAttributes
import android.print.PrintManager
import com.knowapp.android.data.model.TransactionOut
import com.knowapp.android.data.repository.NewSale
import com.knowapp.android.ui.statements.FilePrintAdapter
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ReceiptLine(val name: String, val quantity: String?, val unitPrice: BigDecimal?, val amount: BigDecimal)

data class ReceiptData(
    val isIncome: Boolean,
    val number: String,
    val whenText: String,
    val business: String,
    val servedBy: String,
    val lines: List<ReceiptLine>,
    val total: BigDecimal,
    val method: String,
    val reference: String?,
    val payer: String?,
    val note: String?,
    val pending: Boolean = false,
) {
    val title: String get() = if (isIncome) "RECEIPT" else "PAYMENT VOUCHER"
}

private val money = DecimalFormat("#,##0.00")
fun receiptMoney(v: BigDecimal): String = "KES " + money.format(v)

private fun methodName(m: String) = when (m) { "mpesa" -> "M-Pesa"; "bank" -> "Bank"; else -> "Cash" }

private fun nairobi(iso: String?): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.of("Africa/Nairobi")).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
}.getOrDefault(iso?.take(16)?.replace('T', ' ') ?: "")

private fun clean(v: BigDecimal?) = v?.stripTrailingZeros()?.toPlainString()

object ReceiptBuilder {
    // A saved receipt: every entry that shares one receipt id, or a single entry on its own
    fun fromTransactions(entries: List<TransactionOut>, business: String, servedBy: String): ReceiptData {
        val first = entries.minByOrNull { it.id } ?: entries.first()
        val lines = entries.sortedBy { it.id }.map { t ->
            val amount = t.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val qty = t.quantity?.toBigDecimalOrNull()
            ReceiptLine(t.category, clean(qty), if (qty != null && qty.signum() > 0) amount.divide(qty, 2, RoundingMode.HALF_UP) else null, amount)
        }
        return ReceiptData(
            isIncome = first.type == "income",
            number = first.batchId?.take(6)?.uppercase() ?: "T${first.id}",
            whenText = nairobi(first.transactionDate),
            business = business,
            servedBy = servedBy,
            lines = lines,
            total = lines.fold(BigDecimal.ZERO) { a, l -> a + l.amount },
            method = methodName(first.method),
            reference = entries.firstNotNullOfOrNull { it.mpesaCode?.takeIf { c -> c.isNotBlank() } },
            payer = entries.firstNotNullOfOrNull { it.mpesaPayerName?.takeIf { c -> c.isNotBlank() } },
            note = entries.firstNotNullOfOrNull { it.description?.takeIf { c -> c.isNotBlank() } },
        )
    }

    // A sale saved on the phone while offline: shown as pending until it syncs
    fun fromSale(sale: NewSale, business: String, servedBy: String): ReceiptData {
        val lines = sale.lines.map { l ->
            val amount = l.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val qty = l.quantity?.toBigDecimalOrNull()
            ReceiptLine(l.category ?: "Item", clean(qty), if (qty != null && qty.signum() > 0) amount.divide(qty, 2, RoundingMode.HALF_UP) else null, amount)
        }
        return ReceiptData(
            isIncome = sale.type == "income",
            number = sale.batchId.take(6).uppercase(),
            whenText = ZonedDateTime.now(ZoneId.of("Africa/Nairobi")).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")),
            business = business,
            servedBy = servedBy,
            lines = lines,
            total = lines.fold(BigDecimal.ZERO) { a, l -> a + l.amount },
            method = methodName(sale.method),
            reference = sale.mpesaCode?.takeIf { it.isNotBlank() },
            payer = sale.mpesaPayerName?.takeIf { it.isNotBlank() },
            note = sale.description?.takeIf { it.isNotBlank() },
            pending = true,
        )
    }
}

object ReceiptPdf {
    private const val W = 227f
    private const val PAD = 12f

    private fun paint(size: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, color: Int = 0xFF10201D.toInt(), mono: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            textAlign = align
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun wrap(p: Paint, text: String, width: Float): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (word in text.split(" ")) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(next) <= width) line = next
            else {
                if (line.isNotEmpty()) out += line
                line = word
                while (p.measureText(line) > width && line.length > 1) {
                    var cut = line.length - 1
                    while (cut > 1 && p.measureText(line.substring(0, cut)) > width) cut--
                    out += line.substring(0, cut)
                    line = line.substring(cut)
                }
            }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    // Draws the receipt when a canvas is given; always returns the height used, so the page can be sized to fit
    private fun layout(canvas: Canvas?, d: ReceiptData): Float {
        var y = 20f
        val inner = W - 2 * PAD
        val dash = paint(8f, color = 0xFF5D6F6B.toInt())
        fun center(text: String, p: Paint, gap: Float) {
            wrap(p, text, inner).forEach { line -> canvas?.drawText(line, W / 2, y, p); y += p.textSize + 3 }
            y += gap
        }
        fun rule() {
            canvas?.drawText("- ".repeat(((inner / dash.measureText("- ")).toInt())).trim(), PAD, y, dash)
            y += 12f
        }
        fun pair(left: String, right: String, p: Paint, pr: Paint = p) {
            canvas?.drawText(left, PAD, y, p)
            canvas?.drawText(right, W - PAD, y, Paint(pr).apply { textAlign = Paint.Align.RIGHT })
            y += p.textSize + 4
        }

        center(d.business, paint(12.5f, true, Paint.Align.CENTER), 2f)
        center(d.title, paint(9f, true, Paint.Align.CENTER, 0xFF0F766E.toInt()), 6f)
        val small = paint(7.5f, color = 0xFF3F514D.toInt())
        pair("No. ${d.number}", d.whenText, small)
        pair("Served by", d.servedBy, small)
        if (d.pending) { canvas?.drawText("Saved on this phone, not synced yet", PAD, y, paint(7.5f, true, color = 0xFFB45309.toInt())); y += 12f }
        y += 2f
        rule()

        val head = paint(8f, true)
        pair("Item", "Amount", head, paint(8f, true))
        y += 1f
        val body = paint(8.5f)
        val bodyBold = paint(8.5f, true)
        for (line in d.lines) {
            val names = wrap(bodyBold, line.name, inner - 62f)
            names.forEachIndexed { i, n ->
                canvas?.drawText(n, PAD, y, bodyBold)
                if (i == 0) canvas?.drawText(money.format(line.amount), W - PAD, y, paint(8.5f, true, Paint.Align.RIGHT))
                y += 11f
            }
            if (line.quantity != null) {
                val unit = line.unitPrice?.let { " x ${money.format(it)}" } ?: ""
                canvas?.drawText("${line.quantity}$unit", PAD + 6f, y, paint(7.5f, color = 0xFF5D6F6B.toInt()))
                y += 10f
            }
            y += 3f
        }
        rule()
        pair("TOTAL", receiptMoney(d.total), paint(11f, true), paint(11f, true))
        y += 2f
        rule()
        val pay = paint(8f)
        pair(if (d.isIncome) "Paid by" else "Paid through", d.method, pay)
        d.reference?.let { pair("Reference", it, pay, paint(8f, mono = true)) }
        d.payer?.let { pair(if (d.isIncome) "From" else "To", it, pay) }
        d.note?.let { wrap(pay, "Note: $it", inner).forEach { l -> canvas?.drawText(l, PAD, y, pay); y += 11f } }
        y += 8f
        center(if (d.isIncome) "Thank you!" else "Payment recorded", paint(9f, true, Paint.Align.CENTER), 2f)
        val footer = paint(7f, align = Paint.Align.CENTER, color = 0xFF5D6F6B.toInt())
        canvas?.drawText("Made by KNOW", W / 2, y, footer)
        y += 18f
        return y
    }

    fun pageHeight(d: ReceiptData): Float = layout(null, d)

    fun build(file: File, d: ReceiptData) {
        val height = layout(null, d).toInt()
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), height, 1).create())
        layout(page.canvas, d)
        doc.finishPage(page)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
    }

    // Opens the phone's print dialog, which can print on any printer or save as a PDF to share
    fun print(context: Context, d: ReceiptData) {
        var c: Context? = context
        var activity: Activity? = null
        while (c is ContextWrapper) { if (c is Activity) { activity = c; break }; c = c.baseContext }
        val host = activity ?: return
        val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
        val file = File(dir, "receipt_${d.number}.pdf")
        build(file, d)
        val heightMils = (pageHeight(d) / 72f * 1000).toInt()
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize("know_receipt", "Receipt 80mm", 3150, heightMils))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        val manager = host.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print("Receipt ${d.number}", FilePrintAdapter(file, "Receipt ${d.number}"), attributes)
    }
}
