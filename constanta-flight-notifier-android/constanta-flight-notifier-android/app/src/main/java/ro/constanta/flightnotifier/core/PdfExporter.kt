package ro.constanta.flightnotifier.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import ro.constanta.flightnotifier.model.EventSearchFilter
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfExporter {

    private const val PAGE_WIDTH = 595   // A4 portrait (72 dpi)
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 32f
    private const val MARGIN_TOP = 38f
    private const val MARGIN_BOTTOM = 42f
    private const val ROW_HEIGHT = 22f

    fun exportToStream(
        outputStream: OutputStream,
        events: List<FlightEvent>,
        filter: EventSearchFilter
    ) {
        val doc = PdfDocument()
        val roLocale = Locale("ro", "RO")
        val dtFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", roLocale)
        val generatedFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", roLocale)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F1B24")
            textSize = 15f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            textSize = 9.5f
            typeface = Typeface.SANS_SERIF
        }
        val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 9.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val headerBgPaint = Paint().apply {
            color = Color.parseColor("#0F1B24")
            style = Paint.Style.FILL
        }
        val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 8.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val rowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 8.2f
            typeface = Typeface.SANS_SERIF
        }
        val rowAltBgPaint = Paint().apply {
            color = Color.parseColor("#F1F5F9")
            style = Paint.Style.FILL
        }
        val linePaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 0.7f
        }
        val landingPaint = Paint(rowTextPaint).apply {
            color = Color.parseColor("#0F766E")
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val takeoffPaint = Paint(rowTextPaint).apply {
            color = Color.parseColor("#0369A1")
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val approachPaint = Paint(rowTextPaint).apply {
            color = Color.parseColor("#B45309")
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val emergencyPaint = Paint(rowTextPaint).apply {
            color = Color.parseColor("#DC2626")
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 8f
            typeface = Typeface.SANS_SERIF
        }

        // Poziții coloane X (lățime utilă: 595 - 64 = 531 pt)
        val colDate = MARGIN_X + 4f
        val colType = MARGIN_X + 86f
        val colCallsign = MARGIN_X + 144f
        val colRwy = MARGIN_X + 204f
        val colAircraft = MARGIN_X + 236f
        val colRoute = MARGIN_X + 382f
        val colTelemetry = MARGIN_X + 458f

        fun drawTableHeader(canvas: Canvas, y: Float): Float {
            canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + 20f, headerBgPaint)
            val ty = y + 13.5f
            canvas.drawText("Data și ora", colDate, ty, headerTextPaint)
            canvas.drawText("Tip", colType, ty, headerTextPaint)
            canvas.drawText("Indicativ", colCallsign, ty, headerTextPaint)
            canvas.drawText("Pistă", colRwy, ty, headerTextPaint)
            canvas.drawText("Aeronavă / Operator", colAircraft, ty, headerTextPaint)
            canvas.drawText("Rută", colRoute, ty, headerTextPaint)
            canvas.drawText("Alt / Vit.", colTelemetry, ty, headerTextPaint)
            return y + 20f
        }

        val intervalText = buildString {
            val fromStr = filter.fromMs?.let { dtFmt.format(Date(it)) } ?: "Început"
            val toStr = filter.toMs?.let { dtFmt.format(Date(it)) } ?: "Prezent"
            append("Interval: $fromStr — $toStr")
            if (filter.type != null) {
                val tName = when (filter.type) {
                    FlightEventType.LANDING -> "Doar aterizări"
                    FlightEventType.TAKEOFF -> "Doar decolări"
                    FlightEventType.APPROACH -> "Doar apropieri"
                    FlightEventType.GO_AROUND -> "Doar ratări aterizare (Go-Around)"
                    FlightEventType.EMERGENCY -> "Doar urgențe (7700/PAN/7600/7500)"
                }
                append("  |  Filtru: $tName")
            }
            if (filter.query.isNotBlank()) {
                append("  |  Căutare: \"${filter.query.trim()}\"")
            }
        }

        val landings = events.count { it.type == FlightEventType.LANDING }
        val takeoffs = events.count { it.type == FlightEventType.TAKEOFF }
        val goArounds = events.count { it.type == FlightEventType.GO_AROUND }
        val emergencies = events.count { it.type == FlightEventType.EMERGENCY }

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas

        // Header pagina 1
        var y = MARGIN_TOP
        canvas.drawText("SAT - Monitorizare trafic CND • Raport Trafic Aerian (LRCK)", MARGIN_X, y, titlePaint)
        y += 15f
        canvas.drawText(
            "Aeroportul Internațional Mihail Kogălniceanu • Pista 18/36 • Generat la ${generatedFmt.format(Date())}",
            MARGIN_X,
            y,
            subtitlePaint
        )
        y += 13f
        canvas.drawText(truncate(intervalText, 105), MARGIN_X, y, subtitlePaint)
        y += 15f
        canvas.drawText(
            "Total: ${events.size}   •   Aterizări: $landings   •   Decolări: $takeoffs   •   Ratări: $goArounds   •   Urgențe: $emergencies",
            MARGIN_X,
            y,
            summaryPaint
        )
        y += 12f
        y = drawTableHeader(canvas, y)

        if (events.isEmpty()) {
            y += 24f
            canvas.drawText("Nu există zboruri care să corespundă criteriilor selectate.", colDate, y, subtitlePaint)
        } else {
            events.forEachIndexed { index, evt ->
                if (y + ROW_HEIGHT > PAGE_HEIGHT - MARGIN_BOTTOM) {
                    canvas.drawText(
                        "SAT - Monitorizare trafic CND • Drept de proprietate intelectuală P. G. Petria • Pagina $pageNumber",
                        MARGIN_X,
                        PAGE_HEIGHT - 18f,
                        footerPaint
                    )
                    doc.finishPage(page)
                    pageNumber += 1
                    pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                    page = doc.startPage(pageInfo)
                    canvas = page.canvas
                    y = MARGIN_TOP
                    y = drawTableHeader(canvas, y)
                }

                if (index % 2 == 1) {
                    canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + ROW_HEIGHT, rowAltBgPaint)
                }

                val ty = y + 14.5f
                val emCode = evt.emergencyCode ?: evt.squawk ?: ""
                val (typeLbl, typePaint) = when (evt.type) {
                    FlightEventType.LANDING -> "Aterizare" to landingPaint
                    FlightEventType.TAKEOFF -> "Decolare" to takeoffPaint
                    FlightEventType.APPROACH -> "Apropiere" to approachPaint
                    FlightEventType.GO_AROUND -> "Go-Around" to emergencyPaint
                    FlightEventType.EMERGENCY -> {
                        val lbl = if (emCode == "PAN") "PAN-PAN" else "URG $emCode".trim()
                        lbl to emergencyPaint
                    }
                }
                val acInfo = listOfNotNull(evt.aircraftType, evt.airline, evt.registration)
                    .joinToString(" • ")
                    .ifBlank { "—" }
                val routeInfo = evt.route?.ifBlank { "—" } ?: "—"
                val telem = "${evt.altAglFt ?: "—"}ft / ${evt.gsKt ?: "—"}kt"
                val callsignText = if (evt.simulated) "${evt.callsign}*" else evt.callsign

                canvas.drawText(dtFmt.format(Date(evt.timestampMs)), colDate, ty, rowTextPaint)
                canvas.drawText(typeLbl, colType, ty, typePaint)
                canvas.drawText(truncate(callsignText, 11), colCallsign, ty, rowTextPaint)
                canvas.drawText(evt.runway, colRwy, ty, rowTextPaint)
                canvas.drawText(truncate(acInfo, 28), colAircraft, ty, rowTextPaint)
                canvas.drawText(truncate(routeInfo, 14), colRoute, ty, rowTextPaint)
                canvas.drawText(truncate(telem, 14), colTelemetry, ty, rowTextPaint)

                canvas.drawLine(MARGIN_X, y + ROW_HEIGHT, PAGE_WIDTH - MARGIN_X, y + ROW_HEIGHT, linePaint)
                y += ROW_HEIGHT
            }
        }

        val hasSim = events.any { it.simulated }
        val footerNote = if (hasSim) {
            "SAT - Monitorizare trafic CND • * = simulat • Drept de proprietate intelectuală P. G. Petria • Pagina $pageNumber"
        } else {
            "SAT - Monitorizare trafic CND • Drept de proprietate intelectuală P. G. Petria • Pagina $pageNumber"
        }
        canvas.drawText(footerNote, MARGIN_X, PAGE_HEIGHT - 18f, footerPaint)
        doc.finishPage(page)

        try {
            doc.writeTo(outputStream)
            outputStream.flush()
        } finally {
            doc.close()
        }
    }

    private fun truncate(s: String, maxLen: Int): String {
        if (s.length <= maxLen) return s
        return s.take(maxLen - 1) + "…"
    }
}
