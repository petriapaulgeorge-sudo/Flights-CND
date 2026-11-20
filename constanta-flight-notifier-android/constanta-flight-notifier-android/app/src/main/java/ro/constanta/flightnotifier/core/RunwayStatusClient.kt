package ro.constanta.flightnotifier.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class RunwayWindComponent(
    val runway: String,
    val headwindKt: Int,   // pozitiv = vânt frontal, negativ = vânt din spate
    val crosswindKt: Int
)

data class RunwayOperability(
    val level: Int,               // 1 (Verde - operațională) -> 5 (Roșu - neoperațională)
    val shortTitle: String,       // ex: "NIVEL 1/5 • OPERAȚIONALĂ"
    val suggestion: String,       // sugestie aterizare / decolare
    val colorHex: String,         // culoare buton
    val factors: List<String>     // motivele care au stabilit nivelul
)

data class RunwayConditions(
    val ok: Boolean,
    val source: String,
    val rawMetar: String,
    val rawTaf: String? = null,
    val observationTime: String = "—",
    val windSummary: String = "—",
    val rwy18Wind: RunwayWindComponent? = null,
    val rwy36Wind: RunwayWindComponent? = null,
    val recommendedRunway: String = "18 / 36",
    val visibilityText: String = "—",
    val ceilingText: String = "—",
    val tempDewText: String = "—",
    val qnhText: String = "—",
    val weatherPhenomena: String = "Fără fenomene semnificative",
    val surfaceEstimate: String = "Uscată (condiții normale)",
    val icingRisk: Boolean = false,
    val rvrAndStateGroups: String = "Nu sunt raportate coduri speciale",
    val flightCategory: String = "VFR",
    val windSpeedKt: Int? = null,
    val windGustKt: Int? = null,
    val crosswindKt: Int? = null,
    val visibilityMeters: Int? = null,
    val lowestCeilingFt: Int? = null,
    val hasThunderstorm: Boolean = false,
    val hasFreezingPrecip: Boolean = false,
    val hasRain: Boolean = false,
    val hasSnoclo: Boolean = false,
    val fetchedAtMs: Long = System.currentTimeMillis(),
    val error: String? = null
)

object RunwayStatusClient {

    private const val USER_AGENT = "SAT-Monitorizare-Trafic-CND/1.6 (LRCK)"
    private const val TIMEOUT_MS = 8000

    fun fetchLiveRunwayConditions(): RunwayConditions {
        val metarUrls = listOf(
            "NOAA AviationWeather" to "https://aviationweather.gov/api/data/metar?ids=LRCK",
            "NOAA TGFTP" to "https://tgftp.nws.noaa.gov/data/observations/metar/stations/LRCK.TXT"
        )

        var rawMetar: String? = null
        var usedSource = "METAR LRCK"
        val errors = mutableListOf<String>()

        for ((sourceName, url) in metarUrls) {
            try {
                val body = httpGet(url)
                val line = body.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("LRCK ") || it.startsWith("METAR LRCK ") || it.startsWith("SPECI LRCK ") }
                if (!line.isNullOrBlank()) {
                    rawMetar = line.removePrefix("METAR ").removePrefix("SPECI ").trim()
                    usedSource = sourceName
                    break
                } else {
                    errors.add("$sourceName: răspuns gol")
                }
            } catch (e: Exception) {
                errors.add("$sourceName: ${e.message ?: "eroare"}")
            }
        }

        if (rawMetar == null) {
            return RunwayConditions(
                ok = false,
                source = "Indisponibil",
                rawMetar = "Nu s-a putut prelua METAR live pentru LRCK.",
                error = errors.joinToString(" | ")
            )
        }

        val rawTaf = runCatching {
            httpGet("https://aviationweather.gov/api/data/taf?ids=LRCK")
                .trim()
                .takeIf { it.contains("LRCK") }
        }.getOrNull()

        return parseMetar(rawMetar, usedSource, rawTaf)
    }

    /**
     * Decodifică un METAR ICAO standard pentru LRCK (Pista 18/36) în parametri operaționali de pistă.
     */
    fun parseMetar(
        rawMetar: String,
        source: String = "METAR LRCK",
        rawTaf: String? = null
    ): RunwayConditions {
        val clean = rawMetar.trim().replace(Regex("\\s+"), " ")
        val tokens = clean.split(" ")

        // 1. Ora observației (ex: 202000Z)
        val timeTok = tokens.firstOrNull { Regex("^\\d{6}Z$").matches(it) }
        val obsTime = if (timeTok != null) {
            "Ziua ${timeTok.substring(0, 2)}, ${timeTok.substring(2, 4)}:${timeTok.substring(4, 6)} UTC"
        } else {
            "—"
        }

        // 2. Vânt (ex: 02011KT, 18015G25KT, VRB03KT, 00000KT)
        val windRegex = Regex("^(VRB|\\d{3})(\\d{2,3})(?:G(\\d{2,3}))?(KT|MPS)$")
        val windMatch = tokens.firstNotNullOfOrNull { windRegex.matchEntire(it) }
        var windSummary = "Calm / indisponibil"
        var rwy18Wind: RunwayWindComponent? = null
        var rwy36Wind: RunwayWindComponent? = null
        var recommendedRunway = "18 / 36 (după traficul ATC)"
        var parsedWindSpdKt: Int? = null
        var parsedWindGustKt: Int? = null
        var parsedCrosswindKt: Int? = null

        if (windMatch != null) {
            val dirStr = windMatch.groupValues[1]
            val rawSpd = windMatch.groupValues[2].toIntOrNull() ?: 0
            val rawGust = windMatch.groupValues[3].toIntOrNull()
            val unit = windMatch.groupValues[4]
            val factor = if (unit == "MPS") 1.94384 else 1.0
            val spdKt = (rawSpd * factor).roundToInt()
            val gustKt = rawGust?.let { (it * factor).roundToInt() }
            parsedWindSpdKt = spdKt
            parsedWindGustKt = gustKt

            windSummary = when {
                spdKt == 0 -> "Calm (0 kt)"
                dirStr == "VRB" -> "Variabil, $spdKt kt" + (gustKt?.let { " (rafale $it kt)" } ?: "")
                else -> "${dirStr}°, $spdKt kt" + (gustKt?.let { " (rafale $it kt)" } ?: "")
            }

            val dirDeg = dirStr.toIntOrNull()
            if (dirDeg != null && spdKt > 0) {
                val c18 = computeWindComponent("18", 180, dirDeg, spdKt)
                val c36 = computeWindComponent("36", 360, dirDeg, spdKt)
                rwy18Wind = c18
                rwy36Wind = c36
                parsedCrosswindKt = minOf(c18.crosswindKt, c36.crosswindKt)
                recommendedRunway = when {
                    c36.headwindKt > c18.headwindKt -> "Pista 36 (vânt frontal ${c36.headwindKt} kt, lateral ${c36.crosswindKt} kt)"
                    c18.headwindKt > c36.headwindKt -> "Pista 18 (vânt frontal ${c18.headwindKt} kt, lateral ${c18.crosswindKt} kt)"
                    else -> "Pista 18 sau 36 (vânt lateral ${c18.crosswindKt} kt)"
                }
            } else if (spdKt == 0) {
                parsedCrosswindKt = 0
                recommendedRunway = "Pista 36 / 18 (vânt calm)"
            }
        }

        // 3. Vizibilitate (CAVOK sau 4 cifre în metri, ex: 9999, 0800)
        val isCavok = tokens.contains("CAVOK")
        var visMeters: Int? = if (isCavok) 10_000 else null
        if (!isCavok) {
            val visTok = tokens.firstOrNull { Regex("^\\d{4}$").matches(it) }
            if (visTok != null) {
                visMeters = visTok.toIntOrNull()
            }
        }
        val visibilityText = when {
            isCavok -> "CAVOK (≥ 10 km, fără nori joși)"
            visMeters == null -> "—"
            visMeters >= 9999 -> "≥ 10 km"
            visMeters >= 1000 -> String.format(java.util.Locale.US, "%.1f km (%d m)", visMeters / 1000.0, visMeters)
            else -> "$visMeters m (redusă!)"
        }

        // 4. Nori și plafon (ceiling = cel mai jos strat BKN / OVC / VV)
        val cloudRegex = Regex("^(FEW|SCT|BKN|OVC|VV)(\\d{3}|///)(CB|TCU)?$")
        val cloudTokens = tokens.mapNotNull { cloudRegex.matchEntire(it) }
        var lowestCeilingFt: Int? = null
        val cloudDesc = mutableListOf<String>()
        for (cm in cloudTokens) {
            val cover = cm.groupValues[1]
            val hHundreds = cm.groupValues[2].toIntOrNull()
            val extra = cm.groupValues[3]
            val ft = hHundreds?.times(100)
            if (ft != null && (cover == "BKN" || cover == "OVC" || cover == "VV")) {
                if (lowestCeilingFt == null || ft < lowestCeilingFt) lowestCeilingFt = ft
            }
            cloudDesc.add("$cover ${ft?.let { "${it} ft" } ?: "—"}${if (extra.isNotEmpty()) " ($extra)" else ""}")
        }
        val ceilingText = when {
            isCavok -> "Fără plafon sub 5.000 ft (CAVOK)"
            tokens.contains("NSC") || tokens.contains("NCD") || tokens.contains("SKC") -> "Cer senin / fără nori semnificativi"
            lowestCeilingFt != null -> "Plafon $lowestCeilingFt ft AGL (${cloudDesc.joinToString(", ")})"
            cloudDesc.isNotEmpty() -> cloudDesc.joinToString(", ")
            else -> "—"
        }

        // 5. Temperatură / Punct de rouă (ex: 08/05 sau M02/M04)
        val tempRegex = Regex("^(M?\\d{2})/(M?\\d{2})$")
        val tempMatch = tokens.firstNotNullOfOrNull { tempRegex.matchEntire(it) }
        var tempC: Int? = null
        var dewC: Int? = null
        if (tempMatch != null) {
            tempC = parseMetarTemp(tempMatch.groupValues[1])
            dewC = parseMetarTemp(tempMatch.groupValues[2])
        }
        val tempDewText = if (tempC != null && dewC != null) {
            "$tempC°C (punct de rouă $dewC°C)"
        } else {
            "—"
        }

        // 6. QNH (ex: Q1021)
        val qnhTok = tokens.firstOrNull { Regex("^Q\\d{4}$").matches(it) }
        val qnhText = qnhTok?.let { "${it.substring(1)} hPa" } ?: "—"

        // 7. Grupuri RVR & Stare pistă / MOTNE / SNOWTAM (ex: R18/0600U, R36/290195, R18/CLRD//, R36/SNOCLO)
        val rvrGroups = tokens.filter { Regex("^R(18|36|88|99)/.*$").matches(it) }
        val rvrAndState = if (rvrGroups.isNotEmpty()) {
            rvrGroups.joinToString(" • ") { decodeRunwayGroup(it) }
        } else {
            "Nu sunt raportate coduri RVR / contaminare"
        }

        // 8. Fenomene meteo & estimare stare suprafață pistă
        val wxMatches = tokens.filter {
            Regex("^(\\+|-|VC)?(MI|PR|BC|DR|BL|SH|TS|FZ)?(DZ|RA|SN|SG|IC|PL|GR|GS|BR|FG|FU|VA|DU|SA|HZ|SQ|FC)+$").matches(it)
        }
        val hasFreezing = wxMatches.any { it.contains("FZ") || it.contains("SN") || it.contains("PL") || it.contains("GR") || it.contains("SG") }
        val hasRain = wxMatches.any { it.contains("RA") || it.contains("DZ") }
        val hasFog = wxMatches.any { it.contains("FG") || it.contains("BR") }
        val hasStorm = wxMatches.any { it.contains("TS") || it.contains("SQ") }
        val hasSnoclo = clean.contains("SNOCLO")

        val icingRisk = hasFreezing || (tempC != null && tempC <= 2 && (hasRain || hasFog || (dewC != null && tempC - dewC <= 2)))

        val surfaceEstimate = when {
            hasSnoclo -> "ATENȚIE: Pistă închisă din cauza zăpezii (SNOCLO)!"
            hasFreezing -> "Contaminată — zăpadă / lapoviță / polei! (atenție frânare)"
            hasRain && tempC != null && tempC <= 1 -> "Umedă cu risc ridicat de polei ($tempC°C)!"
            hasRain -> "Umedă — precipitații active (ploaie / burniță)"
            icingRisk -> "Posibil umedă / risc chiciură sau polei ($tempC°C)"
            hasFog -> "Condiții de ceață / vizibilitate redusă (LVP posibil)"
            else -> "Uscată — condiții normale de operare"
        }

        val weatherText = if (wxMatches.isNotEmpty()) {
            wxMatches.joinToString(", ") { translateWx(it) }
        } else if (hasStorm) {
            "Furtună în zonă"
        } else {
            "Fără fenomene meteo semnificative"
        }

        // 9. Categorie de zbor ICAO / FAA (VFR / MVFR / IFR / LIFR)
        val flightCategory = when {
            (visMeters != null && visMeters < 1600) || (lowestCeilingFt != null && lowestCeilingFt < 500) -> "LIFR"
            (visMeters != null && visMeters < 5000) || (lowestCeilingFt != null && lowestCeilingFt < 1000) -> "IFR"
            (visMeters != null && visMeters < 8000) || (lowestCeilingFt != null && lowestCeilingFt < 3000) -> "MVFR"
            else -> "VFR"
        }

        return RunwayConditions(
            ok = true,
            source = source,
            rawMetar = clean,
            rawTaf = rawTaf,
            observationTime = obsTime,
            windSummary = windSummary,
            rwy18Wind = rwy18Wind,
            rwy36Wind = rwy36Wind,
            recommendedRunway = recommendedRunway,
            visibilityText = visibilityText,
            ceilingText = ceilingText,
            tempDewText = tempDewText,
            qnhText = qnhText,
            weatherPhenomena = weatherText,
            surfaceEstimate = surfaceEstimate,
            icingRisk = icingRisk,
            rvrAndStateGroups = rvrAndState,
            flightCategory = flightCategory,
            windSpeedKt = parsedWindSpdKt,
            windGustKt = parsedWindGustKt,
            crosswindKt = parsedCrosswindKt,
            visibilityMeters = visMeters,
            lowestCeilingFt = lowestCeilingFt,
            hasThunderstorm = hasStorm,
            hasFreezingPrecip = hasFreezing,
            hasRain = hasRain,
            hasSnoclo = hasSnoclo
        )
    }

    /**
     * Evaluează operabilitatea pistei pentru aterizare / decolare pe o scară de la
     * 1 (Verde — operațională, condiții optime) până la 5 (Roșu — neoperațională / critică),
     * combinând datele METAR live cu evaluarea locală a pistei (stare operațională + cod RWYCC).
     */
    fun evaluateOperability(
        conditions: RunwayConditions?,
        localOperationalState: String = "Operațională",
        localRwyccCode: String = "6 — Uscat"
    ): RunwayOperability {
        var level = 1
        val factors = mutableListOf<String>()

        fun raiseTo(target: Int, reason: String) {
            if (target > level) level = target
            factors.add(reason)
        }

        // A. Stare operațională locală setată de operator
        when {
            localOperationalState.contains("Închis", ignoreCase = true) ->
                raiseTo(5, "Stare pistă locală: ÎNCHISĂ")
            localOperationalState.contains("Restricț", ignoreCase = true) ->
                raiseTo(3, "Stare pistă locală: Restricționată")
            localOperationalState.contains("Atenție", ignoreCase = true) ->
                raiseTo(2, "Stare pistă locală: Atenție meteo")
        }

        // B. Cod RWYCC (GRF 6 -> 0)
        val rwyccDigit = localRwyccCode.trim().firstOrNull()?.digitToIntOrNull() ?: 6
        when (rwyccDigit) {
            0 -> raiseTo(5, "RWYCC 0 — Gheață / frânare critică (sub minim)")
            1 -> raiseTo(4, "RWYCC 1 — Frânare slabă")
            2 -> raiseTo(4, "RWYCC 2 — Frânare mediu-slabă")
            3 -> raiseTo(3, "RWYCC 3 — Frânare medie (pistă contaminată/alunecoasă)")
            4 -> raiseTo(3, "RWYCC 4 — Frânare mediu-bună")
            5 -> raiseTo(2, "RWYCC 5 — Suprafață umedă (frânare bună)")
        }

        // C. Condiții meteo live METAR LRCK
        if (conditions != null && conditions.ok) {
            if (conditions.hasSnoclo) {
                raiseTo(5, "METAR raportează SNOCLO (pistă închisă din cauza zăpezii)")
            }

            val vis = conditions.visibilityMeters
            val ceil = conditions.lowestCeilingFt
            when {
                (vis != null && vis < 350) || (ceil != null && ceil < 150) ->
                    raiseTo(5, "Vizibilitate/plafon sub minimele curente (viz. ${conditions.visibilityText}, plafon ${conditions.ceilingText})")
                conditions.flightCategory == "LIFR" ->
                    raiseTo(4, "Categorie LIFR — vizibilitate redusă / plafon foarte jos (proceduri LVP)")
                conditions.flightCategory == "IFR" ->
                    raiseTo(3, "Categorie IFR — operare instrumentală obligatorie")
                conditions.flightCategory == "MVFR" ->
                    raiseTo(2, "Categorie MVFR — vizibilitate sau plafon moderat")
            }

            val xw = conditions.crosswindKt ?: 0
            val gust = conditions.windGustKt ?: conditions.windSpeedKt ?: 0
            when {
                xw >= 34 || gust >= 45 ->
                    raiseTo(5, "Vânt extrem: lateral $xw kt / rafale $gust kt (peste limitele uzuale)")
                xw >= 27 || gust >= 35 ->
                    raiseTo(4, "Vânt sever: lateral $xw kt / rafale $gust kt")
                xw >= 20 || gust >= 28 ->
                    raiseTo(3, "Vânt puternic: lateral $xw kt / rafale $gust kt")
                xw >= 14 || gust >= 20 ->
                    raiseTo(2, "Vânt moderat: lateral $xw kt / rafale $gust kt")
            }

            if (conditions.hasThunderstorm) {
                raiseTo(4, "Furtună / vijelie activă în zona aeroportului (${conditions.weatherPhenomena})")
            }
            if (conditions.hasFreezingPrecip) {
                raiseTo(4, "Precipitații înghețate / ninsoare / polei (${conditions.weatherPhenomena})")
            } else if (conditions.icingRisk) {
                raiseTo(3, "Risc de polei / îngheț pe suprafața pistei (${conditions.tempDewText})")
            } else if (conditions.hasRain) {
                raiseTo(2, "Pistă umedă — precipitații active (${conditions.weatherPhenomena})")
            }
        } else if (conditions == null) {
            factors.add("Datele METAR live nu sunt încă încărcate (se evaluează doar starea locală).")
        } else {
            factors.add("METAR live temporar indisponibil — evaluare bazată pe starea locală a pistei.")
        }

        if (factors.isEmpty()) {
            factors.add("VFR, pistă uscată (RWYCC 6), vânt în limite normale — fără factori de risc.")
        }

        val (shortTitle, suggestion, colorHex) = when (level) {
            1 -> Triple(
                "NIVEL 1/5 • OPERAȚIONALĂ",
                "Aterizare / Decolare: Recomandată fără restricții (condiții optime).",
                "#16A34A"
            )
            2 -> Triple(
                "NIVEL 2/5 • OPERAȚIONALĂ (ATENȚIE)",
                "Aterizare / Decolare: Posibilă în condiții bune, cu atenție la vânt / suprafață umedă.",
                "#65A30D"
            )
            3 -> Triple(
                "NIVEL 3/5 • RESTRICȚII MODERATE",
                "Aterizare / Decolare: Posibilă instrumental (IFR) / cu rezerve de frânare și vânt.",
                "#D97706"
            )
            4 -> Triple(
                "NIVEL 4/5 • OPERABILITATE DIFICILĂ",
                "Aterizare / Decolare: Dificilă — risc ridicat de întârzieri, holding sau redirecționare.",
                "#EA580C"
            )
            else -> Triple(
                "NIVEL 5/5 • NEOPERAȚIONALĂ",
                "Aterizare / Decolare: Nerecomandată / Blocată (condiții critice sau pistă închisă).",
                "#DC2626"
            )
        }

        return RunwayOperability(
            level = level.coerceIn(1, 5),
            shortTitle = shortTitle,
            suggestion = suggestion,
            colorHex = colorHex,
            factors = factors
        )
    }

    private fun computeWindComponent(runway: String, rwyHeadingDeg: Int, windDirDeg: Int, speedKt: Int): RunwayWindComponent {
        val diffRad = Math.toRadians((windDirDeg - rwyHeadingDeg).toDouble())
        val headwind = (speedKt * cos(diffRad)).roundToInt()
        val crosswind = abs((speedKt * sin(diffRad)).roundToInt())
        return RunwayWindComponent(runway, headwind, crosswind)
    }

    private fun parseMetarTemp(tok: String): Int? {
        return if (tok.startsWith("M")) {
            tok.substring(1).toIntOrNull()?.let { -it }
        } else {
            tok.toIntOrNull()
        }
    }

    private fun decodeRunwayGroup(group: String): String {
        return when {
            group.contains("CLRD") -> "$group (Pistă curățată)"
            group.contains("SNOCLO") -> "$group (Pistă închisă — zăpadă)"
            else -> group
        }
    }

    private fun translateWx(code: String): String {
        val parts = mutableListOf<String>()
        var rest = code
        when {
            rest.startsWith("-") -> { parts.add("slab"); rest = rest.substring(1) }
            rest.startsWith("+") -> { parts.add("puternic"); rest = rest.substring(1) }
            rest.startsWith("VC") -> { parts.add("în vecinătate"); rest = rest.substring(2) }
        }
        if (rest.contains("TS")) parts.add("furtună")
        if (rest.contains("SH")) parts.add("aversă")
        if (rest.contains("FZ")) parts.add("înghețată/polei")
        if (rest.contains("RA")) parts.add("ploaie")
        if (rest.contains("DZ")) parts.add("burniță")
        if (rest.contains("SN")) parts.add("ninsoare")
        if (rest.contains("GR") || rest.contains("GS")) parts.add("grindină")
        if (rest.contains("FG")) parts.add("ceață")
        if (rest.contains("BR")) parts.add("ceață ușoară")
        if (rest.contains("HZ")) parts.add("pâclă")
        return if (parts.isEmpty()) code else "$code (${parts.joinToString(" ")})"
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
