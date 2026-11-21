package ro.constanta.flightnotifier.core

import org.json.JSONArray
import org.json.JSONObject
import ro.constanta.flightnotifier.model.BorderRegionAlarm
import ro.constanta.flightnotifier.model.DroneMonitorSnapshot
import ro.constanta.flightnotifier.model.DroneTarget
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor de monitorizare și geofencing pentru spațiul aerian al Dobrogei
 * (frontiera Dunăre / Brațul Chilia + apele teritoriale 12 NM la Marea Neagră).
 *
 * Utilizează exclusiv surse oficiale cu cheie configurată de utilizator:
 *  - DIMAP-API (cheie gratuită prin suportul oficial @di_helper)
 *  - Ukraine Alarm API (api.ukrainealarm.com — opțional, filtrat strict pe Raionul Izmail / Odesa)
 */
object DobrogeaDroneMonitor {

    const val DEFAULT_DIMAP_API_URL = "https://api.dimap.live/api/polling"
    const val FALLBACK_DIMAP_TARGETS_URL = "https://api.dimap.live/targets"
    const val FALLBACK_DIMAP_POLLING_HTTP_URL = "http://api.dimap.live/api/polling"
    const val DIMAP_LITE_URL = "https://dimap.live/lite"
    const val DIMAP_FULL_URL = "https://dimap.live/"
    const val DIMAP_HELPER_TELEGRAM_URL = "https://t.me/di_helper"
    const val UKRAINE_ALARM_API_URL = "https://api.ukrainealarm.com/api/v3/alerts"
    const val UKRAINE_ALARM_PORTAL_URL = "https://api.ukrainealarm.com/"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val USER_AGENT = "SAT-Monitorizare-Trafic-CND/2.0 (Dobrogea-Awareness)"

    data class BorderPoint(val lat: Double, val lon: Double, val landmark: String)

    /**
     * Conturul închis al spațiului aerian al Dobrogei (uscat + limita 12 NM ape teritoriale Marea Neagră),
     * ordonat în sens orar pornind de la confluența Prut–Dunăre.
     */
    val DOBROGEA_AIRSPACE_POLYGON: List<BorderPoint> = listOf(
        BorderPoint(45.4660, 28.2060, "Grindu / Giurgiulești (Dunăre)"),
        BorderPoint(45.4300, 28.2680, "Cotul Pisicii (vizavi Reni)"),
        BorderPoint(45.2845, 28.4640, "Isaccea (Dunăre)"),
        BorderPoint(45.3250, 28.7980, "Plauru / Ceatalchioi (vizavi Izmail)"),
        BorderPoint(45.3180, 28.9530, "Pardina (Brațul Chilia)"),
        BorderPoint(45.4240, 29.2900, "Chilia Veche (vizavi Chilia Nouă)"),
        BorderPoint(45.4050, 29.5550, "Periprava (vizavi Vâlcov)"),
        BorderPoint(45.2150, 29.7400, "Gurile Dunării / Musura"),
        BorderPoint(45.1580, 30.0250, "Sulina — larg 12 NM (Marea Neagră)"),
        BorderPoint(44.8850, 29.8900, "Sfântu Gheorghe — larg 12 NM"),
        BorderPoint(44.6850, 29.2800, "Gura Portiței — larg 12 NM"),
        BorderPoint(44.3400, 28.9800, "Cap Midia / Năvodari — larg 12 NM"),
        BorderPoint(44.1700, 28.9300, "Constanța — larg 12 NM"),
        BorderPoint(43.7350, 28.8600, "Mangalia / Vama Veche — larg 12 NM"),
        BorderPoint(43.7380, 28.5790, "Vama Veche (țărm)"),
        BorderPoint(43.8160, 28.2100, "Negru Vodă (Dobrogea Sud)"),
        BorderPoint(44.1130, 27.3720, "Ostrov (Dunăre SV)"),
        BorderPoint(44.3380, 28.0330, "Cernavodă (Dunăre)"),
        BorderPoint(44.6850, 27.9480, "Hârșova (Dunăre)"),
        BorderPoint(45.2450, 28.1220, "Măcin (Dunăre NV)")
    )

    private val DOBROGEA_INTERIOR_LANDMARKS: List<BorderPoint> = listOf(
        BorderPoint(45.1780, 28.8050, "Tulcea"),
        BorderPoint(44.8920, 28.7160, "Babadag"),
        BorderPoint(44.3622, 28.4883, "Aeroport CND / Mihail Kogălniceanu"),
        BorderPoint(44.1733, 28.6383, "Constanța"),
        BorderPoint(44.3240, 28.6130, "Năvodari / Midia"),
        BorderPoint(44.2500, 28.2610, "Medgidia"),
        BorderPoint(43.8170, 28.5820, "Mangalia"),
        BorderPoint(45.1560, 29.6560, "Sulina")
    )

    data class DobrogeaProximity(
        val insideDobrogea: Boolean,
        val distanceKm: Double,
        val nearestLandmark: String,
        val distToCndKm: Double,
        val bearingFromCnd: String
    )

    data class DimapFetchResult(
        val ok: Boolean,
        val targets: List<DroneTarget> = emptyList(),
        val borderAlarms: List<BorderRegionAlarm> = emptyList(),
        val totalParsed: Int = 0,
        val statusMessage: String = ""
    )

    data class UkraineAlarmFetchResult(
        val ok: Boolean,
        val alarms: List<BorderRegionAlarm> = emptyList(),
        val statusMessage: String = ""
    )

    /**
     * Evaluează poziția unei ținte (lat, lon) față de spațiul aerian al Dobrogei
     * (inclusiv apele teritoriale 12 NM la Marea Neagră) și față de Aeroportul CND.
     */
    fun evaluateProximityToDobrogea(lat: Double, lon: Double): DobrogeaProximity {
        val inside = isInsideDobrogeaAirspace(lat, lon)
        val distToCnd = round1(haversineKm(AirportConfig.LAT, AirportConfig.LON, lat, lon))
        val bearingFromCnd = compassFromBearing(bearingDeg(AirportConfig.LAT, AirportConfig.LON, lat, lon))

        if (inside) {
            val allRefs = DOBROGEA_AIRSPACE_POLYGON + DOBROGEA_INTERIOR_LANDMARKS
            val nearest = allRefs.minByOrNull { haversineKm(lat, lon, it.lat, it.lon) }?.landmark ?: "Dobrogea"
            return DobrogeaProximity(
                insideDobrogea = true,
                distanceKm = 0.0,
                nearestLandmark = nearest,
                distToCndKm = distToCnd,
                bearingFromCnd = bearingFromCnd
            )
        }

        var minDist = Double.MAX_VALUE
        var nearestName = "Dobrogea"
        val poly = DOBROGEA_AIRSPACE_POLYGON
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val (segDist, closerToB) = pointToSegmentDistanceKm(lat, lon, a.lat, a.lon, b.lat, b.lon)
            if (segDist < minDist) {
                minDist = segDist
                nearestName = if (closerToB) b.landmark else a.landmark
            }
        }

        return DobrogeaProximity(
            insideDobrogea = false,
            distanceKm = round1(minDist),
            nearestLandmark = nearestName,
            distToCndKm = distToCnd,
            bearingFromCnd = bearingFromCnd
        )
    }

    /**
     * Ray-casting standard pentru a verifica dacă punctul (lat, lon) se află deja
     * în interiorul spațiului aerian al Dobrogei (terestru + 12 NM Marea Neagră).
     */
    fun isInsideDobrogeaAirspace(lat: Double, lon: Double): Boolean {
        val poly = DOBROGEA_AIRSPACE_POLYGON
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val yi = poly[i].lat
            val xi = poly[i].lon
            val yj = poly[j].lat
            val xj = poly[j].lon
            val intersects = ((yi > lat) != (yj > lat)) &&
                (lon < (xj - xi) * (lat - yi) / ((yj - yi) + 1e-12) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Distanța minimă (km) de la un punct P(lat, lon) la segmentul geodezic A->B
     * folosind proiecție locală equirectangulară (precizie < 50 m la scara Dobrogei).
     * Returnează perechea (distKm, closerToB).
     */
    internal fun pointToSegmentDistanceKm(
        pLat: Double,
        pLon: Double,
        aLat: Double,
        aLon: Double,
        bLat: Double,
        bLon: Double
    ): Pair<Double, Boolean> {
        val meanLatRad = Math.toRadians((pLat + aLat + bLat) / 3.0)
        val kmPerDegLat = 111.132
        val kmPerDegLon = 111.320 * cos(meanLatRad)

        val ax = (aLon - pLon) * kmPerDegLon
        val ay = (aLat - pLat) * kmPerDegLat
        val bx = (bLon - pLon) * kmPerDegLon
        val by = (bLat - pLat) * kmPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9) {
            return hypot(ax, ay) to false
        }
        val t = ((-ax * dx - ay * dy) / lenSq).coerceIn(0.0, 1.0)
        val projX = ax + t * dx
        val projY = ay + t * dy
        return hypot(projX, projY) to (t > 0.5)
    }

    /**
     * Interoghează toate sursele configurate (DIMAP-API și/sau Ukraine Alarm API) și
     * construiește snapshot-ul live filtrat exclusiv pentru sectorul Dobrogea.
     */
    fun pollDobrogeaSources(
        dimapApiUrl: String,
        dimapApiKey: String,
        ukraineAlarmApiKey: String,
        alertRadiusKm: Int,
        nowMs: Long = System.currentTimeMillis()
    ): DroneMonitorSnapshot {
        val thresholdKm = alertRadiusKm.coerceIn(5, 50).toDouble()
        val nearbyCutoffKm = maxOf(150.0, thresholdKm * 3.0)

        val dimapUrlClean = dimapApiUrl.trim()
        val dimapKeyClean = dimapApiKey.trim()
        val uaKeyClean = ukraineAlarmApiKey.trim()

        val dimapConfigured = dimapUrlClean.isNotBlank()
        val uaConfigured = uaKeyClean.isNotBlank()

        val dimapRes = if (dimapConfigured) {
            fetchDimapTargets(dimapUrlClean, dimapKeyClean, nowMs)
        } else {
            DimapFetchResult(
                ok = false,
                statusMessage = "Neconfigurat — cere URL/cheia gratuită de la @di_helper"
            )
        }

        val uaRes = if (uaConfigured) {
            fetchDobrogeaBorderAlarms(uaKeyClean)
        } else if (dimapRes.ok && dimapRes.borderAlarms.isNotEmpty()) {
            UkraineAlarmFetchResult(
                ok = true,
                alarms = dimapRes.borderAlarms,
                statusMessage = "Din DIMAP-API • ${dimapRes.borderAlarms.size} alerte raioane vecine"
            )
        } else if (dimapRes.ok) {
            UkraineAlarmFetchResult(
                ok = true,
                alarms = emptyList(),
                statusMessage = "Din DIMAP-API • Fără alerte active în Izmail / Bilhorod / Bolgrad"
            )
        } else {
            UkraineAlarmFetchResult(
                ok = false,
                statusMessage = "Neconfigurat (opțional — preluat automat și din DIMAP-API)"
            )
        }

        val nearby = dimapRes.targets
            .filter { it.insideDobrogea || it.distToDobrogeaKm <= nearbyCutoffKm }
            .sortedBy { it.distToDobrogeaKm }

        val alerts = nearby.filter { it.insideDobrogea || it.distToDobrogeaKm <= thresholdKm }

        val combinedBorderAlarms = LinkedHashMap<String, BorderRegionAlarm>()
        for (a in dimapRes.borderAlarms) {
            combinedBorderAlarms[a.regionId] = a
        }
        if (uaConfigured) {
            for (a in uaRes.alarms) {
                combinedBorderAlarms[a.regionId] = a
            }
        }

        return DroneMonitorSnapshot(
            updatedAtMs = nowMs,
            dimapConfigured = dimapConfigured,
            dimapOk = dimapRes.ok,
            dimapStatus = dimapRes.statusMessage,
            ukraineAlarmConfigured = uaConfigured || dimapRes.ok,
            ukraineAlarmOk = uaRes.ok,
            ukraineAlarmStatus = uaRes.statusMessage,
            nearbyTargets = nearby,
            alertTargets = alerts,
            activeBorderAlarms = combinedBorderAlarms.values.toList(),
            totalTargetsParsed = dimapRes.totalParsed
        )
    }

    /**
     * Apelează endpoint-ul oficial DIMAP-API configurat (implicit https://api.dimap.live/api/polling)
     * cu fallback automat către https://api.dimap.live/targets și http://api.dimap.live/api/polling.
     * Suportă formatul oficial DIMAP polling_update (data.targets.targets + data.alerts.alerts).
     */
    fun fetchDimapTargets(
        endpointUrl: String,
        apiKey: String,
        nowMs: Long = System.currentTimeMillis()
    ): DimapFetchResult {
        val urlClean = endpointUrl.trim()
        val keyClean = apiKey.trim()
        val isAllowedScheme = urlClean.startsWith("https://") ||
            urlClean.startsWith("http://api.dimap.live/")
        if (!isAllowedScheme) {
            return DimapFetchResult(
                ok = false,
                statusMessage = "URL DIMAP-API invalid (folosește https://… sau http://api.dimap.live/…)"
            )
        }

        val candidates = LinkedHashSet<String>()
        candidates.add(urlClean)
        if (urlClean.contains("api.dimap.live", ignoreCase = true)) {
            if (urlClean.startsWith("http://")) {
                candidates.add(urlClean.replaceFirst("http://", "https://"))
            }
            candidates.add(DEFAULT_DIMAP_API_URL)
            candidates.add(FALLBACK_DIMAP_TARGETS_URL)
            candidates.add(FALLBACK_DIMAP_POLLING_HTTP_URL)
        }

        var lastError = DimapFetchResult(
            ok = false,
            statusMessage = "DIMAP-API indisponibil momentan"
        )
        for (candidate in candidates) {
            val res = fetchSingleDimapEndpoint(candidate, keyClean, nowMs)
            if (res.ok) return res
            lastError = res
        }
        return lastError
    }

    private fun fetchSingleDimapEndpoint(
        urlClean: String,
        keyClean: String,
        nowMs: Long
    ): DimapFetchResult {
        val finalUrl = if (urlClean.contains("{key}") && keyClean.isNotBlank()) {
            urlClean.replace("{key}", URLEncoder.encode(keyClean, "UTF-8"))
        } else {
            urlClean
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(finalUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                if (keyClean.isNotBlank() && !urlClean.contains("{key}")) {
                    setRequestProperty("X-API-Key", keyClean)
                    setRequestProperty("Authorization", "Bearer $keyClean")
                }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val parsedTargets = parseDimapJson(body, nowMs)
                val parsedAlerts = parseDimapAlertsForDobrogea(body)
                val shortEp = if (urlClean.contains("/polling")) "/api/polling" else "/targets"
                DimapFetchResult(
                    ok = true,
                    targets = parsedTargets,
                    borderAlarms = parsedAlerts,
                    totalParsed = parsedTargets.size,
                    statusMessage = "DIMAP-API activ ($shortEp) • ${parsedTargets.size} ținte active"
                )
            } else if (code == 401 || code == 403) {
                DimapFetchResult(
                    ok = false,
                    statusMessage = if (keyClean.isBlank()) {
                        "Endpoint-ul solicită cheie DIMAP-API (HTTP $code) — introdu cheia mai jos"
                    } else {
                        "Cheie DIMAP-API respinsă (HTTP $code) — verifică cheia de la @di_helper"
                    }
                )
            } else {
                DimapFetchResult(
                    ok = false,
                    statusMessage = "DIMAP-API indisponibil momentan (HTTP $code)"
                )
            }
        } catch (e: Exception) {
            DimapFetchResult(
                ok = false,
                statusMessage = "Eroare conectare DIMAP-API: ${e.message ?: "conexiune întreruptă"}"
            )
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Parser dedicat pentru formatul oficial DIMAP-API:
     *   { "type": "polling_update", "data": { "targets": { "targets": [ ... ] }, "alerts": { "alerts": [ ... ] } } }
     * + fallback pentru REST simplu (array / data.targets / GeoJSON FeatureCollection).
     */
    fun parseDimapJson(rawJson: String, nowMs: Long = System.currentTimeMillis()): List<DroneTarget> {
        var trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("data:")) {
            trimmed = trimmed.removePrefix("data:").trim()
        }

        val itemsArray: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                extractTargetArray(root, depth = 0) ?: return emptyList()
            }
            else -> return emptyList()
        }

        val out = ArrayList<DroneTarget>(itemsArray.length())
        for (i in 0 until itemsArray.length()) {
            val obj = itemsArray.optJSONObject(i) ?: continue
            val target = parseSingleTargetObject(obj, i, nowMs) ?: continue
            out.add(target)
        }
        return out
    }

    private fun extractTargetArray(obj: JSONObject, depth: Int): JSONArray? {
        if (depth > 4) return null
        val candidateKeys = listOf("targets", "drones", "features", "items", "objects", "markers", "threats", "list", "data")
        // Prioritate 1: formatul exact DIMAP-API: data -> targets -> targets[]
        val dataObj = obj.optJSONObject("data")
        if (dataObj != null) {
            val targetsWrapper = dataObj.optJSONObject("targets")
            val deepArr = targetsWrapper?.optJSONArray("targets")
            if (deepArr != null) return deepArr
        }
        // Prioritate 2: căutare recursivă controlată în cheile standard
        for (key in candidateKeys) {
            val arr = obj.optJSONArray(key)
            if (arr != null) return arr
        }
        for (key in candidateKeys) {
            val nested = obj.optJSONObject(key)
            if (nested != null) {
                val found = extractTargetArray(nested, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Extrage alertele de raion din același răspuns DIMAP-API (`data.alerts.alerts`),
     * filtrate STRICT pentru raioanele vecine cu Dobrogea:
     *  - Raionul Izmail (0 km de Tulcea — Reni, Izmail, Chilia, Vâlcov)
     *  - Raionul Bolgrad (10–25 km nord de Isaccea / Plauru)
     *  - Raionul Bilhorod-Dnistrovskyi (coridor litoral spre Dobrogea)
     *  - Raionul / Regiunea Odesa
     */
    fun parseDimapAlertsForDobrogea(rawJson: String): List<BorderRegionAlarm> {
        var trimmed = rawJson.trim()
        if (trimmed.startsWith("data:")) {
            trimmed = trimmed.removePrefix("data:").trim()
        }
        if (!trimmed.startsWith("{")) return emptyList()

        return try {
            val root = JSONObject(trimmed)
            val dataObj = root.optJSONObject("data") ?: root
            val alertsWrapper = dataObj.optJSONObject("alerts")
            val alertsArr = alertsWrapper?.optJSONArray("alerts")
                ?: dataObj.optJSONArray("alerts")
                ?: return emptyList()

            val out = mutableListOf<BorderRegionAlarm>()
            for (i in 0 until alertsArr.length()) {
                val o = alertsArr.optJSONObject(i) ?: continue
                val region = o.optString("region", "").trim()
                val district = o.optString("district", "").trim()
                val combined = "$region $district".lowercase(Locale.ROOT)
                val color = o.optString("color", "red").lowercase(Locale.ROOT).trim()
                val colorTag = if (color == "yellow") "GALBEN" else "ROȘU"

                val matchedLabel = when {
                    combined.contains("ізмаїл") || combined.contains("izmail") ->
                        "Raionul Izmail [$colorTag] (0 km de Tulcea — Reni/Izmail/Chilia)"
                    combined.contains("болград") || combined.contains("bolhrad") || combined.contains("bolgrad") ->
                        "Raionul Bolgrad [$colorTag] (nord Isaccea / Plauru)"
                    combined.contains("білгород-дністров") || combined.contains("bilhorod") ->
                        "Raionul Bilhorod-Dnistrovskyi [$colorTag] (coridor litoral spre Dobrogea)"
                    combined.contains("одеськ") || combined.contains("odesa") || combined.contains("odessa") ->
                        "Sector Odesa [$colorTag] (vecin cu Dobrogea / Marea Neagră)"
                    else -> null
                } ?: continue

                val id = o.optString("id", "dimap_alert_$i")
                val alertType = o.optString("alertType", "air").uppercase(Locale.ROOT)
                val typeName = o.optString("typeName", "").trim()
                val displayType = if (typeName.isNotBlank()) "$alertType • $colorTag" else alertType

                out.add(
                    BorderRegionAlarm(
                        regionId = id,
                        regionName = matchedLabel,
                        alertType = displayType,
                        active = true,
                        startedAt = o.optLong("startedAt", 0L).takeIf { it > 0L }?.toString() ?: "",
                        source = "DIMAP-API"
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseSingleTargetObject(obj: JSONObject, index: Int, nowMs: Long): DroneTarget? {
        val props = obj.optJSONObject("properties") ?: obj
        val coords = extractLatLon(obj) ?: extractLatLon(props) ?: return null
        val (lat, lon) = coords
        if (lat !in 40.0..54.0 || lon !in 20.0..42.0) return null

        val rawType = firstNonBlank(
            props.optString("type", ""),
            props.optString("category", ""),
            props.optString("kind", ""),
            props.optString("icon", ""),
            props.optString("target_type", ""),
            obj.optString("type", "")
        )
        val rawName = firstNonBlank(
            props.optString("name", ""),
            props.optString("subtype", "")
        )
        val code = if (props.has("code") && !props.isNull("code")) props.optInt("code", -1) else -1
        val normalizedType = normalizeThreatType(rawType, rawName, code)

        val id = firstNonBlank(
            obj.optString("id", ""),
            props.optString("id", ""),
            props.optString("uid", ""),
            props.optString("target_id", "")
        ).ifBlank {
            val latBucket = (lat * 100.0).roundToInt()
            val lonBucket = (lon * 100.0).roundToInt()
            "dimap_${index}_${latBucket}_${lonBucket}"
        }

        val courseDeg = optIntFlexible(props, listOf("bearing", "course", "heading", "angle", "dir", "track", "azimuth"))
            ?.let { ((it % 360) + 360) % 360 }
        val speedKmh = optIntFlexible(props, listOf("speed", "speed_kmh", "velocity", "gs"))
            ?.takeIf { it > 0 }

        val label = firstNonBlank(
            props.optString("region", ""),
            props.optString("location_name", ""),
            props.optString("city", ""),
            props.optString("description", ""),
            props.optString("text", ""),
            props.optString("title", "")
        )

        val prox = evaluateProximityToDobrogea(lat, lon)
        return DroneTarget(
            id = id,
            type = normalizedType,
            lat = lat,
            lon = lon,
            courseDeg = courseDeg,
            speedKmh = speedKmh,
            label = label,
            distToDobrogeaKm = prox.distanceKm,
            insideDobrogea = prox.insideDobrogea,
            nearestLandmark = prox.nearestLandmark,
            distToCndKm = prox.distToCndKm,
            bearingFromCnd = prox.bearingFromCnd,
            source = "DIMAP-API",
            timestampMs = nowMs,
            simulated = false
        )
    }

    private fun extractLatLon(obj: JSONObject): Pair<Double, Double>? {
        val directLat = optDoubleFlexible(obj, listOf("lat", "latitude", "y"))
        val directLon = optDoubleFlexible(obj, listOf("lng", "lon", "longitude", "x"))
        if (directLat != null && directLon != null) return directLat to directLon

        // GeoJSON geometry: { "type": "Point", "coordinates": [lon, lat] }
        val geom = obj.optJSONObject("geometry")
        val geomCoords = geom?.optJSONArray("coordinates")
        if (geomCoords != null && geomCoords.length() >= 2) {
            val lon = geomCoords.optDouble(0, Double.NaN)
            val lat = geomCoords.optDouble(1, Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) return lat to lon
        }

        // Nested position / location / coords object or array
        for (nestedKey in listOf("position", "pos", "location", "coords", "coordinates")) {
            val subObj = obj.optJSONObject(nestedKey)
            if (subObj != null) {
                val nLat = optDoubleFlexible(subObj, listOf("lat", "latitude", "y"))
                val nLon = optDoubleFlexible(subObj, listOf("lng", "lon", "longitude", "x"))
                if (nLat != null && nLon != null) return nLat to nLon
            }
            val subArr = obj.optJSONArray(nestedKey)
            if (subArr != null && subArr.length() >= 2) {
                val a = subArr.optDouble(0, Double.NaN)
                val b = subArr.optDouble(1, Double.NaN)
                if (!a.isNaN() && !b.isNaN()) {
                    // În zona Ucrainei/României: lat ∈ [42, 53], lon ∈ [20, 41]
                    return if (a > 40.0 && b < 42.0) a to b else b to a
                }
            }
        }
        return null
    }

    /**
     * Interoghează API-ul oficial api.ukrainealarm.com/api/v3/alerts și filtrează STRICT
     * alertele active din raioanele lipite de Dobrogea (Raionul Izmail / Bilhorod-Dnistrovskyi / Regiunea Odesa).
     */
    fun fetchDobrogeaBorderAlarms(apiKey: String): UkraineAlarmFetchResult {
        val keyClean = apiKey.trim()
        if (keyClean.isBlank()) {
            return UkraineAlarmFetchResult(ok = false, statusMessage = "Neconfigurat")
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(UKRAINE_ALARM_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", keyClean)
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val alarms = parseUkraineAlarmJsonForDobrogea(body)
                val msg = if (alarms.isEmpty()) {
                    "UkraineAlarm activ • Fără alerte active în Raionul Izmail / Odesa"
                } else {
                    "ALERTĂ ACTIVĂ graniță Dobrogea: ${alarms.joinToString(", ") { it.regionName }}"
                }
                UkraineAlarmFetchResult(ok = true, alarms = alarms, statusMessage = msg)
            } else if (code == 401 || code == 403) {
                UkraineAlarmFetchResult(
                    ok = false,
                    statusMessage = "Cheie UkraineAlarm respinsă (HTTP $code)"
                )
            } else {
                UkraineAlarmFetchResult(
                    ok = false,
                    statusMessage = "UkraineAlarm indisponibil (HTTP $code)"
                )
            }
        } catch (e: Exception) {
            UkraineAlarmFetchResult(
                ok = false,
                statusMessage = "Eroare UkraineAlarm: ${e.message ?: "conexiune întreruptă"}"
            )
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Filtrează răspunsul JSON de la api.ukrainealarm.com/api/v3/alerts strict pentru
     * raioanele/regiunile ucrainene lipite de Dobrogea (Izmail/Reni/Chilia/Vâlcov + Odesa sud).
     */
    fun parseUkraineAlarmJsonForDobrogea(rawJson: String): List<BorderRegionAlarm> {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val arr = JSONArray(trimmed)
        val out = mutableListOf<BorderRegionAlarm>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val regionId = obj.optString("regionId", "").trim()
            val nameUk = obj.optString("regionName", "").trim()
            val nameEn = obj.optString("regionEngName", "").trim()
            val combined = "$nameUk $nameEn".lowercase(Locale.ROOT)
            val activeAlerts = obj.optJSONArray("activeAlerts")
            if (activeAlerts == null || activeAlerts.length() == 0) continue

            val matchedLabel = when {
                combined.contains("ізмаїл") || combined.contains("izmail") ->
                    "Raionul Izmail (Reni–Izmail–Chilia–Vâlcov — 0 km de Tulcea)"
                combined.contains("болград") || combined.contains("bolhrad") || combined.contains("bolgrad") ->
                    "Raionul Bolgrad (nord Isaccea / Plauru)"
                combined.contains("білгород-дністров") || combined.contains("bilhorod") ->
                    "Raionul Bilhorod-Dnistrovskyi (coridor litoral spre Dobrogea)"
                combined.contains("одеськ") || combined.contains("odesa") || combined.contains("odessa") ->
                    "Regiunea Odesa (vecină cu Dobrogea / Marea Neagră)"
                else -> null
            } ?: continue

            val firstAlert = activeAlerts.optJSONObject(0)
            val alertType = firstAlert?.optString("type", "AIR")?.ifBlank { "AIR" } ?: "AIR"
            val startedAt = firstAlert?.optString("lastUpdate", "")
                ?.ifBlank { obj.optString("lastUpdate", "") }
                ?: obj.optString("lastUpdate", "")

            out.add(
                BorderRegionAlarm(
                    regionId = regionId.ifBlank { "ua_$i" },
                    regionName = matchedLabel,
                    alertType = alertType,
                    active = true,
                    startedAt = startedAt,
                    source = "UkraineAlarm"
                )
            )
        }
        return out
    }

    /**
     * Creează o țintă dronă simulată realistă în apropierea Dobrogei (sectorul Plauru / Izmail
     * sau larg Sulina) pentru testarea notificărilor și calculului `< 10 km`.
     */
    fun createSimulatedDobrogeaDrone(
        scenario: String = "izmail",
        nowMs: Long = System.currentTimeMillis()
    ): DroneTarget {
        val (lat, lon, course, speed, label) = when (scenario) {
            "sea" -> SimSeed(
                lat = 44.3600,
                lon = 29.0550,
                course = 265,
                speed = 185,
                label = "Marea Neagră — apropiere Cap Midia / CND"
            )
            "inside" -> SimSeed(
                lat = 45.2800,
                lon = 29.0800,
                course = 215,
                speed = 175,
                label = "Delta Dunării — sector Pardina / Tulcea"
            )
            else -> SimSeed(
                lat = 45.3650,
                lon = 28.8300,
                course = 205,
                speed = 180,
                label = "Zona Izmail — curs spre Plauru / Tulcea"
            )
        }
        val prox = evaluateProximityToDobrogea(lat, lon)
        return DroneTarget(
            id = "sim_dobrogea_${scenario}_${nowMs / 1000L}",
            type = "Dronă Shahed",
            lat = lat,
            lon = lon,
            courseDeg = course,
            speedKmh = speed,
            label = label,
            distToDobrogeaKm = prox.distanceKm,
            insideDobrogea = prox.insideDobrogea,
            nearestLandmark = prox.nearestLandmark,
            distToCndKm = prox.distToCndKm,
            bearingFromCnd = prox.bearingFromCnd,
            source = "SIMULARE",
            timestampMs = nowMs,
            simulated = true
        )
    }

    private data class SimSeed(
        val lat: Double,
        val lon: Double,
        val course: Int,
        val speed: Int,
        val label: String
    )

    fun normalizeThreatType(rawType: String, rawName: String = "", code: Int = -1): String {
        val t = rawType.lowercase(Locale.ROOT).trim()
        val n = rawName.lowercase(Locale.ROOT).trim()
        val both = "$t $n"
        return when {
            t == "reactive_shahed" || code == 13 || both.contains("герань-4") || both.contains("geran-4") ->
                "Dronă reactivă (Geran-4)"
            t == "geran3" || code == 14 || both.contains("герань-3") || both.contains("geran-3") ->
                "Dronă reactivă (Geran-3)"
            t == "geran5" || both.contains("герань-5") || both.contains("geran-5") ->
                "Dronă reactivă (Geran-5)"
            t == "shahed" || code == 10 || both.contains("shahed") || both.contains("шахед") || both.contains("geran") || both.contains("геран") ->
                "Dronă Shahed"
            t == "fpv" || code == 21 ->
                "Dronă UAV / FPV"
            t == "avia" || code == 80 || both.contains("авіац") ->
                "Aviație (OSINT)"
            t == "kab" || both.contains("kab") || both.contains("каб") || both.contains("bomb") ->
                "Bombă ghidată (KAB)"
            both.contains("ballist") || both.contains("баліст") || both.contains("iskander") || both.contains("kinzhal") || both.contains("кинджал") ->
                "Amenințare balistică"
            both.contains("missile") || both.contains("ракет") || both.contains("kalibr") || both.contains("калібр") || both.contains("kh-") || both.contains("х-") ->
                "Rachetă de croazieră"
            t == "unknown" && (both.contains("бпла") || both.contains("бпа") || code == 22) ->
                "Dronă (UAV)"
            both.contains("uav") || both.contains("drone") || both.contains("бпла") || both.contains("бпа") || both.contains("recon") || both.contains("розвід") ->
                "Dronă (UAV)"
            rawName.isNotBlank() && !rawName.equals("unknown", ignoreCase = true) ->
                rawName
            rawType.isNotBlank() && !rawType.equals("unknown", ignoreCase = true) ->
                rawType.replaceFirstChar { it.uppercaseChar() }
            else -> "Dronă / Țintă aeriană"
        }
    }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun compassFromBearing(deg: Double): String {
        val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSV", "SV", "VSV", "V", "VNV", "NV", "NNV")
        val idx = ((deg + 11.25) / 22.5).toInt() % 16
        return dirs[idx]
    }

    private fun optDoubleFlexible(obj: JSONObject, keys: List<String>): Double? {
        for (k in keys) {
            if (!obj.has(k) || obj.isNull(k)) continue
            val v = obj.optDouble(k, Double.NaN)
            if (!v.isNaN()) return v
            val s = obj.optString(k, "").trim().toDoubleOrNull()
            if (s != null) return s
        }
        return null
    }

    private fun optIntFlexible(obj: JSONObject, keys: List<String>): Int? {
        for (k in keys) {
            if (!obj.has(k) || obj.isNull(k)) continue
            val v = obj.optDouble(k, Double.NaN)
            if (!v.isNaN()) return v.roundToInt()
            val s = obj.optString(k, "").trim().toDoubleOrNull()
            if (s != null) return s.roundToInt()
        }
        return null
    }

    private fun firstNonBlank(vararg values: String): String {
        for (v in values) {
            val clean = v.trim()
            if (clean.isNotEmpty() && !clean.equals("null", ignoreCase = true)) return clean
        }
        return ""
    }

    private fun round1(value: Double): Double = (value * 10.0).roundToInt() / 10.0
}

/**
 * Deduplicator cu cooldown pentru notificările de drone în zona Dobrogei,
 * astfel încât aceeași dronă să nu genereze spam la fiecare ciclu de polling,
 * dar să escaladeze imediat dacă intră în spațiul aerian al Dobrogei sau se apropie semnificativ.
 */
class DobrogeaDroneAlertTracker(
    private val cooldownMs: Long = 5 * 60_000L
) {
    private data class AlertState(
        val notifiedAtMs: Long,
        val lastDistKm: Double,
        val wasInside: Boolean
    )

    private val droneStates = HashMap<String, AlertState>()
    private val activeBorderRegionIds = HashSet<String>()

    @Synchronized
    fun filterNewDroneAlerts(targets: List<DroneTarget>, nowMs: Long): List<DroneTarget> {
        val expired = droneStates.entries.filter { nowMs - it.value.notifiedAtMs > cooldownMs * 3 }.map { it.key }
        expired.forEach { droneStates.remove(it) }

        val fresh = mutableListOf<DroneTarget>()
        for (t in targets) {
            if (t.simulated) {
                fresh.add(t)
                continue
            }
            val prev = droneStates[t.id]
            val shouldEmit = prev == null ||
                (t.insideDobrogea && !prev.wasInside) ||
                (prev.lastDistKm - t.distToDobrogeaKm >= 3.0) ||
                (nowMs - prev.notifiedAtMs >= cooldownMs)
            if (shouldEmit) {
                droneStates[t.id] = AlertState(nowMs, t.distToDobrogeaKm, t.insideDobrogea)
                fresh.add(t)
            }
        }
        return fresh
    }

    @Synchronized
    fun filterNewBorderAlarms(activeAlarms: List<BorderRegionAlarm>): List<BorderRegionAlarm> {
        val currentIds = activeAlarms.map { it.regionId }.toSet()
        // Șterge regiunile ieșite din alertă ca să poată notifica din nou la o alarmă ulterioară
        activeBorderRegionIds.retainAll(currentIds)

        val fresh = mutableListOf<BorderRegionAlarm>()
        for (alarm in activeAlarms) {
            if (activeBorderRegionIds.add(alarm.regionId)) {
                fresh.add(alarm)
            }
        }
        return fresh
    }
}
