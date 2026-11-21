package ro.constanta.flightnotifier.core

import ro.constanta.flightnotifier.model.RomatsaAirspaceSnapshot
import ro.constanta.flightnotifier.model.RomatsaNotice
import ro.constanta.flightnotifier.model.RomatsaNoticeCategory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Client oficial pentru preluarea live a autorizațiilor, rezervărilor de spațiu aerian,
 * zborurilor de drone (UAS) și activărilor MApN publicate de ROMATSA prin NOTAM
 * pentru zona Dobrogei (LRCK Constanța, LRTC Tulcea, LRTZ Tuzla, Cap Midia, Babadag,
 * Mangalia, Delta Dunării și litoralul Mării Negre).
 *
 * Surse oficiale publice ROMATSA (fără cheie API):
 *  - https://flightplan.romatsa.ro/init/notam/getnotamlist?app=LRCK
 *  - https://flightplan.romatsa.ro/init/notam/restrictiiro
 */
object RomatsaAirspaceClient {

    const val ROMATSA_DRONES_MAP_URL = "https://flightplan.romatsa.ro/init/drones"
    const val ROMATSA_APP_LRCK_NOTAMS_URL = "https://flightplan.romatsa.ro/init/notam/getnotamlist?app=LRCK"
    const val ROMATSA_RESTRICTIONS_RO_URL = "https://flightplan.romatsa.ro/init/notam/restrictiiro"
    const val MAPN_AEROFOTO_URL = "https://aerofoto.mapn.ro/"

    private const val CONNECT_TIMEOUT_MS = 9_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val USER_AGENT = "SAT-Monitorizare-Trafic-CND/2.3 (ROMATSA-Dobrogea)"

    private val executor = Executors.newFixedThreadPool(2)

    private val notamStartRegex = Regex("""\(\s*([A-Z]\d{4}/\d{2})\s+NOTAM([NRC])\b""")
    private val qLineRegex = Regex(
        """Q\)\s*([A-Z]{4})/([A-Z]{5})/[^/\n]*/[^/\n]*/[^/\n]*/\s*(\d{3})/(\d{3})/(\d{4})([NS])(\d{5})([EW])(\d{3})"""
    )
    private val fieldARegex = Regex("""\bA\)\s*([A-Z]{4})\b""")
    private val fieldBRegex = Regex("""\bB\)\s*(\d{10})\b""")
    private val fieldCRegex = Regex("""\bC\)\s*(PERM|\d{10}(?:\s*EST)?)\b""")
    private val fieldDRegex = Regex("""\bD\)\s*(.+?)(?=\s*\bE\))""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val fieldERegex = Regex("""\bE\)\s*(.+?)(?=\s*\bF\)|\s*$)""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val fieldFRegex = Regex("""\bF\)\s*([^\n]+?)(?=\s+\bG\)|$)""", RegexOption.MULTILINE)
    private val fieldGRegex = Regex("""\bG\)\s*([^\n]+)""")
    private val dmsCoordRegex = Regex("""(\d{2})(\d{2})(\d{2})N\s*0?(\d{2})(\d{2})(\d{2})E""")

    private val dobrogeaTextKeywords = listOf(
        "CAP MIDIA", "MIDIA", "CONSTANTA", "LRCK", "KOGALNICEANU",
        "TULCEA", "LRTC", "BABADAG", "MANGALIA", "TUZLA", "LRTZ",
        "SULINA", "CHILIA", "IZMAIL", "DOBROGEA", "CERNAVODA",
        "MEDGIDIA", "COBADIN", "NAVODARI", "CORBU", "OSTROV", "ISACCEA",
        "LRD01", "LRD06", "LRD07", "LRD70", "LRD71", "LRD72", "LRD73", "LRD74", "LRD75",
        "LRTRA40", "LRTRA71"
    )

    private val adminExclusionKeywords = listOf(
        "DUE TO APPROACHING THE MAXIMUM NUMBER",
        "TRIGGER NOTAM",
        "AIRAC AIP",
        "WIND FARM UNDER CONSTRUCTION",
        "FIR MINSK",
        "UKRAINIAN CRISIS",
        "MILITARY INVASION OF UKRAINE",
        "GNSS OUTAGE",
        "OPERATING LICENCE"
    )

    private val operationalQCodes = setOf(
        "QWULW", "QWUXX", "QRTCA", "QRRCA", "QRDCA", "QWELW", "QRPCA", "QRACA", "QRMCA", "QRTXX", "QRDXX"
    )

    /**
     * Interoghează în paralel sursele oficiale ROMATSA (`app=LRCK` + `restrictiiro`),
     * parsează blocurile NOTAM, filtrează autorizațiile UAS / restricțiile / zonele MApN
     * aferente Dobrogei și deduplică rezultatele după codul NOTAM.
     */
    fun fetchDobrogeaAuthorizations(nowMs: Long = System.currentTimeMillis()): RomatsaAirspaceSnapshot {
        val sources = listOf(
            "APP LRCK" to ROMATSA_APP_LRCK_NOTAMS_URL,
            "Restricții RO" to ROMATSA_RESTRICTIONS_RO_URL
        )
        val futures = sources.map { (name, url) ->
            name to executor.submit(Callable { httpGet(url) })
        }

        val allBlocks = mutableListOf<String>()
        val succeededSources = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for ((name, fut) in futures) {
            try {
                val html = fut.get(15_000L, TimeUnit.MILLISECONDS)
                succeededSources.add(name)
                allBlocks.addAll(extractNotamBlocks(html))
            } catch (e: Exception) {
                fut.cancel(true)
                errors.add("$name: ${e.cause?.message ?: e.message ?: "eroare"}")
            }
        }

        if (succeededSources.isEmpty()) {
            return RomatsaAirspaceSnapshot(
                updatedAtMs = nowMs,
                ok = false,
                statusMessage = "ROMATSA indisponibil temporar (${errors.joinToString(" | ")})",
                notices = emptyList(),
                totalParsedNational = 0
            )
        }

        val parsed = parseDobrogeaNoticesFromBlocks(allBlocks, nowMs)
        val uasCount = parsed.count { it.category == RomatsaNoticeCategory.UAS_DRONE }
        val restrCount = parsed.count { it.category == RomatsaNoticeCategory.RESTRICTED_UAS }
        val dangerCount = parsed.count { it.category == RomatsaNoticeCategory.DANGER_AREA }
        val milCount = parsed.count { it.category == RomatsaNoticeCategory.MAPN_MILITARY }

        val summary = buildString {
            append("ROMATSA Live (${succeededSources.joinToString(" + ")}) • ")
            if (parsed.isEmpty()) {
                append("0 autorizații/rezervări active în Dobrogea")
            } else {
                append("${parsed.size} active/programate Dobrogea: ")
                val parts = mutableListOf<String>()
                if (uasCount > 0) parts.add("$uasCount drone/UAS")
                if (restrCount > 0) parts.add("$restrCount restricții UAS")
                if (dangerCount > 0) parts.add("$dangerCount poligoane MApN")
                if (milCount > 0) parts.add("$milCount zboruri MIL")
                append(parts.joinToString(" • "))
            }
        }

        return RomatsaAirspaceSnapshot(
            updatedAtMs = nowMs,
            ok = true,
            statusMessage = summary,
            notices = parsed,
            totalParsedNational = allBlocks.size
        )
    }

    /**
     * Parsează direct un document HTML sau text ROMATSA (util și în teste unitare).
     */
    fun parseDobrogeaNotices(rawHtmlOrText: String, nowMs: Long = System.currentTimeMillis()): List<RomatsaNotice> {
        val blocks = extractNotamBlocks(rawHtmlOrText)
        return parseDobrogeaNoticesFromBlocks(blocks, nowMs)
    }

    internal fun extractNotamBlocks(rawHtmlOrText: String): List<String> {
        if (rawHtmlOrText.isBlank()) return emptyList()
        val text = rawHtmlOrText
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</(?:tr|p|div|li|td|th)>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val starts = notamStartRegex.findAll(text).toList()
        val blocks = mutableListOf<String>()
        for (idx in starts.indices) {
            val startIdx = starts[idx].range.first
            val nextStartIdx = if (idx + 1 < starts.size) starts[idx + 1].range.first else text.length
            val maxEnd = minOf(nextStartIdx, startIdx + 4000)

            var depth = 0
            var endIdx = -1
            var i = startIdx
            while (i < maxEnd) {
                when (text[i]) {
                    '(' -> depth += 1
                    ')' -> {
                        val isFieldMarker = i > startIdx &&
                            text[i - 1] in "QABCDEFG" &&
                            (i - 2 < startIdx || text[i - 2].isWhitespace()) &&
                            i + 1 < maxEnd &&
                            (
                                text[i + 1] == ' ' || text[i + 1] == '\t' ||
                                    (text[i + 1] == '\n' && text[i - 1] in "DE" && text.indexOf(')', i + 1) in (i + 1) until maxEnd)
                                )
                        val isNumberedItem = depth == 1 &&
                            i > startIdx &&
                            text[i - 1].isDigit() &&
                            (i - 2 < startIdx || text[i - 2].isWhitespace()) &&
                            i + 1 < maxEnd &&
                            (text[i + 1] == ' ' || text[i + 1] == '\t')

                        if (!isFieldMarker && !isNumberedItem) {
                            depth -= 1
                            if (depth == 0) {
                                endIdx = i + 1
                                break
                            }
                        }
                    }
                }
                i += 1
            }
            if (endIdx > startIdx) {
                blocks.add(text.substring(startIdx, endIdx).trim())
            } else {
                val fallbackEnd = text.lastIndexOf(')', maxEnd - 1)
                if (fallbackEnd > startIdx) {
                    blocks.add(text.substring(startIdx, fallbackEnd + 1).trim())
                }
            }
        }
        return blocks
    }

    private fun parseDobrogeaNoticesFromBlocks(blocks: List<String>, nowMs: Long): List<RomatsaNotice> {
        val byId = LinkedHashMap<String, RomatsaNotice>()
        for (block in blocks) {
            val notice = parseSingleNotamBlock(block, nowMs) ?: continue
            byId.putIfAbsent(notice.notamId, notice)
        }
        return byId.values.sortedWith(
            compareBy<RomatsaNotice>(
                { categoryOrder(it.category) },
                { if (it.activeNow) 0 else 1 },
                { it.validFromMs }
            )
        )
    }

    private fun categoryOrder(cat: RomatsaNoticeCategory): Int = when (cat) {
        RomatsaNoticeCategory.UAS_DRONE -> 0
        RomatsaNoticeCategory.RESTRICTED_UAS -> 1
        RomatsaNoticeCategory.DANGER_AREA -> 2
        RomatsaNoticeCategory.MAPN_MILITARY -> 3
    }

    private fun parseSingleNotamBlock(block: String, nowMs: Long): RomatsaNotice? {
        val head = notamStartRegex.find(block) ?: return null
        val notamId = head.groupValues[1].uppercase(Locale.US)
        val kind = head.groupValues[2].uppercase(Locale.US)
        if (kind == "C") return null // NOTAMC = anulare

        val cleanInner = block.removePrefix("(").removeSuffix(")").trim()
        val eRaw = fieldERegex.find(cleanInner)?.groupValues?.get(1)
            ?.replace(Regex("""[ \t]+"""), " ")
            ?.replace(Regex("""\n\s*"""), "\n")
            ?.trim()
            ?: return null
        val eUpper = eRaw.uppercase(Locale.US)

        if (adminExclusionKeywords.any { eUpper.contains(it) }) return null

        val qMatch = qLineRegex.find(cleanInner)
        val qCode = qMatch?.groupValues?.get(2)?.uppercase(Locale.US).orEmpty()
        val location = fieldARegex.find(cleanInner)?.groupValues?.get(1)?.uppercase(Locale.US) ?: "LRBB"

        val isOperational = qCode in operationalQCodes ||
            eUpper.contains("UNMANNED") ||
            eUpper.contains("UAS") ||
            eUpper.contains("UAV") ||
            eUpper.contains("DRONE") ||
            eUpper.contains("RPAS") ||
            eUpper.contains("MIL FLT") ||
            eUpper.contains("MIL EXER") ||
            eUpper.contains("DANGER AREA") ||
            eUpper.contains("TEMPORARY RESTRICTED") ||
            eUpper.contains("TEMPORARY RESERVED") ||
            eUpper.contains("LRTRA") ||
            eUpper.contains("LRD") ||
            eUpper.contains("FRNG")
        if (!isOperational) return null

        // Excludem NOTAM-urile expirate (> 1 min în trecut)
        val bRaw = fieldBRegex.find(cleanInner)?.groupValues?.get(1).orEmpty()
        val cRaw = fieldCRegex.find(cleanInner)?.groupValues?.get(1).orEmpty()
        val validFromMs = parseNotamUtcTime(bRaw) ?: 0L
        val validToMs = if (cRaw.startsWith("PERM")) Long.MAX_VALUE else (parseNotamUtcTime(cRaw.take(10)) ?: Long.MAX_VALUE)
        if (validToMs < nowMs - 60_000L) return null

        // Extrage centrul + raza din linia Q) și coordonatele exacte din corpul E)
        val qLat = qMatch?.let { parseQDegMin(it.groupValues[5], it.groupValues[6]) }
        val qLon = qMatch?.let { parseQDegMin(it.groupValues[7], it.groupValues[8]) }
        val qRadiusNm = qMatch?.groupValues?.get(9)?.toIntOrNull()

        val eCoords = extractDmsPoints(eUpper)
        val (inDobrogea, landmark) = evaluateDobrogeaRelevance(
            location = location,
            eUpper = eUpper,
            qLat = qLat,
            qLon = qLon,
            qRadiusNm = qRadiusNm,
            eCoords = eCoords
        )
        if (!inDobrogea) return null

        val category = classifyNoticeCategory(qCode, eUpper)
        val authority = when (category) {
            RomatsaNoticeCategory.UAS_DRONE ->
                if (eUpper.contains("LRD") || eUpper.contains("FRNG") || eUpper.contains("MIL")) {
                    "MApN / ROMATSA (UAS)"
                } else {
                    "ROMATSA (Autorizare UAS / NOTAM)"
                }
            RomatsaNoticeCategory.RESTRICTED_UAS ->
                if (eUpper.contains("UKRAINIAN BORDER") || eUpper.contains("MIL")) {
                    "ROMATSA / MApN (Restricție UAS)"
                } else {
                    "ROMATSA (Restricție temporară)"
                }
            RomatsaNoticeCategory.DANGER_AREA -> "MApN / ROMATSA (Poligon / Pericol)"
            RomatsaNoticeCategory.MAPN_MILITARY -> "MApN / ROMATSA (Zbor militar)"
        }

        val lowerLim = fieldFRegex.find(cleanInner)?.groupValues?.get(1)?.trim().orEmpty()
        val upperLim = fieldGRegex.find(cleanInner)?.groupValues?.get(1)?.trim().orEmpty()
        val verticalLimits = when {
            lowerLim.isNotEmpty() && upperLim.isNotEmpty() -> "$lowerLim → $upperLim"
            upperLim.isNotEmpty() -> "până la $upperLim"
            lowerLim.isNotEmpty() -> "de la $lowerLim"
            else -> ""
        }
        val schedule = fieldDRegex.find(cleanInner)?.groupValues?.get(1)
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()

        val activeNow = (validFromMs == 0L || nowMs >= validFromMs) && nowMs <= validToMs
        val validFromLabel = formatNotamTimeLabel(bRaw)
        val validToLabel = if (cRaw.startsWith("PERM")) "PERM" else formatNotamTimeLabel(cRaw.take(10))
        val title = buildHumanTitle(category, eUpper, landmark)
        val compactSummary = buildCompactSummary(eRaw)

        val repPoint = eCoords.firstOrNull() ?: if (qLat != null && qLon != null) qLat to qLon else null

        return RomatsaNotice(
            notamId = notamId,
            location = location,
            qCode = qCode,
            category = category,
            authority = authority,
            title = title,
            validFromUtc = validFromLabel,
            validToUtc = validToLabel,
            validFromMs = validFromMs,
            validToMs = validToMs,
            schedule = schedule,
            verticalLimits = verticalLimits,
            landmark = landmark,
            centerLat = repPoint?.first,
            centerLon = repPoint?.second,
            radiusNm = qRadiusNm,
            activeNow = activeNow,
            summaryText = compactSummary,
            rawNotam = block
        )
    }

    private fun classifyNoticeCategory(qCode: String, eUpper: String): RomatsaNoticeCategory {
        val mentionsUas = qCode == "QWULW" || qCode == "QWUXX" ||
            eUpper.contains("UNMANNED ACFT") ||
            eUpper.contains("UNMANNED AIRCRAFT") ||
            eUpper.contains("UAS FLT") ||
            eUpper.contains("DRONE")

        val isRestrictedForUas = eUpper.contains("TEMPORARY RESTRICTED AREA FOR") &&
            (eUpper.contains("UNMANNED") || eUpper.contains("VFR"))

        return when {
            isRestrictedForUas -> RomatsaNoticeCategory.RESTRICTED_UAS
            mentionsUas -> RomatsaNoticeCategory.UAS_DRONE
            qCode == "QRTCA" && eUpper.contains("TEMPORARY RESTRICTED") -> RomatsaNoticeCategory.RESTRICTED_UAS
            qCode == "QRDCA" || eUpper.contains("DANGER AREA") || eUpper.contains("FRNG") || eUpper.contains("EXPLOSIVES") ->
                RomatsaNoticeCategory.DANGER_AREA
            else -> RomatsaNoticeCategory.MAPN_MILITARY
        }
    }

    private fun evaluateDobrogeaRelevance(
        location: String,
        eUpper: String,
        qLat: Double?,
        qLon: Double?,
        qRadiusNm: Int?,
        eCoords: List<Pair<Double, Double>>
    ): Pair<Boolean, String> {
        if (location in setOf("LRCK", "LRTC", "LRTZ")) {
            val lm = when (location) {
                "LRCK" -> "Aeroport CND / Mihail Kogălniceanu"
                "LRTC" -> "Tulcea (LRTC)"
                else -> "Aerodrom Tuzla (LRTZ)"
            }
            return true to lm
        }

        // 1. Verificare geometrică exactă pe coordonatele poligonului/cercului din textul E)
        if (eCoords.isNotEmpty()) {
            var bestLandmark = ""
            var bestDist = Double.MAX_VALUE
            var hitsDobrogea = false
            for ((lat, lon) in eCoords) {
                val prox = DobrogeaDroneMonitor.evaluateProximityToDobrogea(lat, lon)
                if (prox.insideDobrogea || prox.distanceKm <= 15.0) {
                    hitsDobrogea = true
                    if (prox.distanceKm < bestDist) {
                        bestDist = prox.distanceKm
                        bestLandmark = prox.nearestLandmark
                    }
                }
            }
            if (hitsDobrogea) {
                val named = extractNamedDobrogeaZone(eUpper, includeUkrainianBorder = true)
                return true to (named ?: bestLandmark.ifBlank { "Dobrogea" })
            }
        }

        // 2. Verificare după cuvinte-cheie geografice specifice Dobrogei
        val namedZone = extractNamedDobrogeaZone(eUpper, includeUkrainianBorder = false)
        if (namedZone != null) {
            return true to namedZone
        }
        if (dobrogeaTextKeywords.any { eUpper.contains(it) }) {
            return true to "Dobrogea"
        }

        // 3. Verificare geometrică pe centrul + raza din linia Q) (doar dacă nu e un NOTAM național > 65 NM)
        if (qLat != null && qLon != null && (qRadiusNm == null || qRadiusNm <= 65)) {
            val prox = DobrogeaDroneMonitor.evaluateProximityToDobrogea(qLat, qLon)
            val radiusKm = (qRadiusNm ?: 5) * 1.852
            if (prox.insideDobrogea || prox.distanceKm <= radiusKm + 5.0) {
                return true to prox.nearestLandmark
            }
        }

        return false to ""
    }

    private fun extractNamedDobrogeaZone(eUpper: String, includeUkrainianBorder: Boolean): String? {
        return when {
            eUpper.contains("CAP MIDIA") || eUpper.contains("LRD06") || eUpper.contains("LRD07") || eUpper.contains("MIDIA") ->
                "Cap Midia / Năvodari (Constanța)"
            eUpper.contains("BABADAG") || eUpper.contains("LRD01") -> "Poligon Babadag (Tulcea)"
            eUpper.contains("MANGALIA") || eUpper.contains("LRD70") || eUpper.contains("LRD71") -> "Mangalia (Marea Neagră)"
            eUpper.contains("TUZLA") || eUpper.contains("LRD72") || eUpper.contains("LRTZ") -> "Tuzla / Costinești"
            eUpper.contains("CONSTANTA") || eUpper.contains("LRD73") -> "Constanța / Litoral"
            eUpper.contains("LRTRA40") -> "LRTRA40 — Delta Dunării / Tulcea"
            eUpper.contains("LRTRA71") -> "LRTRA71 — Cobadin / Dobrogea Sud"
            includeUkrainianBorder && eUpper.contains("UKRAINIAN BORDER") -> "Frontieră Dunăre / Delta Dunării"
            eUpper.contains("LRCK") || eUpper.contains("KOGALNICEANU") -> "Mihail Kogălniceanu (LRCK)"
            eUpper.contains("LRTC") || eUpper.contains("TULCEA") -> "Tulcea (LRTC)"
            else -> null
        }
    }

    private fun buildHumanTitle(category: RomatsaNoticeCategory, eUpper: String, landmark: String): String {
        val locPart = if (landmark.isNotBlank()) " — $landmark" else ""
        return when (category) {
            RomatsaNoticeCategory.UAS_DRONE -> when {
                eUpper.contains("FRNG") || eUpper.contains("LRD") -> "Zbor UAS + Trageri poligon$locPart"
                else -> "Zbor autorizat dronă / UAS$locPart"
            }
            RomatsaNoticeCategory.RESTRICTED_UAS -> when {
                eUpper.contains("UKRAINIAN BORDER") -> "Zonă restricționată IFR/VFR/Drone — Frontieră Ucraina"
                eUpper.contains("MIL EXER") -> "Zonă restricționată temporar (Exercițiu MApN)$locPart"
                else -> "Zonă restricționată temporar UAS/VFR$locPart"
            }
            RomatsaNoticeCategory.DANGER_AREA -> when {
                eUpper.contains("DEMOLITION") || eUpper.contains("EXPLOSIVES") -> "Poligon activ — Distrugeri explozivi$locPart"
                eUpper.contains("FRNG") -> "Poligon activ — Trageri MApN$locPart"
                else -> "Zonă periculoasă activată (MApN)$locPart"
            }
            RomatsaNoticeCategory.MAPN_MILITARY -> when {
                eUpper.contains("LRTRA") -> {
                    val traName = Regex("""LRTRA\d+[A-Z]?""").find(eUpper)?.value ?: "LRTRA"
                    "Rezervare militară $traName$locPart"
                }
                eUpper.contains("MIL FLT") -> "Coridor / zboruri militare MApN$locPart"
                else -> "Rezervare temporară spațiu aerian$locPart"
            }
        }
    }

    private fun buildCompactSummary(eRaw: String): String {
        val lines = eRaw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val nonCoordLines = lines.filterNot { line ->
            val coordHits = dmsCoordRegex.findAll(line).count()
            coordHits >= 2
        }
        val joined = (if (nonCoordLines.isNotEmpty()) nonCoordLines else lines)
            .joinToString(" • ")
            .replace(Regex("""\s+"""), " ")
        return if (joined.length > 190) joined.take(187) + "…" else joined
    }

    private fun extractDmsPoints(text: String): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        for (m in dmsCoordRegex.findAll(text)) {
            val lat = m.groupValues[1].toDouble() +
                m.groupValues[2].toDouble() / 60.0 +
                m.groupValues[3].toDouble() / 3600.0
            val lon = m.groupValues[4].toDouble() +
                m.groupValues[5].toDouble() / 60.0 +
                m.groupValues[6].toDouble() / 3600.0
            if (lat in 42.0..49.0 && lon in 20.0..32.0) {
                out.add(lat to lon)
            }
        }
        return out
    }

    private fun parseQDegMin(digits: String, hemi: String): Double? {
        return try {
            val degLen = digits.length - 2
            val deg = digits.substring(0, degLen).toDouble()
            val min = digits.substring(degLen).toDouble()
            val v = deg + min / 60.0
            if (hemi == "S" || hemi == "W") -v else v
        } catch (_: Exception) {
            null
        }
    }

    private fun parseNotamUtcTime(yymmddhhmm: String): Long? {
        if (yymmddhhmm.length != 10 || !yymmddhhmm.all { it.isDigit() }) return null
        return try {
            val fmt = SimpleDateFormat("yyMMddHHmm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            fmt.parse(yymmddhhmm)?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun formatNotamTimeLabel(yymmddhhmm: String): String {
        val ts = parseNotamUtcTime(yymmddhhmm) ?: return yymmddhhmm.ifBlank { "—" }
        val fmt = SimpleDateFormat("dd.MM HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date(ts))
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } finally {
            conn.disconnect()
        }
    }
}
