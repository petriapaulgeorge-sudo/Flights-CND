package ro.constanta.flightnotifier.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import ro.constanta.flightnotifier.core.AirportConfig
import ro.constanta.flightnotifier.model.ActiveAircraft
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

class RadarCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var aircraft: List<ActiveAircraft> = emptyList()
    private var rangeKm: Double = 25.0
    private var selectedHex: String? = null
    private var sweepAngleDeg: Float = 0f
    private var lastFrameMs: Long = 0L
    private var animRunning: Boolean = false

    private var onAircraftSelected: ((ActiveAircraft?) -> Unit)? = null
    private val screenPositions = HashMap<String, Pair<Float, Float>>()

    // Cache imagini satelit (memorie + disc local)
    private val tileMemCache = object : LruCache<String, Bitmap>(72) {}
    private val inflightTiles = Collections.synchronizedSet(HashSet<String>())
    private val tileExecutor = Executors.newFixedThreadPool(4)
    private val tileCacheDir = File(context.cacheDir, "sat_tiles").apply { mkdirs() }

    private val landmarks = listOf(
        Triple("Constanța", 44.1733, 28.6383),
        Triple("Năvodari", 44.3210, 28.6120),
        Triple("Mamaia", 44.2440, 28.6220),
        Triple("Medgidia", 44.2500, 28.2617),
        Triple("Ovidiu", 44.2600, 28.5600)
    )

    // Pints & shaders
    private val bgFallbackPaint = Paint().apply { color = Color.parseColor("#0B1620") }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintOverlayPaint = Paint().apply { color = Color.argb(88, 7, 17, 26) }
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 226, 240, 250)
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 20, 184, 166)
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(75, 226, 240, 250)
        style = Paint.Style.STROKE
        strokeWidth = 1.3f
    }
    private val corridorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 45, 212, 191)
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val runwayGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
    }
    private val runwayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.BUTT
    }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 226, 240, 250)
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 226, 240, 250)
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 241, 245, 249)
        textSize = 23f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 10, 20, 30)
        style = Paint.Style.FILL
    }
    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }
    private val callsignPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 25f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textSize = 20f
        typeface = Typeface.MONOSPACE
    }
    private val aircraftHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val aircraftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val aircraftOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B1620")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val selectedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
    }
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 226, 232, 240)
        textSize = 19f
        textAlign = Paint.Align.RIGHT
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sweepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
    }
    private val sweepMatrix = Matrix()
    private val tileRect = RectF()
    private val pillRect = RectF()

    // Siluetă stilizată de aeronavă (orientată cu botul în sus = Nord / 0°)
    private val planePath = Path().apply {
        moveTo(0f, -20f)
        lineTo(3.5f, -7f)
        lineTo(18f, 2f)
        lineTo(18f, 6f)
        lineTo(3.5f, 3f)
        lineTo(2.5f, 13f)
        lineTo(8.5f, 17f)
        lineTo(8.5f, 20f)
        lineTo(0f, 17.5f)
        lineTo(-8.5f, 20f)
        lineTo(-8.5f, 17f)
        lineTo(-2.5f, 13f)
        lineTo(-3.5f, 3f)
        lineTo(-18f, 6f)
        lineTo(-18f, 2f)
        lineTo(-3.5f, -7f)
        close()
    }

    private val sweepTicker = object : Runnable {
        override fun run() {
            if (!animRunning || !isAttachedToWindow || visibility != VISIBLE) {
                animRunning = false
                return
            }
            val now = System.currentTimeMillis()
            val dt = if (lastFrameMs == 0L) 33L else (now - lastFrameMs).coerceIn(16L, 100L)
            lastFrameMs = now
            sweepAngleDeg = (sweepAngleDeg + dt * 0.055f) % 360f
            invalidate()
            postOnAnimation(this)
        }
    }

    fun setOnAircraftSelectedListener(listener: (ActiveAircraft?) -> Unit) {
        onAircraftSelected = listener
    }

    fun updateAircraft(list: List<ActiveAircraft>) {
        aircraft = list
        if (selectedHex != null) {
            val found = list.find { it.hex == selectedHex }
            if (found == null) {
                selectedHex = null
                onAircraftSelected?.invoke(null)
            } else {
                onAircraftSelected?.invoke(found)
            }
        }
        invalidate()
    }

    fun setRangeKm(km: Double) {
        rangeKm = km.coerceIn(10.0, 110.0)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startSweepAnimation()
    }

    override fun onDetachedFromWindow() {
        animRunning = false
        removeCallbacks(sweepTicker)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startSweepAnimation() else animRunning = false
    }

    private fun startSweepAnimation() {
        if (animRunning || !isAttachedToWindow) return
        animRunning = true
        lastFrameMs = System.currentTimeMillis()
        postOnAnimation(sweepTicker)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val tx = event.x
            val ty = event.y
            var best: ActiveAircraft? = null
            var bestDist = 64f
            for (ac in aircraft) {
                val p = screenPositions[ac.hex] ?: continue
                val d = hypot(p.first - tx, p.second - ty)
                if (d < bestDist) {
                    bestDist = d
                    best = ac
                }
            }
            selectedHex = best?.hex
            onAircraftSelected?.invoke(best)
            invalidate()
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val maxR = min(w, h) * 0.44f
        val screenPxPerKm = maxR / rangeKm

        // 1. Fundal de rezervă + Harta SATELIT (Esri World Imagery, Web Mercator)
        canvas.drawRect(0f, 0f, w, h, bgFallbackPaint)

        val latCos = cos(Math.toRadians(AirportConfig.LAT))
        val idealZoom = log2((40075.016686 * latCos * screenPxPerKm) / 256.0)
        val zoom = floor(idealZoom).toInt().coerceIn(7, 13)
        val n = 2.0.pow(zoom.toDouble())
        val kmPerWorldPx = (40075.016686 * latCos) / (256.0 * n)
        val scaleFactor = (screenPxPerKm * kmPerWorldPx).toFloat()

        val centerWx = lonToWorldX(AirportConfig.LON, n)
        val centerWy = latToWorldY(AirportConfig.LAT, n)

        drawSatelliteTiles(canvas, w, h, cx, cy, zoom, n.toInt(), centerWx, centerWy, scaleFactor)

        // 2. Strat subtil de contrast tactic + vignetă radială pentru lizibilitate maximă
        canvas.drawRect(0f, 0f, w, h, tintOverlayPaint)
        vignettePaint.shader = RadialGradient(
            cx, cy, maxR * 1.45f,
            intArrayOf(Color.TRANSPARENT, Color.argb(125, 6, 14, 22)),
            floatArrayOf(0.55f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, vignettePaint)

        // 3. Fascicul radar rotativ (SweepGradient)
        val sweepShader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.argb(0, 56, 189, 248),
                Color.argb(0, 56, 189, 248),
                Color.argb(65, 56, 189, 248)
            ),
            floatArrayOf(0f, 0.84f, 1f)
        )
        sweepMatrix.setRotate(sweepAngleDeg - 90f, cx, cy)
        sweepShader.setLocalMatrix(sweepMatrix)
        sweepPaint.shader = sweepShader
        canvas.drawCircle(cx, cy, maxR, sweepPaint)

        val sweepRad = Math.toRadians((sweepAngleDeg - 90f).toDouble())
        canvas.drawLine(
            cx, cy,
            cx + (cos(sweepRad) * maxR).toFloat(),
            cy + (sin(sweepRad) * maxR).toFloat(),
            sweepLinePaint
        )

        // 4. Cercuri de distanță + busolă tactică
        val rings = when {
            rangeKm <= 16.0 -> listOf(5.0, 10.0, 15.0)
            rangeKm <= 28.0 -> listOf(5.0, 15.0, 25.0)
            rangeKm <= 55.0 -> listOf(10.0, 25.0, 45.0)
            else -> listOf(25.0, 50.0, 100.0)
        }
        for (r in rings) {
            val rPx = (r * screenPxPerKm).toFloat()
            val paint = when {
                r <= 5.0 -> innerRingPaint
                r >= rangeKm - 1.0 -> outerRingPaint
                else -> ringPaint
            }
            canvas.drawCircle(cx, cy, rPx, paint)
            canvas.drawText("${r.toInt()} km", cx, cy - rPx + 24f, ringLabelPaint)
        }

        // Axe și gradații busolă (la fiecare 30°)
        canvas.drawLine(cx - maxR, cy, cx + maxR, cy, axisPaint)
        canvas.drawLine(cx, cy - maxR, cx, cy + maxR, axisPaint)
        for (deg in 0 until 360 step 30) {
            val rad = Math.toRadians((deg - 90).toDouble())
            val innerTick = maxR - if (deg % 90 == 0) 14f else 8f
            canvas.drawLine(
                cx + (cos(rad) * innerTick).toFloat(),
                cy + (sin(rad) * innerTick).toFloat(),
                cx + (cos(rad) * maxR).toFloat(),
                cy + (sin(rad) * maxR).toFloat(),
                ringPaint
            )
        }
        canvas.drawText("N", cx, cy - maxR - 8f, compassPaint)
        canvas.drawText("S", cx, cy + maxR + 24f, compassPaint)
        canvas.drawText("E", cx + maxR + 16f, cy + 8f, compassPaint)
        canvas.drawText("V", cx - maxR - 16f, cy + 8f, compassPaint)

        // 5. Coridor apropiere + Pista 18/36 (aliniată exact pe coordonatele reale ale pragurilor!)
        val (r18x, r18y) = project(44.37783, 28.49067, cx, cy, n, centerWx, centerWy, scaleFactor)
        val (r36x, r36y) = project(44.34650, 28.48633, cx, cy, n, centerWx, centerWy, scaleFactor)

        val corridorLen = min(maxR * 0.88f, (13.0 * screenPxPerKm).toFloat())
        canvas.drawLine(cx, cy - corridorLen, cx, cy + corridorLen, corridorPaint)

        // Dacă pista e foarte scurtă la zoom mic, îi păstrăm vizibilitatea minimă
        val rwyHalfY = (kotlin.math.abs(r36y - r18y) / 2f).coerceAtLeast(16f)
        canvas.drawLine(r18x, cy - rwyHalfY, r36x, cy + rwyHalfY, runwayGlowPaint)
        canvas.drawLine(r18x, cy - rwyHalfY, r36x, cy + rwyHalfY, runwayPaint)

        val prevAlign = ringLabelPaint.textAlign
        ringLabelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("18", r18x + 14f, cy - rwyHalfY + 8f, ringLabelPaint)
        canvas.drawText("36", r36x + 14f, cy + rwyHalfY + 8f, ringLabelPaint)
        ringLabelPaint.textAlign = prevAlign

        // 6. Repere Dobrogea / Constanța
        for ((name, lat, lon) in landmarks) {
            val (px, py) = project(lat, lon, cx, cy, n, centerWx, centerWy, scaleFactor)
            if (px < 16f || px > w - 110f || py < 28f || py > h - 22f) continue
            canvas.drawCircle(px, py, 3.8f, landmarkPaint)
            canvas.drawText(name, px + 9f, py + 7f, landmarkPaint)
        }

        // 7. Aeronave live, trasee și etichete tip HUD
        screenPositions.clear()
        for (ac in aircraft) {
            val emInfo = AirportConfig.emergencyInfo(ac.squawk, ac.emergencyStatus)
            val isEmergency = emInfo != null || ac.phase == "emergency" || ac.phase == "emergency_pan"
            val col = when {
                emInfo?.code == "PAN" || ac.phase == "emergency_pan" -> Color.parseColor("#F97316")
                isEmergency -> Color.parseColor("#EF4444")
                ac.phase == "go_around" -> Color.parseColor("#F43F5E")
                ac.military -> Color.parseColor("#C084FC")
                else -> phaseColor(ac.phase)
            }

            if (ac.trail.size > 1) {
                trailPaint.color = Color.argb(175, Color.red(col), Color.green(col), Color.blue(col))
                val tPath = Path()
                ac.trail.forEachIndexed { idx, (lat, lon) ->
                    val (tx, ty) = project(lat, lon, cx, cy, n, centerWx, centerWy, scaleFactor)
                    if (idx == 0) tPath.moveTo(tx, ty) else tPath.lineTo(tx, ty)
                }
                canvas.drawPath(tPath, trailPaint)
            }

            val (px, py) = project(ac.lat, ac.lon, cx, cy, n, centerWx, centerWy, scaleFactor)
            screenPositions[ac.hex] = px to py

            // Halo luminos discret (pulsant pentru cod de urgență / PAN / Go-Around!)
            val isHighAlert = isEmergency || ac.phase == "go_around"
            val haloRadius = if (isHighAlert) 26f + 8f * sin(Math.toRadians((sweepAngleDeg * 4f).toDouble())).toFloat() else if (ac.military) 25f else 22f
            aircraftHaloPaint.color = Color.argb(if (isHighAlert) 125 else if (ac.military) 96 else 72, Color.red(col), Color.green(col), Color.blue(col))
            canvas.drawCircle(px, py, haloRadius, aircraftHaloPaint)

            if (selectedHex == ac.hex) {
                canvas.drawCircle(px, py, 27f, selectedRingPaint)
            }

            aircraftPaint.color = col
            canvas.save()
            canvas.translate(px, py)
            canvas.rotate((ac.trackDeg ?: 0).toFloat())
            canvas.drawPath(planePath, aircraftPaint)
            canvas.drawPath(planePath, aircraftOutlinePaint)
            canvas.restore()

            // Etichetă lizibilă (pill semi-transparent) deasupra imaginii satelit
            val line1 = when {
                emInfo != null -> "${emInfo.shortBadge} ${ac.callsign}"
                ac.phase == "go_around" -> "GO-AROUND ${ac.callsign}"
                ac.military -> "MIL ${ac.callsign}"
                else -> ac.callsign
            }
            val altStr = if (ac.onGround) "SOL" else "${ac.altAglFt ?: "—"}ft"
            val gsStr = ac.gsKt?.let { "${it}kt" } ?: ""
            val line2 = "$altStr $gsStr".trim()
            val textW = maxOf(callsignPaint.measureText(line1), subLabelPaint.measureText(line2))
            val boxLeft = (px + 20f).coerceAtMost(w - textW - 22f)
            val boxTop = (py - 28f).coerceAtLeast(10f)
            pillRect.set(boxLeft, boxTop, boxLeft + textW + 16f, boxTop + 50f)

            pillStrokePaint.color = Color.argb(220, Color.red(col), Color.green(col), Color.blue(col))
            canvas.drawRoundRect(pillRect, 8f, 8f, pillBgPaint)
            canvas.drawRoundRect(pillRect, 8f, 8f, pillStrokePaint)
            canvas.drawText(line1, pillRect.left + 8f, pillRect.top + 23f, callsignPaint)
            canvas.drawText(line2, pillRect.left + 8f, pillRect.top + 43f, subLabelPaint)
        }

        // Atribuire hartă satelit
        canvas.drawText("Satelit: Esri World Imagery", w - 12f, h - 10f, attributionPaint)
    }

    private fun drawSatelliteTiles(
        canvas: Canvas,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
        zoom: Int,
        maxTileIndex: Int,
        centerWx: Double,
        centerWy: Double,
        scaleFactor: Float
    ) {
        val tileDrawSize = 256f * scaleFactor
        if (tileDrawSize <= 1f) return

        val worldLeft = centerWx - (cx / scaleFactor)
        val worldRight = centerWx + ((w - cx) / scaleFactor)
        val worldTop = centerWy - (cy / scaleFactor)
        val worldBottom = centerWy + ((h - cy) / scaleFactor)

        val minTx = floor(worldLeft / 256.0).toInt().coerceIn(0, maxTileIndex - 1)
        val maxTx = floor(worldRight / 256.0).toInt().coerceIn(0, maxTileIndex - 1)
        val minTy = floor(worldTop / 256.0).toInt().coerceIn(0, maxTileIndex - 1)
        val maxTy = floor(worldBottom / 256.0).toInt().coerceIn(0, maxTileIndex - 1)

        for (tx in minTx..maxTx) {
            for (ty in minTy..maxTy) {
                val bmp = getOrFetchTile(zoom, tx, ty)
                if (bmp != null) {
                    val left = cx + ((tx * 256.0 - centerWx) * scaleFactor).toFloat()
                    val top = cy + ((ty * 256.0 - centerWy) * scaleFactor).toFloat()
                    tileRect.set(left, top, left + tileDrawSize + 0.8f, top + tileDrawSize + 0.8f)
                    canvas.drawBitmap(bmp, null, tileRect, tilePaint)
                }
            }
        }
    }

    private fun getOrFetchTile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "${z}_${x}_${y}"
        tileMemCache.get(key)?.let { return it }

        if (!inflightTiles.add(key)) return null

        tileExecutor.execute {
            try {
                val cacheFile = File(tileCacheDir, "$key.jpg")
                val bitmap = if (cacheFile.exists() && cacheFile.length() > 0L) {
                    BitmapFactory.decodeFile(cacheFile.absolutePath)
                } else {
                    downloadTile(z, x, y, cacheFile)
                }
                if (bitmap != null) {
                    tileMemCache.put(key, bitmap)
                    postInvalidate()
                }
            } catch (_: Exception) {
                // Ignoră erorile temporare de rețea; păstrează fundalul tactic
            } finally {
                inflightTiles.remove(key)
            }
        }
        return null
    }

    private fun downloadTile(z: Int, x: Int, y: Int, targetFile: File): Bitmap? {
        // Esri ArcGIS World Imagery folosește ordinea /tile/{z}/{y}/{x}
        val urlStr = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "SAT-Monitorizare-Trafic-CND/1.2")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            runCatching {
                FileOutputStream(targetFile).use { it.write(bytes) }
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            conn.disconnect()
        }
    }

    private fun lonToWorldX(lon: Double, n: Double): Double {
        return ((lon + 180.0) / 360.0) * n * 256.0
    }

    private fun latToWorldY(lat: Double, n: Double): Double {
        val rad = Math.toRadians(lat)
        val merc = ln(tan(rad) + 1.0 / cos(rad))
        return ((1.0 - merc / Math.PI) / 2.0) * n * 256.0
    }

    private fun project(
        lat: Double,
        lon: Double,
        cx: Float,
        cy: Float,
        n: Double,
        centerWx: Double,
        centerWy: Double,
        scaleFactor: Float
    ): Pair<Float, Float> {
        val wx = lonToWorldX(lon, n)
        val wy = latToWorldY(lat, n)
        return (cx + ((wx - centerWx) * scaleFactor).toFloat()) to
            (cy + ((wy - centerWy) * scaleFactor).toFloat())
    }

    private fun phaseColor(phase: String): Int = when (phase) {
        "approaching", "landed" -> Color.parseColor("#2DD4BF")
        "departing" -> Color.parseColor("#38BDF8")
        "ground" -> Color.parseColor("#FBBF24")
        else -> Color.parseColor("#F1F5F9")
    }
}
