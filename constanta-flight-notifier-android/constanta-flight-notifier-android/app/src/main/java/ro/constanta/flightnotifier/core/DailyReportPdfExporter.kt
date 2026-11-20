package ro.constanta.flightnotifier.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import ro.constanta.flightnotifier.model.DailyReportData
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DailyReportPdfExporter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 32f
    private const val MARGIN_TOP = 38f
    private const val MARGIN_BOTTOM = 44f
    private const val ROW_H = 22f

    fun exportToStream(outputStream: OutputStream, report: DailyReportData) {
        val doc = PdfDocument()
        val genFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ro", "RO"))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F1B24")
            textSize = 15f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0B5ED7")
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            textSize = 9.5f
            typeface = Typeface.SANS_SERIF
        }
        val metaBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 10f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val headerBg = Paint().apply { color = Color.parseColor("#0F1B24") }
        val totalBg = Paint().apply { color = Color.parseColor("#E2E8F0") }
        val altBg = Paint().apply { color = Color.parseColor("#F8FAFC") }
        val boxBg = Paint().apply { color = Color.parseColor("#F8FAFC") }
        val borderPaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
        }
        val linePaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 0.6f
        }
        val thPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 8.2f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val tdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 8.4f
            typeface = Typeface.SANS_SERIF
        }
        val tdBoldPaint = Paint(tdPaint).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 8f
            typeface = Typeface.SANS_SERIF
        }

        // Coloane tabel curse (531 pt lățime utilă)
        val cAirline = MARGIN_X + 4f
        val cType = MARGIN_X + 132f
        val cReg = MARGIN_X + 184f
        val cLand = MARGIN_X + 248f
        val cTakeoff = MARGIN_X + 306f
        val cPax = MARGIN_X + 368f
        val cBags = MARGIN_X + 424f
        val cPmr = MARGIN_X + 488f

        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
        var canvas = page.canvas
        var y = MARGIN_TOP

        fun drawFooter(c: Canvas, p: Int) {
            c.drawText(
                "SAT - Monitorizare trafic CND • Drept de proprietate intelectuală P. G. Petria • Pagina $p",
                MARGIN_X,
                PAGE_HEIGHT - 18f,
                footerPaint
            )
        }

        fun ensureSpace(neededHeight: Float) {
            if (y + neededHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                drawFooter(canvas, pageNum)
                doc.finishPage(page)
                pageNum += 1
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
                canvas = page.canvas
                y = MARGIN_TOP
            }
        }

        fun drawFlightsHeader(): Float {
            canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + 20f, headerBg)
            val ty = y + 13.5f
            canvas.drawText("Companie / Cursă", cAirline, ty, thPaint)
            canvas.drawText("Tip", cType, ty, thPaint)
            canvas.drawText("Înmatr.", cReg, ty, thPaint)
            canvas.drawText("Aterizare", cLand, ty, thPaint)
            canvas.drawText("Decolare", cTakeoff, ty, thPaint)
            canvas.drawText("Pasageri", cPax, ty, thPaint)
            canvas.drawText("Bag. cală", cBags, ty, thPaint)
            canvas.drawText("PMR", cPmr, ty, thPaint)
            return y + 20f
        }

        // === 1. ANTET RAPORT ZILNIC ===
        canvas.drawText("RAPORT ZILNIC — SAT - Monitorizare trafic CND", MARGIN_X, y, titlePaint)
        y += 15f
        canvas.drawText(
            "Aeroportul Internațional „Mihail Kogălniceanu” Constanța (CND / LRCK) • Generat la ${genFmt.format(Date())}",
            MARGIN_X,
            y,
            metaPaint
        )
        y += 16f

        // Casetă Data + Supervizor
        canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + 28f, boxBg)
        canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + 28f, borderPaint)
        canvas.drawText("Data raportului: ${report.dateLabel}", MARGIN_X + 10f, y + 18f, metaBoldPaint)
        canvas.drawText("Supervizor: ${report.supervisor}", MARGIN_X + 270f, y + 18f, metaBoldPaint)
        y += 42f

        // === 2. CURSE TURKISH AIRLINES & WIZZ AIR ===
        canvas.drawText("1. Curse Turkish Airlines și Wizz Air", MARGIN_X, y, sectionPaint)
        y += 10f
        y = drawFlightsHeader()

        if (report.flights.isEmpty()) {
            canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + ROW_H, altBg)
            canvas.drawText("Nu au fost înregistrate curse Turkish Airlines / Wizz Air în această zi.", cAirline, y + 14.5f, metaPaint)
            canvas.drawLine(MARGIN_X, y + ROW_H, PAGE_WIDTH - MARGIN_X, y + ROW_H, linePaint)
            y += ROW_H
        } else {
            report.flights.forEachIndexed { idx, f ->
                if (y + ROW_H > PAGE_HEIGHT - MARGIN_BOTTOM) {
                    drawFooter(canvas, pageNum)
                    doc.finishPage(page)
                    pageNum += 1
                    page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
                    canvas = page.canvas
                    y = MARGIN_TOP
                    y = drawFlightsHeader()
                }
                if (idx % 2 == 1) {
                    canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + ROW_H, altBg)
                }
                val ty = y + 14.5f
                val compText = truncate("${f.airline} (${f.callsign})", 26)
                canvas.drawText(compText, cAirline, ty, tdPaint)
                canvas.drawText(truncate(f.aircraftType, 9), cType, ty, tdPaint)
                canvas.drawText(truncate(f.registration, 10), cReg, ty, tdPaint)
                canvas.drawText(f.landingTime, cLand, ty, tdBoldPaint)
                canvas.drawText(f.takeoffTime, cTakeoff, ty, tdBoldPaint)
                canvas.drawText(f.passengers.toString(), cPax, ty, tdPaint)
                canvas.drawText(f.holdBaggage.toString(), cBags, ty, tdPaint)
                canvas.drawText(f.pmr.toString(), cPmr, ty, tdPaint)
                canvas.drawLine(MARGIN_X, y + ROW_H, PAGE_WIDTH - MARGIN_X, y + ROW_H, linePaint)
                y += ROW_H
            }

            // Rând TOTAL
            ensureSpace(ROW_H + 8f)
            val totPax = report.flights.sumOf { it.passengers }
            val totBags = report.flights.sumOf { it.holdBaggage }
            val totPmr = report.flights.sumOf { it.pmr }
            canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + ROW_H, totalBg)
            val ty = y + 14.5f
            canvas.drawText("TOTAL (${report.flights.size} curse)", cAirline, ty, tdBoldPaint)
            canvas.drawText(totPax.toString(), cPax, ty, tdBoldPaint)
            canvas.drawText(totBags.toString(), cBags, ty, tdBoldPaint)
            canvas.drawText(totPmr.toString(), cPmr, ty, tdBoldPaint)
            canvas.drawLine(MARGIN_X, y + ROW_H, PAGE_WIDTH - MARGIN_X, y + ROW_H, linePaint)
            y += ROW_H
        }

        y += 22f

        // === 3. ACTIVITĂȚI / EVENIMENTE SPECIALE ===
        ensureSpace(90f)
        canvas.drawText("2. Activități și mențiuni operaționale", MARGIN_X, y, sectionPaint)
        y += 10f
        canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + 66f, boxBg)
        canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + 66f, borderPaint)

        fun flagLabel(checked: Boolean): String = if (checked) "[X] DA" else "[ ] NU"
        canvas.drawText("${flagLabel(report.shootingSession)}   Ședință de tragere", MARGIN_X + 12f, y + 20f, tdBoldPaint)
        canvas.drawText("${flagLabel(report.securityCommittee)}   Comitet de securitate", MARGIN_X + 12f, y + 38f, tdBoldPaint)
        canvas.drawText("${flagLabel(report.pnsaExemptions)}   Exceptări PNSA", MARGIN_X + 12f, y + 56f, tdBoldPaint)
        y += 88f

        // === 4. ARTICOLE INTERZISE DESCOPERITE LA CONTROL ===
        ensureSpace(190f)
        canvas.drawText("3. Articole interzise descoperite la controlul de securitate", MARGIN_X, y, sectionPaint)
        y += 10f

        canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + 20f, headerBg)
        canvas.drawText("Categorie articol interzis", MARGIN_X + 10f, y + 13.5f, thPaint)
        canvas.drawText("Număr", PAGE_WIDTH - MARGIN_X - 55f, y + 13.5f, thPaint)
        y += 20f

        val p = report.prohibitedItems
        val categories = listOf(
            "Dispozitive care lansează proiectile" to p.projectileDevices,
            "Dispozitive cu efect paralizant" to p.stunningDevices,
            "Ascuțite" to p.sharpObjects,
            "Unelte" to p.tools,
            "Contondente" to p.bluntInstruments,
            "Explozive / Incendiare" to p.explosivesIncendiary
        )

        categories.forEachIndexed { idx, (label, count) ->
            if (idx % 2 == 1) {
                canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + ROW_H, altBg)
            }
            canvas.drawText(label, MARGIN_X + 10f, y + 14.5f, tdPaint)
            canvas.drawText(count.toString(), PAGE_WIDTH - MARGIN_X - 45f, y + 14.5f, tdBoldPaint)
            canvas.drawLine(MARGIN_X, y + ROW_H, PAGE_WIDTH - MARGIN_X, y + ROW_H, linePaint)
            y += ROW_H
        }

        canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + ROW_H, totalBg)
        canvas.drawText("TOTAL ARTICOLE INTERZISE", MARGIN_X + 10f, y + 14.5f, tdBoldPaint)
        canvas.drawText(p.total.toString(), PAGE_WIDTH - MARGIN_X - 45f, y + 14.5f, tdBoldPaint)
        canvas.drawLine(MARGIN_X, y + ROW_H, PAGE_WIDTH - MARGIN_X, y + ROW_H, linePaint)
        y += ROW_H + 22f

        // === 5. ALTE DATE DE INTERES ===
        ensureSpace(70f)
        canvas.drawText("4. Alte date de interes", MARGIN_X, y, sectionPaint)
        y += 10f

        val notesText = report.otherNotes.trim().ifBlank { "Fără alte mențiuni." }
        val wrappedLines = wrapText(notesText, tdPaint, PAGE_WIDTH - MARGIN_X * 2 - 20f)
        val lineH = 14f
        var lineIdx = 0
        while (lineIdx < wrappedLines.size) {
            val remainingSpace = PAGE_HEIGHT - MARGIN_BOTTOM - y - 16f
            val maxLinesOnPage = (remainingSpace / lineH).toInt().coerceAtLeast(1)
            val endIdx = minOf(wrappedLines.size, lineIdx + maxLinesOnPage)
            val chunk = wrappedLines.subList(lineIdx, endIdx)
            val boxH = chunk.size * lineH + 14f

            canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + boxH, boxBg)
            canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + boxH, borderPaint)
            var ty = y + 15f
            for (line in chunk) {
                canvas.drawText(line, MARGIN_X + 10f, ty, tdPaint)
                ty += lineH
            }
            y += boxH + 8f
            lineIdx = endIdx
            if (lineIdx < wrappedLines.size) {
                ensureSpace(60f)
            }
        }

        drawFooter(canvas, pageNum)
        doc.finishPage(page)

        try {
            doc.writeTo(outputStream)
            outputStream.flush()
        } finally {
            doc.close()
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) {
                result.add("")
                continue
            }
            var current = ""
            for (word in trimmed.split(Regex("\\s+"))) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = candidate
                } else {
                    if (current.isNotEmpty()) result.add(current)
                    current = word
                }
            }
            if (current.isNotEmpty()) result.add(current)
        }
        return if (result.isEmpty()) listOf("—") else result
    }

    private fun truncate(s: String, maxLen: Int): String {
        if (s.length <= maxLen) return s
        return s.take(maxLen - 1) + "…"
    }
}
