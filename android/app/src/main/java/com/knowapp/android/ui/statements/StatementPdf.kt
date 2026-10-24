package com.knowapp.android.ui.statements

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigDecimal
import java.text.DecimalFormat
import java.time.format.DateTimeFormatter

data class StatementPdfData(
    val institution: String?,
    val title: String,
    val account: String,
    val period: String,
    val staff: String?,
    val preparedBy: String,
    val generated: String,
    val lines: List<StatementLine>,
    val opening: BigDecimal?,
    val closing: BigDecimal?,
    val income: BigDecimal,
    val expense: BigDecimal,
)

private const val PAGE_W = 595
private const val PAGE_H = 842
private const val LEFT = 36f
private const val RIGHT = 559f
private const val ROW_H = 22f
private const val FIRST_CAP = 20
private const val NEXT_CAP = 30

object StatementPdf {
    private val number = DecimalFormat("#,##0.00")
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yy")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    private fun fit(p: Paint, text: String, maxWidth: Float): String {
        if (p.measureText(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    fun build(file: File, d: StatementPdfData) {
        val ink = 0xFF10201D.toInt()
        val muted = 0xFF5D6F6B.toInt()
        val teal = 0xFF0F766E.toInt()
        val deep = 0xFF134E4A.toInt()
        val rule = 0xFFD9E2E0.toInt()
        val zebra = 0xFFF3F7F6.toInt()
        val green = 0xFF047857.toInt()
        val red = 0xFFB91C1C.toInt()
        val white = 0xFFFFFFFF.toInt()

        fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, mono: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            textAlign = align
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val body = paint(8.5f, ink)
        val small = paint(7f, muted)
        val label = paint(6.8f, muted, bold = true)
        val fill = Paint().apply { style = Paint.Style.FILL }
        val stroke = Paint().apply { color = rule; strokeWidth = 0.8f }

        // A business shows its name; a personal workspace (named "Name (@user)") shows the account holder instead
        val business = d.institution?.takeIf { it.isNotBlank() && !it.contains("(@") } ?: d.preparedBy.ifBlank { "Account statement" }
        val showBal = d.lines.firstOrNull()?.balance != null || d.opening != null

        // Column positions: Date, Time, Details, Reference, Credit, Debit, Balance
        val dateX = LEFT + 6f
        val timeX = LEFT + 64f
        val detailX = LEFT + 100f
        val refX = LEFT + 232f
        val creditRight = if (showBal) 404f else 482f
        val debitRight = if (showBal) 474f else 557f
        val balRight = 557f

        // How many rows each page holds; the last page keeps room for the totals
        val sizes = mutableListOf<Int>()
        var remaining = d.lines.size
        var cap = FIRST_CAP
        while (remaining > 0) {
            val take = minOf(cap, remaining)
            sizes += take
            remaining -= take
            cap = NEXT_CAP
        }
        val lastCap = if (sizes.size <= 1) FIRST_CAP else NEXT_CAP
        if (sizes.isEmpty() || sizes.last() > lastCap - 4) sizes += 0

        val doc = PdfDocument()
        var index = 0

        sizes.forEachIndexed { pageIndex, count ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex + 1).create())
            val canvas: Canvas = page.canvas
            var y: Float

            fun tableHeader(top: Float): Float {
                fill.color = deep
                canvas.drawRoundRect(LEFT, top, RIGHT, top + 24f, 5f, 5f, fill)
                val base = top + 15.5f
                val h = paint(8f, white, bold = true)
                val hr = paint(8f, white, bold = true, align = Paint.Align.RIGHT)
                canvas.drawText("Date", dateX, base, h)
                canvas.drawText("Time", timeX, base, h)
                canvas.drawText("Details", detailX, base, h)
                canvas.drawText("Reference", refX, base, h)
                canvas.drawText("Credit", creditRight, base, hr)
                canvas.drawText("Debit", debitRight, base, hr)
                if (showBal) canvas.drawText("Balance", balRight, base, hr)
                return top + 24f
            }

            if (pageIndex == 0) {
                // Header band: the business name leads
                fill.color = teal
                canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 96f, fill)
                fill.color = 0x14FFFFFF
                canvas.drawCircle(540f, 8f, 78f, fill)
                fill.color = 0x10FFFFFF
                canvas.drawCircle(470f, 100f, 46f, fill)
                canvas.drawText("ACCOUNT STATEMENT", LEFT, 34f, paint(8f, 0xFF99F6E4.toInt(), bold = true))
                canvas.drawText(fit(paint(24f, white, bold = true), business, 400f), LEFT, 62f, paint(24f, white, bold = true))
                canvas.drawText(d.title, LEFT, 80f, paint(10.5f, 0xDDFFFFFF.toInt()))
                canvas.drawText("Generated", RIGHT, 34f, paint(7.5f, 0xFF99F6E4.toInt(), bold = true, align = Paint.Align.RIGHT))
                canvas.drawText(d.generated, RIGHT, 48f, paint(9f, white, align = Paint.Align.RIGHT))

                // Details
                fun meta(x: Float, top: Float, name: String, value: String, maxW: Float) {
                    canvas.drawText(name.uppercase(), x, top, label)
                    canvas.drawText(fit(paint(10f, ink, bold = true), value, maxW), x, top + 13f, paint(10f, ink, bold = true))
                }
                meta(LEFT, 120f, "Account", d.account, 160f)
                meta(LEFT + 176f, 120f, "Period", d.period, 190f)
                meta(LEFT + 380f, 120f, "Prepared by", d.preparedBy.ifBlank { "-" }, 140f)
                if (d.staff != null) meta(LEFT, 150f, "Staff member", d.staff, 160f)
                meta(if (d.staff != null) LEFT + 176f else LEFT, 150f, "Entries", d.lines.size.toString(), 160f)

                // Credit and debit lead the summary
                val boxes = buildList {
                    if (showBal && d.opening != null) add(Triple("Opening balance", d.opening, ink))
                    add(Triple("Total credit", d.income, green))
                    add(Triple("Total debit", d.expense, red))
                    if (showBal && d.closing != null) add(Triple("Closing balance", d.closing, ink)) else add(Triple("Net", d.income - d.expense, ink))
                }
                val gap = 8f
                val boxW = (RIGHT - LEFT - gap * (boxes.size - 1)) / boxes.size
                boxes.forEachIndexed { i, (name, value, accent) ->
                    val x = LEFT + i * (boxW + gap)
                    fill.color = zebra
                    canvas.drawRoundRect(x, 180f, x + boxW, 228f, 8f, 8f, fill)
                    fill.color = accent
                    canvas.drawRoundRect(x, 188f, x + 3f, 220f, 1.5f, 1.5f, fill)
                    canvas.drawText(name.uppercase(), x + 12f, 198f, label)
                    canvas.drawText("KES ${number.format(value)}", x + 12f, 216f, paint(11f, accent, bold = true))
                }
                y = tableHeader(246f)
            } else {
                canvas.drawText(fit(paint(9.5f, teal, bold = true), business, 300f), LEFT, 40f, paint(9.5f, teal, bold = true))
                canvas.drawText("${d.title}  ·  ${d.period}", RIGHT, 40f, paint(8.5f, muted, align = Paint.Align.RIGHT))
                canvas.drawLine(LEFT, 50f, RIGHT, 50f, stroke)
                y = tableHeader(62f)
            }

            val detailPaint = paint(8.5f, ink)
            val refPaint = paint(8f, ink, mono = true)
            val creditPaint = paint(8.5f, green, bold = true, align = Paint.Align.RIGHT)
            val debitPaint = paint(8.5f, red, bold = true, align = Paint.Align.RIGHT)
            val balPaint = paint(8.5f, ink, align = Paint.Align.RIGHT)
            for (i in 0 until count) {
                val line = d.lines[index++]
                val r = line.row
                if (i % 2 == 1) {
                    fill.color = zebra
                    canvas.drawRect(LEFT, y, RIGHT, y + ROW_H, fill)
                }
                val base = y + 14f
                canvas.drawText(r.date.format(dateFmt), dateX, base, body)
                canvas.drawText(r.time?.format(timeFmt) ?: "-", timeX, base, body)
                val description = listOfNotNull(r.category, r.party ?: r.note).joinToString(" · ").ifBlank { "-" }
                canvas.drawText(fit(detailPaint, description, 124f), detailX, y + 10f, detailPaint)
                canvas.drawText(r.methodLabel, detailX, y + 19f, small)
                canvas.drawText(fit(refPaint, r.reference ?: "-", 78f), refX, base, refPaint)
                val amountText = number.format(r.amount)
                if (r.type == "income") canvas.drawText(amountText, creditRight, base, creditPaint) else canvas.drawText(amountText, debitRight, base, debitPaint)
                line.balance?.let { canvas.drawText(number.format(it), balRight, base, balPaint) }
                y += ROW_H
            }

            if (pageIndex == sizes.lastIndex) {
                if (d.lines.isEmpty()) {
                    canvas.drawText("No entries for this selection.", LEFT + 6f, y + 16f, small)
                    y += ROW_H
                }
                y += 6f
                canvas.drawLine(LEFT, y, RIGHT, y, paint(1f, ink).apply { strokeWidth = 1.1f })
                y += 16f
                val totalPaint = paint(9.5f, ink, bold = true)
                canvas.drawText("Totals", dateX, y, totalPaint)
                canvas.drawText(number.format(d.income), creditRight, y, paint(9.5f, green, bold = true, align = Paint.Align.RIGHT))
                canvas.drawText(number.format(d.expense), debitRight, y, paint(9.5f, red, bold = true, align = Paint.Align.RIGHT))
                if (showBal && d.closing != null) {
                    y += 18f
                    fill.color = zebra
                    canvas.drawRoundRect(LEFT, y - 13f, RIGHT, y + 7f, 6f, 6f, fill)
                    canvas.drawText("Closing balance", dateX, y, paint(9.5f, teal, bold = true))
                    canvas.drawText("KES ${number.format(d.closing)}", balRight, y, paint(10f, teal, bold = true, align = Paint.Align.RIGHT))
                }
                y += 24f
                canvas.drawText("End of statement. All amounts are in Kenya shillings (KES). Credit is money in, debit is money out.", LEFT + 6f, y, small)
            }

            // Footer: made by KNOW, with the page number
            canvas.drawLine(LEFT, 808f, RIGHT, 808f, stroke)
            val madeBy = paint(8f, muted)
            canvas.drawText("Made by ", LEFT, 824f, madeBy)
            canvas.drawText("KNOW", LEFT + madeBy.measureText("Made by "), 824f, paint(8.5f, teal, bold = true))
            canvas.drawText("Page ${pageIndex + 1} of ${sizes.size}", RIGHT, 824f, paint(8f, muted, align = Paint.Align.RIGHT))
            doc.finishPage(page)
        }

        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
    }
}

// Hands an already-written PDF to Android's print dialog, which also offers Save as PDF
class FilePrintAdapter(private val file: File, private val jobName: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { out -> input.copyTo(out) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}
