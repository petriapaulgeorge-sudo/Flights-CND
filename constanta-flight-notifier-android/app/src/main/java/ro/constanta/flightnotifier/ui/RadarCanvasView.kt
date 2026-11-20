package ro.constanta.flightnotifier.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import ro.constanta.flightnotifier.core.AirportConfig
import ro.constanta.flightnotifier.model.ActiveAircraft
import kotlin.math.cos
import kotlin.math.min

class RadarCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var aircraft: List<ActiveAircraft> = emptyList()
    private var rangeKm: Double = 25.0

    private val landmarks = listOf(
        Triple("Constanța", 44.1733, 28.6383),
        Triple("Năvodari", 44.3210, 28.6120),
        Triple("Mamaia", 44.2440, 28.6220),
        Triple("Medgidia", 44.2500, 28.2617),
        Triple("Ovidiu", 44.2600, 28.5600)
    )

    private val coastline = listOf(
        44.68 to 28.94,
        44.56 to 28.82,
        44.45 to 28.72,
        44.35 to 28.66,
        44.25 to 28.63,
        44.17 to 28.66,
        43.95 to 28.65
    )

    private val bgPaint = Paint().apply { color = Color.parseColor("#0F1B24") }
    private val seaFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#571A4D72")
        style = Paint.Style.FILL
    }
    private val coastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#665CA1CF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3894B8CE")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6614B8A6")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val corridorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6614B8A6")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 12f), 0f)
    }
    private val runwayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9FB5C3")
        textSize = 26f
        typeface = Typeface.MONOSPACE
    }
    private val callsignPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F1F5F9")
        textSize = 28f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9FB5C3")
        textSize = 23f
        typeface = Typeface.MONOSPACE
    }
    private val aircraftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
    }
    private val arrowPath = Path().apply {
        moveTo(0f, -18f)
        lineTo(12f, 13f)
        lineTo(0f, 6f)
        lineTo(-12f, 13f)
        close()
    }

    fun updateAircraft(list: List<ActiveAircraft>) {
        aircraft = list
        invalidate()
    }

    fun setRangeKm(km: Double) {
        rangeKm = km.coerceIn(10.0, 60.0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val maxR = min(w, h) * 0.44f
        val scale = (maxR / rangeKm).toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Marea Neagră
        val seaPath = Path()
        coastline.forEachIndexed { idx, (lat, lon) ->
            val (px, py) = project(lat, lon, cx, cy, scale)
            if (idx == 0) seaPath.moveTo(px, py) else seaPath.lineTo(px, py)
        }
        seaPath.lineTo(w + 60f, h + 60f)
        seaPath.lineTo(w + 60f, -60f)
        seaPath.close()
        canvas.drawPath(seaPath, seaFillPaint)
        canvas.drawPath(seaPath, coastPaint)

        // Cercuri de distanță
        val rings = if (rangeKm <= 25.0) listOf(5.0, 15.0, 25.0) else listOf(10.0, 25.0, 45.0)
        labelPaint.textAlign = Paint.Align.CENTER
        for (r in rings) {
            val rPx = (r * scale).toFloat()
            canvas.drawCircle(cx, cy, rPx, if (r <= 5.0) innerRingPaint else ringPaint)
            canvas.drawText("${r.toInt()} km", cx, cy - rPx + 28f, labelPaint)
        }

        // Axe N-S / E-V
        canvas.drawLine(cx - maxR, cy, cx + maxR, cy, ringPaint)
        canvas.drawLine(cx, cy - maxR, cx, cy + maxR, ringPaint)

        // Coridor de apropiere Pista 18/36
        val corridorPx = min(maxR * 0.85f, 13f * scale)
        canvas.drawLine(cx, cy - corridorPx, cx, cy + corridorPx, corridorPaint)

        // Pista 18/36
        val rwyHalf = (1.75f * scale).coerceAtLeast(18f)
        canvas.drawLine(cx, cy - rwyHalf, cx, cy + rwyHalf, runwayPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("18", cx + 18f, cy - rwyHalf + 8f, labelPaint)
        canvas.drawText("36", cx + 18f, cy + rwyHalf + 8f, labelPaint)

        // Repere Constanța
        for ((name, lat, lon) in landmarks) {
            val (px, py) = project(lat, lon, cx, cy, scale)
            if (px < 12f || px > w - 120f || py < 28f || py > h - 16f) continue
            canvas.drawCircle(px, py, 4f, labelPaint)
            canvas.drawText(name, px + 10f, py + 8f, labelPaint)
        }

        // Aeronave + trasee
        for (ac in aircraft) {
            val col = phaseColor(ac.phase)
            if (ac.trail.size > 1) {
                trailPaint.color = Color.argb(120, Color.red(col), Color.green(col), Color.blue(col))
                val tPath = Path()
                ac.trail.forEachIndexed { idx, (lat, lon) ->
                    val (tx, ty) = project(lat, lon, cx, cy, scale)
                    if (idx == 0) tPath.moveTo(tx, ty) else tPath.lineTo(tx, ty)
                }
                canvas.drawPath(tPath, trailPaint)
            }

            val (px, py) = project(ac.lat, ac.lon, cx, cy, scale)
            aircraftPaint.color = col
            canvas.save()
            canvas.translate(px, py)
            canvas.rotate((ac.trackDeg ?: 0).toFloat())
            canvas.drawPath(arrowPath, aircraftPaint)
            canvas.restore()

            canvas.drawText(ac.callsign, px + 18f, py - 2f, callsignPaint)
            val altStr = if (ac.onGround) "SOL" else "${ac.altAglFt ?: "—"}ft"
            val gsStr = ac.gsKt?.let { "${it}kt" } ?: ""
            canvas.drawText("$altStr $gsStr".trim(), px + 18f, py + 24f, subLabelPaint)
        }
    }

    private fun project(lat: Double, lon: Double, cx: Float, cy: Float, scale: Float): Pair<Float, Float> {
        val kmPerDegLat = 111.132
        val kmPerDegLon = 111.320 * cos(Math.toRadians(AirportConfig.LAT))
        val dxKm = (lon - AirportConfig.LON) * kmPerDegLon
        val dyKm = (lat - AirportConfig.LAT) * kmPerDegLat
        return (cx + dxKm.toFloat() * scale) to (cy - dyKm.toFloat() * scale)
    }

    private fun phaseColor(phase: String): Int = when (phase) {
        "approaching", "landed" -> Color.parseColor("#14B8A6")
        "departing" -> Color.parseColor("#38BDF8")
        "ground" -> Color.parseColor("#F59E0B")
        else -> Color.parseColor("#CBD5E1")
    }
}
