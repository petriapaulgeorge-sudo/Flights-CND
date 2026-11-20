package ro.constanta.flightnotifier.core

import org.json.JSONArray
import org.json.JSONObject
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.model.RawAircraft
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class AdsbFetchResult(
    val ok: Boolean,
    val provider: String?,
    val aircraft: List<RawAircraft>,
    val error: String? = null
)

data class BackfillResult(
    val ok: Boolean,
    val events: List<FlightEvent>,
    val error: String? = null
)

data class AircraftRouteDetails(
    val airline: String? = null,
    val origin: String? = null,
    val destination: String? = null,
    val registration: String? = null,
    val aircraftModel: String? = null
)

object AdsbClient {

    private const val USER_AGENT = "SAT-Monitorizare-Trafic-CND/1.5 (LRCK-CND)"
    private const val TIMEOUT_MS = 8000
    private val routeDetailsCache = ConcurrentHashMap<String, AircraftRouteDetails>()

    private data class Provider(
        val name: String,
        val buildUrl: (Int) -> String,
        val parse: (String) -> List<RawAircraft>
    )

    private val providers = listOf(
        Provider(
            name = "airplanes.live",
            buildUrl = { r -> "https://api.airplanes.live/v2/point/${AirportConfig.LAT}/${AirportConfig.LON}/$r" },
            parse = ::parseReadsbV2
        ),
        Provider(
            name = "adsb.lol",
            buildUrl = { r -> "https://api.adsb.lol/v2/point/${AirportConfig.LAT}/${AirportConfig.LON}/$r" },
            parse = ::parseReadsbV2
        ),
        Provider(
            name = "adsb.fi",
            buildUrl = { r -> "https://opendata.adsb.fi/api/v2/lat/${AirportConfig.LAT}/lon/${AirportConfig.LON}/dist/$r" },
            parse = ::parseReadsbV2
        ),
        Provider(
            name = "opensky",
            buildUrl = { r ->
                val radiusKm = r * 1.852
                val dLat = radiusKm / 111.1
                val dLon = radiusKm / (111.32 * kotlin.math.cos(Math.toRadians(AirportConfig.LAT)))
                val lamin = String.format(java.util.Locale.US, "%.4f", AirportConfig.LAT - dLat)
                val lamax = String.format(java.util.Locale.US, "%.4f", AirportConfig.LAT + dLat)
                val lomin = String.format(java.util.Locale.US, "%.4f", AirportConfig.LON - dLon)
                val lomax = String.format(java.util.Locale.US, "%.4f", AirportConfig.LON + dLon)
                "https://opensky-network.org/api/states/all?lamin=$lamin&lomin=$lomin&lamax=$lamax&lomax=$lomax"
            },
            parse = ::parseOpenSky
        )
    )

    fun fetchLiveAircraft(radiusNm: Int = 25): AdsbFetchResult {
        val errors = mutableListOf<String>()
        for (p in providers) {
            try {
                val body = httpGet(p.buildUrl(radiusNm), TIMEOUT_MS)
                val list = p.parse(body)
                return AdsbFetchResult(ok = true, provider = p.name, aircraft = list)
            } catch (e: Exception) {
                errors.add("${p.name}: ${e.message ?: "eroare"}")
            }
        }
        return AdsbFetchResult(
            ok = false,
            provider = null,
            aircraft = emptyList(),
            error = errors.joinToString(" | ")
        )
    }

    /**
     * Recuperează aterizările și decolările istorice de pe LRCK din OpenSky Network
     * pentru intervalul [beginSec, endSec] (maxim 48 ore pe cerere).
     */
    fun fetchMissedFlightsFromOpenSky(beginSec: Long, endSec: Long): BackfillResult {
        if (endSec <= beginSec) {
            return BackfillResult(ok = true, events = emptyList())
        }
        val clampedBegin = maxOf(beginSec, endSec - 48L * 3600L)
        val recovered = mutableListOf<FlightEvent>()
        val errors = mutableListOf<String>()

        try {
            val arrUrl = "https://opensky-network.org/api/flights/arrival?airport=${AirportConfig.ICAO}&begin=$clampedBegin&end=$endSec"
            val body = httpGet(arrUrl, 10_000)
            recovered.addAll(parseOpenSkyFlightsJson(body, FlightEventType.LANDING))
        } catch (e: Exception) {
            // 404 pe OpenSky înseamnă "0 zboruri în acest interval"
            if (e.message?.contains("HTTP 404") != true) {
                errors.add("Aterizări OpenSky: ${e.message}")
            }
        }

        try {
            val depUrl = "https://opensky-network.org/api/flights/departure?airport=${AirportConfig.ICAO}&begin=$clampedBegin&end=$endSec"
            val body = httpGet(depUrl, 10_000)
            recovered.addAll(parseOpenSkyFlightsJson(body, FlightEventType.TAKEOFF))
        } catch (e: Exception) {
            if (e.message?.contains("HTTP 404") != true) {
                errors.add("Decolări OpenSky: ${e.message}")
            }
        }

        val enriched = recovered.map { enrichEvent(it) }
        return if (errors.isEmpty() || enriched.isNotEmpty()) {
            BackfillResult(ok = true, events = enriched)
        } else {
            BackfillResult(ok = false, events = emptyList(), error = errors.joinToString(" | "))
        }
    }

    fun parseOpenSkyFlightsJson(body: String, type: FlightEventType): List<FlightEvent> {
        val arr = JSONArray(body)
        val out = ArrayList<FlightEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hex = o.optString("icao24").trim().lowercase()
            if (hex.isEmpty()) continue
            val callsign = o.optString("callsign").trim().uppercase().ifEmpty { hex.uppercase() }
            val tsSec = if (type == FlightEventType.LANDING) {
                o.optLong("lastSeen", 0L)
            } else {
                o.optLong("firstSeen", 0L)
            }
            if (tsSec <= 0L) continue
            val dep = o.optString("estDepartureAirport").takeIf { it.isNotBlank() && it != "null" }
            val arrApt = o.optString("estArrivalAirport").takeIf { it.isNotBlank() && it != "null" }
            val route = if (dep != null && arrApt != null) "$dep → $arrApt" else null

            out.add(
                FlightEvent(
                    id = "opensky_${type.name}_${hex}_$tsSec",
                    type = type,
                    timestampMs = tsSec * 1000L,
                    hex = hex,
                    callsign = callsign,
                    route = route,
                    runway = AirportConfig.RUNWAY,
                    distKm = 0.0,
                    altAglFt = 0,
                    gsKt = null,
                    detectionReason = "opensky_backfill",
                    simulated = false
                )
            )
        }
        return out
    }

    fun enrichEvent(event: FlightEvent): FlightEvent {
        if (event.simulated) return event
        var reg = event.registration
        var type = event.aircraftType
        var airline = event.airline
        var route = event.route

        try {
            val acBody = httpGet("https://api.adsbdb.com/v0/aircraft/${URLEncoder.encode(event.hex, "UTF-8")}", 4000)
            val ac = JSONObject(acBody).optJSONObject("response")?.optJSONObject("aircraft")
            if (ac != null) {
                if (reg.isNullOrBlank()) reg = ac.optString("registration").ifBlank { null }
                val mfr = ac.optString("manufacturer")
                val model = ac.optString("type")
                if (mfr.isNotBlank() && model.isNotBlank()) {
                    type = "$mfr $model"
                } else if (type.isNullOrBlank()) {
                    type = ac.optString("icao_type").ifBlank { null }
                }
                if (airline.isNullOrBlank()) {
                    airline = ac.optString("registered_owner").ifBlank { null }
                }
            }
        } catch (_: Exception) {
            // Opțional
        }

        if (event.callsign.length >= 4 && !event.callsign.equals(event.hex, ignoreCase = true)) {
            try {
                val rtBody = httpGet("https://api.adsbdb.com/v0/callsign/${URLEncoder.encode(event.callsign, "UTF-8")}", 4000)
                val fr = JSONObject(rtBody).optJSONObject("response")?.optJSONObject("flightroute")
                if (fr != null) {
                    val alName = fr.optJSONObject("airline")?.optString("name")
                    if (!alName.isNullOrBlank()) airline = alName
                    val orig = fr.optJSONObject("origin")?.optString("iata_code")
                    val dest = fr.optJSONObject("destination")?.optString("iata_code")
                    if (!orig.isNullOrBlank() && !dest.isNullOrBlank()) {
                        route = "$orig → $dest"
                    }
                }
            } catch (_: Exception) {
                // Opțional
            }
        }

        return event.copy(
            registration = reg,
            aircraftType = type,
            airline = airline,
            route = route
        )
    }

    private fun parseReadsbV2(body: String): List<RawAircraft> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("ac") ?: root.optJSONArray("aircraft") ?: JSONArray()
        val out = ArrayList<RawAircraft>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hex = o.optString("hex").trim()
            if (hex.isEmpty() || !o.has("lat") || !o.has("lon")) continue
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue

            val altRaw = o.opt("alt_baro")
            val onGround = altRaw is String && altRaw.equals("ground", ignoreCase = true)
            val altBaroFt = when (altRaw) {
                is Number -> altRaw.toInt()
                else -> null
            }
            val altGeomFt = if (o.has("alt_geom") && !o.isNull("alt_geom")) o.optDouble("alt_geom", Double.NaN).takeIf { it.isFinite() }?.roundToInt() else null
            val gsKt = if (o.has("gs") && !o.isNull("gs")) o.optDouble("gs", Double.NaN).takeIf { it.isFinite() }?.roundToInt() else null
            val trackDeg = if (o.has("track") && !o.isNull("track")) o.optDouble("track", Double.NaN).takeIf { it.isFinite() }?.roundToInt() else null
            val baroRate = if (o.has("baro_rate") && !o.isNull("baro_rate")) o.optDouble("baro_rate", Double.NaN).takeIf { it.isFinite() }?.roundToInt() else null
            val geomRate = if (o.has("geom_rate") && !o.isNull("geom_rate")) o.optDouble("geom_rate", Double.NaN).takeIf { it.isFinite() }?.roundToInt() else null
            val emStatus = o.optString("emergency").trim().takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }.orEmpty()

            out.add(
                RawAircraft(
                    hex = hex.lowercase(),
                    callsign = o.optString("flight").trim(),
                    registration = o.optString("r").trim(),
                    aircraftType = o.optString("t").trim(),
                    squawk = o.optString("squawk").trim(),
                    emergencyStatus = emStatus,
                    lat = lat,
                    lon = lon,
                    altBaroFt = altBaroFt,
                    altGeomFt = altGeomFt,
                    onGround = onGround,
                    gsKt = gsKt,
                    trackDeg = trackDeg,
                    verticalRateFpm = baroRate ?: geomRate
                )
            )
        }
        return out
    }

    private fun parseOpenSky(body: String): List<RawAircraft> {
        val states = JSONObject(body).optJSONArray("states") ?: return emptyList()
        val out = ArrayList<RawAircraft>(states.length())
        for (i in 0 until states.length()) {
            val s = states.optJSONArray(i) ?: continue
            val hex = s.optString(0).trim()
            val lon = s.optDouble(5, Double.NaN)
            val lat = s.optDouble(6, Double.NaN)
            if (hex.isEmpty() || !lat.isFinite() || !lon.isFinite()) continue

            val onGround = s.optBoolean(8, false)
            val baroM = s.optDouble(7, Double.NaN)
            val geoM = s.optDouble(13, Double.NaN)
            val velMs = s.optDouble(9, Double.NaN)
            val track = s.optDouble(10, Double.NaN)
            val vrateMs = s.optDouble(11, Double.NaN)

            out.add(
                RawAircraft(
                    hex = hex.lowercase(),
                    callsign = s.optString(1).trim(),
                    squawk = s.optString(14).trim(),
                    lat = lat,
                    lon = lon,
                    altBaroFt = if (!onGround && baroM.isFinite()) (baroM * 3.28084).roundToInt() else null,
                    altGeomFt = if (geoM.isFinite()) (geoM * 3.28084).roundToInt() else null,
                    onGround = onGround,
                    gsKt = if (velMs.isFinite()) (velMs * 1.94384).roundToInt() else null,
                    trackDeg = if (track.isFinite()) track.roundToInt() else null,
                    verticalRateFpm = if (vrateMs.isFinite()) (vrateMs * 196.8504).roundToInt() else null
                )
            )
        }
        return out
    }

    fun getCachedAircraftRouteDetails(hex: String, callsign: String): AircraftRouteDetails? {
        val key = "${hex.lowercase(Locale.US)}|${callsign.trim().uppercase(Locale.US)}"
        return routeDetailsCache[key]
    }

    /**
     * Preia în clar compania aeriană, aeroportul de origine și aeroportul de destinație
     * pentru aeronava selectată pe radar (adsbdb.com + fallback hexdb.io + dicționar local).
     */
    fun fetchAircraftRouteDetails(
        hex: String,
        callsign: String,
        airlineHint: String? = null,
        routeHint: String? = null
    ): AircraftRouteDetails {
        val normHex = hex.trim().lowercase(Locale.US)
        val normCs = callsign.trim().uppercase(Locale.US)
        val cacheKey = "$normHex|$normCs"
        routeDetailsCache[cacheKey]?.let { return it }

        var airline: String? = airlineHint?.takeIf { it.isNotBlank() } ?: resolveKnownAirline(normCs)
        var origin: String? = null
        var destination: String? = null
        var registration: String? = null
        var aircraftModel: String? = null

        // Dacă avem deja ruta (ex. zboruri simulate), o transformăm în nume complete de aeroporturi
        if (!routeHint.isNullOrBlank()) {
            val parts = routeHint.split("→", "-", "->").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                origin = resolveKnownAirport(parts[0])
                destination = resolveKnownAirport(parts[1])
            }
        }

        val isSimulated = normHex.startsWith("sim")
        if (!isSimulated) {
            // 1. adsbdb.com — rută după callsign (oferă companie, nume complet aeroport origine & destinație)
            if (normCs.length >= 4 && !normCs.equals(normHex, ignoreCase = true)) {
                try {
                    val body = httpGet(
                        "https://api.adsbdb.com/v0/callsign/${URLEncoder.encode(normCs, "UTF-8")}",
                        4500
                    )
                    val parsed = parseAdsbdbFlightRouteJson(body)
                    if (parsed != null) {
                        if (!parsed.airline.isNullOrBlank()) airline = parsed.airline
                        if (!parsed.origin.isNullOrBlank()) origin = parsed.origin
                        if (!parsed.destination.isNullOrBlank()) destination = parsed.destination
                    }
                } catch (_: Exception) {
                    // Continuăm cu sursele de rezervă
                }
            }

            // 2. Fallback hexdb.io — rută IATA/ICAO după callsign dacă adsbdb nu are ruta
            if ((origin.isNullOrBlank() || destination.isNullOrBlank()) &&
                normCs.length >= 4 && !normCs.equals(normHex, ignoreCase = true)
            ) {
                try {
                    val body = httpGet(
                        "https://hexdb.io/api/v1/route/iata/${URLEncoder.encode(normCs, "UTF-8")}",
                        3500
                    )
                    val routeStr = JSONObject(body).optString("route").trim()
                    val parts = routeStr.split("-").map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.size >= 2) {
                        if (origin.isNullOrBlank()) origin = resolveKnownAirport(parts.first())
                        if (destination.isNullOrBlank()) destination = resolveKnownAirport(parts.last())
                    }
                } catch (_: Exception) {
                    // Opțional
                }
            }

            // 3. adsbdb.com — detalii aeronavă după ICAO24 hex (proprietar/operator, model, înmatriculare)
            if (normHex.length == 6) {
                try {
                    val acBody = httpGet(
                        "https://api.adsbdb.com/v0/aircraft/${URLEncoder.encode(normHex, "UTF-8")}",
                        4000
                    )
                    val ac = JSONObject(acBody).optJSONObject("response")?.optJSONObject("aircraft")
                    if (ac != null) {
                        val reg = ac.optString("registration").trim().takeIf { it.isNotEmpty() && it != "null" }
                        if (reg != null) registration = reg
                        val mfr = ac.optString("manufacturer").trim().takeIf { it.isNotEmpty() && it != "null" }
                        val model = ac.optString("type").trim().takeIf { it.isNotEmpty() && it != "null" }
                        aircraftModel = when {
                            mfr != null && model != null -> "$mfr $model"
                            model != null -> model
                            else -> ac.optString("icao_type").trim().takeIf { it.isNotEmpty() && it != "null" }
                        }
                        if (airline.isNullOrBlank()) {
                            airline = ac.optString("registered_owner").trim()
                                .takeIf { it.isNotEmpty() && it != "null" }
                        }
                    }
                } catch (_: Exception) {
                    // Opțional
                }
            }
        }

        val details = AircraftRouteDetails(
            airline = airline,
            origin = origin,
            destination = destination,
            registration = registration,
            aircraftModel = aircraftModel
        )
        routeDetailsCache[cacheKey] = details
        return details
    }

    fun parseAdsbdbFlightRouteJson(body: String): AircraftRouteDetails? {
        val fr = JSONObject(body).optJSONObject("response")?.optJSONObject("flightroute") ?: return null
        val airlineName = fr.optJSONObject("airline")?.optString("name")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
        val origin = formatAdsbdbAirport(fr.optJSONObject("origin"))
        val destination = formatAdsbdbAirport(fr.optJSONObject("destination"))
        return AircraftRouteDetails(
            airline = airlineName,
            origin = origin,
            destination = destination
        )
    }

    private fun formatAdsbdbAirport(obj: JSONObject?): String? {
        if (obj == null) return null
        val name = obj.optString("name").trim().takeIf { it.isNotEmpty() && it != "null" }
        val iata = obj.optString("iata_code").trim().takeIf { it.isNotEmpty() && it != "null" }
        val icao = obj.optString("icao_code").trim().takeIf { it.isNotEmpty() && it != "null" }
        val city = obj.optString("municipality").trim().takeIf { it.isNotEmpty() && it != "null" }
        val country = obj.optString("country_name").trim().takeIf { it.isNotEmpty() && it != "null" }

        val codes = listOfNotNull(iata, icao).distinct().joinToString(" / ")
        if (name == null && codes.isEmpty()) return null

        return buildString {
            if (name != null) {
                append(name)
            } else if (city != null) {
                append(city)
            }
            if (codes.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("($codes)")
            }
            val locationParts = mutableListOf<String>()
            if (city != null && (name == null || !name.contains(city, ignoreCase = true))) {
                locationParts.add(city)
            }
            if (country != null) {
                locationParts.add(country)
            }
            if (locationParts.isNotEmpty()) {
                append(" — ")
                append(locationParts.joinToString(", "))
            }
        }
    }

    fun resolveKnownAirline(callsign: String): String? {
        val cs = callsign.trim().uppercase(Locale.US)
        if (cs.length < 3) return null
        return knownAirlines[cs.take(3)]
    }

    fun resolveKnownAirport(code: String): String {
        val c = code.trim().uppercase(Locale.US)
        return knownAirports[c] ?: c
    }

    private val knownAirlines = mapOf(
        "THY" to "Turkish Airlines",
        "WZZ" to "Wizz Air",
        "WMT" to "Wizz Air Malta",
        "WUK" to "Wizz Air UK",
        "ROT" to "TAROM",
        "RYR" to "Ryanair",
        "RUK" to "Ryanair UK",
        "DLH" to "Lufthansa",
        "AUA" to "Austrian Airlines",
        "BAW" to "British Airways",
        "AFR" to "Air France",
        "KLM" to "KLM Royal Dutch Airlines",
        "LOT" to "LOT Polish Airlines",
        "QTR" to "Qatar Airways",
        "UAE" to "Emirates",
        "HYS" to "HiSky",
        "HYM" to "HiSky Europe",
        "ANW" to "Animawings",
        "DAN" to "Dan Air",
        "BMS" to "Blue Air",
        "AEE" to "Aegean Airlines",
        "EZY" to "easyJet",
        "EJU" to "easyJet Europe",
        "SWR" to "Swiss International Air Lines",
        "VLG" to "Vueling",
        "PGT" to "Pegasus Airlines",
        "SXS" to "SunExpress",
        "FDB" to "flydubai",
        "ELY" to "El Al Israel Airlines",
        "CND" to "Corendon Dutch Airlines",
        "CAI" to "Corendon Airlines",
        "ETH" to "Ethiopian Airlines",
        "SVA" to "Saudia",
        "ETD" to "Etihad Airways",
        "TAP" to "TAP Air Portugal",
        "IBE" to "Iberia",
        "ITY" to "ITA Airways",
        "SAS" to "Scandinavian Airlines (SAS)",
        "FIN" to "Finnair",
        "NOZ" to "Norwegian Air",
        "ROF" to "Forțele Aeriene Române",
        "RCH" to "US Air Force (Air Mobility Command)",
        "NATO" to "NATO"
    )

    private val knownAirports = mapOf(
        "CND" to "Constanța Mihail Kogălniceanu (CND / LRCK) — România",
        "LRCK" to "Constanța Mihail Kogălniceanu (CND / LRCK) — România",
        "OTP" to "București Henri Coandă (OTP / LROP) — România",
        "LROP" to "București Henri Coandă (OTP / LROP) — România",
        "BBU" to "București Băneasa (BBU / LRBS) — România",
        "CLJ" to "Cluj-Napoca (CLJ / LRCL) — România",
        "TSR" to "Timișoara Traian Vuia (TSR / LRTR) — România",
        "IAS" to "Iași (IAS / LRIA) — România",
        "IST" to "Istanbul Airport (IST / LTFM) — Turcia",
        "LTFM" to "Istanbul Airport (IST / LTFM) — Turcia",
        "SAW" to "Istanbul Sabiha Gökçen (SAW / LTFJ) — Turcia",
        "LTFJ" to "Istanbul Sabiha Gökçen (SAW / LTFJ) — Turcia",
        "AYT" to "Antalya (AYT / LTAI) — Turcia",
        "LTN" to "Londra Luton (LTN / EGGW) — Marea Britanie",
        "EGGW" to "Londra Luton (LTN / EGGW) — Marea Britanie",
        "STN" to "Londra Stansted (STN / EGSS) — Marea Britanie",
        "LHR" to "Londra Heathrow (LHR / EGLL) — Marea Britanie",
        "LGW" to "Londra Gatwick (LGW / EGKK) — Marea Britanie",
        "VIE" to "Viena (VIE / LOWW) — Austria",
        "LOWW" to "Viena (VIE / LOWW) — Austria",
        "FRA" to "Frankfurt (FRA / EDDF) — Germania",
        "MUC" to "München (MUC / EDDM) — Germania",
        "AMS" to "Amsterdam Schiphol (AMS / EHAM) — Olanda",
        "CDG" to "Paris Charles de Gaulle (CDG / LFPG) — Franța",
        "FCO" to "Roma Fiumicino (FCO / LIRF) — Italia",
        "BGY" to "Milano Bergamo (BGY / LIME) — Italia",
        "MAD" to "Madrid Barajas (MAD / LEMD) — Spania",
        "BCN" to "Barcelona El Prat (BCN / LEBL) — Spania",
        "ATH" to "Atena (ATH / LGAV) — Grecia",
        "TLV" to "Tel Aviv Ben Gurion (TLV / LLBG) — Israel",
        "DXB" to "Dubai (DXB / OMDB) — EAU",
        "DOH" to "Doha Hamad (DOH / OTHH) — Qatar",
        "WAW" to "Varșovia Chopin (WAW / EPWA) — Polonia",
        "SOF" to "Sofia (SOF / LBSF) — Bulgaria",
        "BUD" to "Budapesta (BUD / LHBP) — Ungaria",
        "PRG" to "Praga (PRG / LKPR) — Cehia"
    )

    private fun httpGet(urlStr: String, timeoutMs: Int): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
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
