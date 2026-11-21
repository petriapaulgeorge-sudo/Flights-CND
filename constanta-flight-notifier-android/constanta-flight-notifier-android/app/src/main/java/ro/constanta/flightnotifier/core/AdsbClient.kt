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
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class AdsbFetchResult(
    val ok: Boolean,
    val provider: String?,
    val aircraft: List<RawAircraft>,
    val error: String? = null
)

data class BackfillResult(
    val ok: Boolean,
    val events: List<FlightEvent>,
    val error: String? = null,
    val needsAuth: Boolean = false,
    val summaryNote: String? = null
)

data class AircraftRouteDetails(
    val airline: String? = null,
    val origin: String? = null,
    val destination: String? = null,
    val registration: String? = null,
    val aircraftModel: String? = null
)

data class OgnDeviceInfo(
    val deviceId: String,
    val aircraftModel: String? = null,
    val registration: String? = null,
    val cn: String? = null,
    val deviceType: String? = null
)

object AdsbClient {

    private const val USER_AGENT = "SAT-Monitorizare-Trafic-CND/2.2.2 (LRCK-CND)"
    private const val TIMEOUT_MS = 7500
    private const val OPENSKY_MIN_POLL_INTERVAL_ANON_MS = 60_000L
    private const val OPENSKY_MIN_POLL_INTERVAL_AUTH_MS = 45_000L
    private const val OPENSKY_429_BACKOFF_MS = 15 * 60_000L
    const val OPENSKY_ACCOUNT_URL = "https://opensky-network.org/my-opensky/account"
    private const val OPENSKY_TOKEN_URL =
        "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token"
    private const val OPENSKY_TOKEN_REFRESH_MARGIN_MS = 30_000L

    private data class OpenSkyToken(
        val accessToken: String,
        val expiresAtMs: Long,
        val credentialFingerprint: String
    )

    private class OpenSkyAuthException(message: String) : IllegalStateException(message)

    private val routeDetailsCache = ConcurrentHashMap<String, AircraftRouteDetails>()
    private val ognDdbCache = ConcurrentHashMap<String, OgnDeviceInfo>()
    private val fetchExecutor = Executors.newFixedThreadPool(8)
    private val openSkyTokenLock = Any()

    @Volatile
    private var cachedOpenSkyToken: OpenSkyToken? = null

    @Volatile
    private var lastOpenSkyFetchMs: Long = 0L

    @Volatile
    private var openSkyBackoffUntilMs: Long = 0L

    private data class Provider(
        val name: String,
        val buildUrl: (Int) -> String,
        val parse: (String) -> List<RawAircraft>,
        val headers: Map<String, String> = emptyMap(),
        val filterWithinKm: Double? = null,
        val acceptHeader: String = "application/json",
        val fallbackUrl: ((Int) -> String)? = null,
        val isCoreAdsb: Boolean = true,
        val isOpenSky: Boolean = false,
        val customFetcher: ((Int) -> String)? = null
    )

    private val freeReadsbProviders = listOf(
        Provider(
            name = "adsb.lol",
            buildUrl = { r -> "https://api.adsb.lol/v2/point/${AirportConfig.LAT}/${AirportConfig.LON}/$r" },
            parse = { body -> parseReadsbV2(body, forceMilitary = false) }
        ),
        Provider(
            name = "airplanes.live",
            buildUrl = { r -> "https://api.airplanes.live/v2/point/${AirportConfig.LAT}/${AirportConfig.LON}/$r" },
            parse = { body -> parseReadsbV2(body, forceMilitary = false) }
        ),
        Provider(
            name = "adsb.fi",
            buildUrl = { r -> "https://opendata.adsb.fi/api/v2/lat/${AirportConfig.LAT}/lon/${AirportConfig.LON}/dist/$r" },
            parse = { body -> parseReadsbV2(body, forceMilitary = false) }
        ),
        Provider(
            name = "adsb.one",
            buildUrl = { r -> "https://api.adsb.one/v2/point/${AirportConfig.LAT}/${AirportConfig.LON}/$r" },
            parse = { body -> parseReadsbV2(body, forceMilitary = false) }
        )
    )

    /**
     * Interoghează ÎN PARALEL și fuzionează toate rețelele gratuite nefiltrate (adsb.lol,
     * airplanes.live, adsb.fi, adsb.one + flux militar /v2/mil + Open Glider Network OGN/FLARM
     * + OpenSky Network temperat cu suport OAuth2 + opțional FlyItalyADSB), maximizând acoperirea
     * MLAT, a zborurilor militare și a aeronavelor mici/școală (Tuzla LRTZ / Constanța LRCK).
     */
    fun fetchLiveAircraft(
        radiusNm: Int = 55,
        flyItalyApiKey: String = "",
        openSkyClientId: String = "",
        openSkyClientSecret: String = ""
    ): AdsbFetchResult {
        val radiusKm = maxOf(radiusNm * 1.852, 110.0)
        val dLat = radiusKm / 111.1
        val dLon = radiusKm / (111.32 * cos(Math.toRadians(AirportConfig.LAT)))
        val lamin = String.format(Locale.US, "%.4f", AirportConfig.LAT - dLat)
        val lamax = String.format(Locale.US, "%.4f", AirportConfig.LAT + dLat)
        val lomin = String.format(Locale.US, "%.4f", AirportConfig.LON - dLon)
        val lomax = String.format(Locale.US, "%.4f", AirportConfig.LON + dLon)

        val activeProviders = ArrayList<Provider>(freeReadsbProviders.size + 4)
        activeProviders.addAll(freeReadsbProviders)

        // Flux dedicat militar (adsb.lol /v2/mil) filtrat în jurul LRCK pentru acoperire militară maximă
        activeProviders.add(
            Provider(
                name = "MIL-feed",
                buildUrl = { _ -> "https://api.adsb.lol/v2/mil" },
                parse = { body -> parseReadsbV2(body, forceMilitary = true) },
                filterWithinKm = maxOf(radiusKm, 140.0),
                isCoreAdsb = false
            )
        )

        val trimmedKey = flyItalyApiKey.trim()
        if (trimmedKey.isNotEmpty()) {
            val kmInt = radiusKm.roundToInt().coerceIn(50, 480)
            activeProviders.add(
                Provider(
                    name = "FlyItalyADSB",
                    buildUrl = { _ ->
                        "https://api.flyitalyadsb.com/v2/lat/${AirportConfig.LAT}/lon/${AirportConfig.LON}/dist/$kmInt"
                    },
                    parse = { body -> parseReadsbV2(body, forceMilitary = false) },
                    headers = mapOf("X-Api-Key" to trimmedKey)
                )
            )
        }

        // Sursă gratuită Open Glider Network (OGN / FLARM) — aeronave mici, școală, planoare, elicoptere
        activeProviders.add(
            Provider(
                name = "OGN",
                buildUrl = { _ ->
                    "https://live.glidernet.org/lxml.php?a=0&b=$lamax&c=$lamin&d=$lomax&e=$lomin&z=2"
                },
                fallbackUrl = { _ ->
                    "http://live.glidernet.org/lxml.php?a=0&b=$lamax&c=$lamin&d=$lomax&e=$lomin&z=2"
                },
                parse = { body -> parseOgnLiveXml(body) },
                filterWithinKm = radiusKm,
                acceptHeader = "application/xml,text/xml,*/*",
                isCoreAdsb = false
            )
        )

        // Promovare OpenSky Network în fuziune paralelă (cu OAuth2 Bearer dacă e configurat -> 4.000 credite/zi)
        val hasOpenSkyCreds = openSkyClientId.isNotBlank() && openSkyClientSecret.isNotBlank()
        val openSkyIntervalMs = if (hasOpenSkyCreds) {
            OPENSKY_MIN_POLL_INTERVAL_AUTH_MS
        } else {
            OPENSKY_MIN_POLL_INTERVAL_ANON_MS
        }
        val openSkyUrl =
            "https://opensky-network.org/api/states/all?lamin=$lamin&lomin=$lomin&lamax=$lamax&lomax=$lomax&extended=1"

        val nowMs = System.currentTimeMillis()
        val queriedOpenSkyInParallel = nowMs >= openSkyBackoffUntilMs &&
            (nowMs - lastOpenSkyFetchMs) >= openSkyIntervalMs
        if (queriedOpenSkyInParallel) {
            lastOpenSkyFetchMs = nowMs
            activeProviders.add(
                Provider(
                    name = "OpenSky",
                    buildUrl = { _ -> openSkyUrl },
                    parse = { body -> parseOpenSky(body) },
                    isCoreAdsb = true,
                    isOpenSky = true,
                    customFetcher = { _ ->
                        fetchOpenSkyLiveStates(openSkyUrl, openSkyClientId, openSkyClientSecret)
                    }
                )
            )
        }

        val futures = activeProviders.map { p ->
            p to fetchExecutor.submit(Callable {
                val custom = p.customFetcher
                val body = if (custom != null) {
                    custom(radiusNm)
                } else {
                    try {
                        httpGet(p.buildUrl(radiusNm), TIMEOUT_MS, p.headers, p.acceptHeader)
                    } catch (primaryErr: Exception) {
                        val fb = p.fallbackUrl
                        if (fb != null) {
                            httpGet(fb(radiusNm), TIMEOUT_MS, p.headers, p.acceptHeader)
                        } else {
                            throw primaryErr
                        }
                    }
                }
                val parsed = p.parse(body)
                val maxKm = p.filterWithinKm
                if (maxKm != null) {
                    parsed.filter { haversineKm(it.lat, it.lon, AirportConfig.LAT, AirportConfig.LON) <= maxKm }
                } else {
                    parsed
                }
            })
        }

        val succeededNames = mutableListOf<String>()
        var coreAdsbSucceeded = false
        val collectedLists = mutableListOf<List<RawAircraft>>()
        val errors = mutableListOf<String>()

        for ((p, fut) in futures) {
            try {
                val list = fut.get((TIMEOUT_MS + 1500).toLong(), TimeUnit.MILLISECONDS)
                if (p.name != "MIL-feed") {
                    succeededNames.add(p.name)
                }
                if (p.isCoreAdsb) {
                    coreAdsbSucceeded = true
                }
                collectedLists.add(list)
            } catch (e: Exception) {
                fut.cancel(true)
                val msg = e.cause?.message ?: e.message ?: "eroare"
                if (p.isOpenSky && msg.contains("HTTP 429")) {
                    openSkyBackoffUntilMs = System.currentTimeMillis() + OPENSKY_429_BACKOFF_MS
                }
                errors.add("${p.name}: $msg")
            }
        }

        if (collectedLists.isNotEmpty() && succeededNames.isNotEmpty()) {
            val merged = mergeAircraftLists(collectedLists)
            if (coreAdsbSucceeded || merged.isNotEmpty()) {
                val label = if (succeededNames.size == 1) {
                    succeededNames.first()
                } else {
                    "Fuziune ${succeededNames.size} rețele (${succeededNames.joinToString("+")})"
                }
                return AdsbFetchResult(ok = true, provider = label, aircraft = merged)
            }
        }

        // Fallback final: OpenSky Network (dacă toate rețelele ADS-B de bază au eșuat)
        if (!queriedOpenSkyInParallel && System.currentTimeMillis() >= openSkyBackoffUntilMs) {
            try {
                lastOpenSkyFetchMs = System.currentTimeMillis()
                val body = fetchOpenSkyLiveStates(openSkyUrl, openSkyClientId, openSkyClientSecret)
                val list = parseOpenSky(body)
                val merged = mergeAircraftLists(collectedLists + listOf(list))
                return AdsbFetchResult(ok = true, provider = "opensky", aircraft = merged)
            } catch (e: Exception) {
                val msg = e.message ?: "eroare"
                if (msg.contains("HTTP 429")) {
                    openSkyBackoffUntilMs = System.currentTimeMillis() + OPENSKY_429_BACKOFF_MS
                }
                errors.add("opensky: $msg")
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
     * Combină aeronavele din mai multe rețele ADS-B / OGN / OpenSky, deduplicând pe codul ICAO hex
     * și pe înmatricularea normalizată (pentru aeronavele văzute simultan pe ADS-B și OGN/FLARM),
     * păstrând cele mai complete date (inclusiv flag-urile military, generalAviation și MLAT).
     */
    fun mergeAircraftLists(lists: List<List<RawAircraft>>): List<RawAircraft> {
        val byHex = LinkedHashMap<String, RawAircraft>()
        for (list in lists) {
            for (ac in list) {
                val key = ac.hex.trim().lowercase(Locale.US)
                if (key.isEmpty()) continue
                val existing = byHex[key]
                if (existing == null) {
                    byHex[key] = ac
                } else {
                    byHex[key] = combineAircraftPair(existing, ac)
                }
            }
        }

        // Deduplicare încrucișată ADS-B <-> OGN/FLARM după înmatriculare normalizată (ex. YR-SCP == YRSCP)
        val merged = LinkedHashMap<String, RawAircraft>()
        val regIndex = HashMap<String, String>() // normalizedReg -> hex key in merged

        // Mai întâi introducem sursele ADS-B / OpenSky / MLAT (au ICAO24 canonic)
        val sortedEntries = byHex.entries.sortedBy { (_, ac) ->
            if (ac.sourceType.contains("ogn", ignoreCase = true) || ac.hex.startsWith("ogn_")) 1 else 0
        }

        for ((hexKey, ac) in sortedEntries) {
            val isOgnOnly = ac.sourceType.contains("ogn", ignoreCase = true) || hexKey.startsWith("ogn_")
            val normIds = listOf(ac.registration, ac.callsign)
                .map { normalizeRegistrationKey(it) }
                .filter { it.length >= 4 && !it.equals(hexKey, ignoreCase = true) }

            var matchedHex: String? = null
            if (isOgnOnly && normIds.isNotEmpty()) {
                for (nid in normIds) {
                    val candidateHex = regIndex[nid] ?: continue
                    val candidate = merged[candidateHex] ?: continue
                    if (haversineKm(candidate.lat, candidate.lon, ac.lat, ac.lon) <= 15.0) {
                        matchedHex = candidateHex
                        break
                    }
                }
            }

            if (matchedHex != null) {
                val existing = merged[matchedHex]!!
                merged[matchedHex] = combineAircraftPair(existing, ac)
            } else {
                merged[hexKey] = ac
                for (nid in normIds) {
                    regIndex.putIfAbsent(nid, hexKey)
                }
            }
        }

        return merged.values.toList()
    }

    private fun combineAircraftPair(existing: RawAircraft, ac: RawAircraft): RawAircraft {
        val mergedMil = existing.military || ac.military
        val mergedGa = !mergedMil && (existing.generalAviation || ac.generalAviation)
        val mergedGaCat = if (mergedMil) "" else pickBetterGaCategory(existing.gaCategory, ac.gaCategory)
        val mergedSrc = when {
            existing.sourceType.isBlank() -> ac.sourceType
            ac.sourceType.isBlank() -> existing.sourceType
            existing.sourceType.equals(ac.sourceType, ignoreCase = true) -> existing.sourceType
            ac.sourceType.contains("ogn", ignoreCase = true) && !existing.sourceType.contains("ogn", ignoreCase = true) ->
                "${existing.sourceType}+ogn"
            else -> existing.sourceType
        }
        val mergedAltBaro = existing.altBaroFt ?: ac.altBaroFt
        val clearlyAirborne = mergedAltBaro != null && mergedAltBaro > AirportConfig.ELEVATION_FT + 400
        return existing.copy(
            callsign = existing.callsign.ifBlank { ac.callsign },
            registration = existing.registration.ifBlank { ac.registration },
            aircraftType = pickBetterAircraftType(existing.aircraftType, ac.aircraftType),
            squawk = existing.squawk.ifBlank { ac.squawk },
            emergencyStatus = existing.emergencyStatus.ifBlank { ac.emergencyStatus },
            altBaroFt = mergedAltBaro,
            altGeomFt = existing.altGeomFt ?: ac.altGeomFt,
            onGround = if (clearlyAirborne) false else (existing.onGround || ac.onGround),
            gsKt = existing.gsKt ?: ac.gsKt,
            trackDeg = existing.trackDeg ?: ac.trackDeg,
            verticalRateFpm = existing.verticalRateFpm ?: ac.verticalRateFpm,
            airlineHint = existing.airlineHint ?: ac.airlineHint,
            routeHint = existing.routeHint ?: ac.routeHint,
            military = mergedMil,
            sourceType = mergedSrc,
            generalAviation = mergedGa,
            gaCategory = mergedGaCat
        )
    }

    private fun pickBetterGaCategory(a: String, b: String): String {
        fun rank(c: String): Int = when (c) {
            "ȘCOALĂ" -> 5
            "ELICOPTER" -> 4
            "PLANOR" -> 3
            "ULM" -> 2
            "GA" -> 1
            else -> 0
        }
        return if (rank(b) > rank(a)) b else a
    }

    private fun pickBetterAircraftType(a: String, b: String): String {
        if (a.isBlank()) return b
        if (b.isBlank()) return a
        val aGeneric = a.startsWith("Avion ", ignoreCase = true) || a.startsWith("Aeronavă ", ignoreCase = true)
        val bGeneric = b.startsWith("Avion ", ignoreCase = true) || b.startsWith("Aeronavă ", ignoreCase = true)
        return if (aGeneric && !bGeneric) b else a
    }

    private fun normalizeRegistrationKey(raw: String): String {
        return raw.trim().uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
    }

    fun clearOpenSkyTokenCache() {
        synchronized(openSkyTokenLock) {
            cachedOpenSkyToken = null
        }
    }

    /**
     * Parsează răspunsul JSON OAuth2 al serverului auth.opensky-network.org și calculează
     * timestamp-ul de expirare cu marjă de siguranță (30s înainte de expirare).
     */
    fun parseOpenSkyTokenJson(body: String, nowMs: Long = System.currentTimeMillis()): Pair<String, Long>? {
        val root = JSONObject(body)
        val token = root.optString("access_token").trim()
        if (token.isEmpty()) return null
        val expiresInSec = root.optLong("expires_in", 1800L).coerceAtLeast(60L)
        val expiresAtMs = nowMs + (expiresInSec * 1000L) - OPENSKY_TOKEN_REFRESH_MARGIN_MS
        return token to expiresAtMs
    }

    /**
     * Verifică validitatea credențialelor OpenSky OAuth2 (Client ID + Client Secret)
     * solicitând un token proaspăt de la serverul OpenSky.
     */
    fun testOpenSkyCredentials(clientId: String, clientSecret: String): Result<String> {
        return try {
            getOpenSkyBearerToken(clientId, clientSecret, forceRefresh = true)
            Result.success("Token OAuth2 OpenSky valid (4.000 credite/zi).")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getOpenSkyBearerToken(
        clientId: String,
        clientSecret: String,
        forceRefresh: Boolean = false
    ): String {
        val id = clientId.trim()
        val secret = clientSecret.trim()
        if (id.isEmpty() || secret.isEmpty()) {
            throw OpenSkyAuthException("Lipsește Client ID sau Client Secret OpenSky.")
        }
        val fingerprint = "$id:${secret.hashCode()}:${secret.length}"
        synchronized(openSkyTokenLock) {
            val cached = cachedOpenSkyToken
            if (!forceRefresh &&
                cached != null &&
                cached.credentialFingerprint == fingerprint &&
                System.currentTimeMillis() < cached.expiresAtMs
            ) {
                return cached.accessToken
            }

            val body = try {
                httpPostForm(
                    urlStr = OPENSKY_TOKEN_URL,
                    formFields = mapOf(
                        "grant_type" to "client_credentials",
                        "client_id" to id,
                        "client_secret" to secret
                    ),
                    timeoutMs = 10_000
                )
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (msg.contains("HTTP 400") || msg.contains("HTTP 401") || msg.contains("HTTP 403")) {
                    cachedOpenSkyToken = null
                    throw OpenSkyAuthException(
                        "Credențiale OpenSky invalide ($msg). Verifică Client ID și Client Secret din contul OpenSky."
                    )
                }
                throw e
            }

            val parsed = parseOpenSkyTokenJson(body)
                ?: throw OpenSkyAuthException("Răspuns OAuth2 invalid de la serverul OpenSky.")
            cachedOpenSkyToken = OpenSkyToken(
                accessToken = parsed.first,
                expiresAtMs = parsed.second,
                credentialFingerprint = fingerprint
            )
            return parsed.first
        }
    }

    private fun httpGetOpenSkyAuthenticated(
        urlStr: String,
        timeoutMs: Int,
        clientId: String,
        clientSecret: String
    ): String {
        val token = getOpenSkyBearerToken(clientId, clientSecret, forceRefresh = false)
        return try {
            httpGet(urlStr, timeoutMs, mapOf("Authorization" to "Bearer $token"))
        } catch (e: Exception) {
            if (e.message?.contains("HTTP 401") == true) {
                val freshToken = getOpenSkyBearerToken(clientId, clientSecret, forceRefresh = true)
                httpGet(urlStr, timeoutMs, mapOf("Authorization" to "Bearer $freshToken"))
            } else {
                throw e
            }
        }
    }

    private fun fetchOpenSkyLiveStates(
        urlStr: String,
        clientId: String,
        clientSecret: String
    ): String {
        val hasCreds = clientId.isNotBlank() && clientSecret.isNotBlank()
        if (!hasCreds) {
            return httpGet(urlStr, TIMEOUT_MS)
        }
        return try {
            httpGetOpenSkyAuthenticated(urlStr, TIMEOUT_MS, clientId, clientSecret)
        } catch (_: OpenSkyAuthException) {
            // Nu întrerupem radarul live dacă credențialele OAuth2 sunt invalide — revenim la anonim
            httpGet(urlStr, TIMEOUT_MS)
        }
    }

    /**
     * Reconstruiește aterizările, decolările, ratările și urgențele dintr-o secvență
     * cronologică de snapshot-uri istorice OpenSky (`/api/states/all?time=...`) trecându-le
     * printr-o instanță izolată de `FlightDetector`.
     */
    fun replayHistoricalSnapshots(
        snapshots: List<Pair<Long, List<RawAircraft>>>
    ): List<FlightEvent> {
        if (snapshots.size < 2) return emptyList()
        val replayDetector = FlightDetector()
        val recovered = mutableListOf<FlightEvent>()
        for ((tsSec, aircraft) in snapshots.sortedBy { it.first }) {
            val res = replayDetector.processSnapshot(aircraft, tsSec * 1000L)
            for (evt in res.events) {
                if (evt.type == FlightEventType.APPROACH) continue
                recovered.add(
                    evt.copy(
                        id = "opensky_replay_${evt.type.name}_${evt.hex}_${evt.timestampMs / 1000L}",
                        detectionReason = "opensky_replay_${evt.detectionReason}"
                    )
                )
            }
        }
        return recovered
    }

    /**
     * Recuperează aterizările și decolările istorice de pe LRCK din OpenSky Network
     * folosind autentificare gratuită OAuth2 (`client_credentials`), combinând:
     * 1. Arhiva batch 48h (`/api/flights/arrival` și `/api/flights/departure` — actualizată nocturn)
     * 2. Replay radar din ultimele 60 minute (`/api/states/all?time=...&extended=1`) pentru
     *    întreruperile recente din cursul zilei curente.
     */
    fun fetchMissedFlightsFromOpenSky(
        beginSec: Long,
        endSec: Long,
        clientId: String = "",
        clientSecret: String = "",
        includeRecentReplay: Boolean = true,
        includeBatchApi: Boolean = true
    ): BackfillResult {
        if (endSec <= beginSec) {
            return BackfillResult(ok = true, events = emptyList())
        }
        val id = clientId.trim()
        val secret = clientSecret.trim()
        if (id.isEmpty() || secret.isEmpty()) {
            return BackfillResult(
                ok = false,
                events = emptyList(),
                error = "OpenSky blochează accesul anonim la istoricul zborurilor (HTTP 403). Configurează gratuit Client ID și Client Secret (OAuth2) din contul OpenSky.",
                needsAuth = true
            )
        }

        // Obținem/validăm token-ul OAuth2 o singură dată înainte de interogări
        try {
            getOpenSkyBearerToken(id, secret, forceRefresh = false)
        } catch (e: OpenSkyAuthException) {
            return BackfillResult(
                ok = false,
                events = emptyList(),
                error = e.message,
                needsAuth = true
            )
        } catch (e: Exception) {
            val msg = e.message ?: "eroare de rețea"
            val authErr = msg.contains("HTTP 400") || msg.contains("HTTP 401") || msg.contains("HTTP 403")
            return BackfillResult(
                ok = false,
                events = emptyList(),
                error = "Eroare autentificare OpenSky: $msg",
                needsAuth = authErr
            )
        }

        val clampedBegin = maxOf(beginSec, endSec - 47L * 3600L)
        val batchEvents = mutableListOf<FlightEvent>()
        val replayEvents = mutableListOf<FlightEvent>()
        val errors = mutableListOf<String>()
        var anyAuthFailure = false
        var anySuccess = false
        var checkedBatch = false
        var checkedReplay = false

        // 1. Arhiva batch OpenSky (ultimele 47h — lot procesat nocturn)
        if (includeBatchApi) {
            val batchEndpoints = listOf(
                Triple(
                    "Aterizări OpenSky",
                    "https://opensky-network.org/api/flights/arrival?airport=${AirportConfig.ICAO}&begin=$clampedBegin&end=$endSec",
                    FlightEventType.LANDING
                ),
                Triple(
                    "Decolări OpenSky",
                    "https://opensky-network.org/api/flights/departure?airport=${AirportConfig.ICAO}&begin=$clampedBegin&end=$endSec",
                    FlightEventType.TAKEOFF
                )
            )
            for ((label, url, type) in batchEndpoints) {
                try {
                    val body = httpGetOpenSkyAuthenticated(url, 12_000, id, secret)
                    batchEvents.addAll(parseOpenSkyFlightsJson(body, type))
                    anySuccess = true
                    checkedBatch = true
                } catch (e: Exception) {
                    val msg = e.message.orEmpty()
                    when {
                        // 404 pe OpenSky înseamnă "0 zboruri în acest interval" (nu este eroare)
                        msg.contains("HTTP 404") -> {
                            anySuccess = true
                            checkedBatch = true
                        }
                        e is OpenSkyAuthException || msg.contains("HTTP 401") || msg.contains("HTTP 403") -> {
                            anyAuthFailure = true
                            errors.add("$label: acces refuzat ($msg)")
                        }
                        msg.contains("HTTP 429") -> {
                            openSkyBackoffUntilMs = System.currentTimeMillis() + OPENSKY_429_BACKOFF_MS
                            errors.add("Limită zilnică OpenSky atinsă (HTTP 429)")
                        }
                        else -> errors.add("$label: $msg")
                    }
                }
            }
        }

        // 2. Replay radar din ultimele 60 min (`/api/states/all?time=...&extended=1`)
        //    Permite recuperarea imediată a zborurilor din ultima oră fără a aștepta lotul de noapte.
        if (includeRecentReplay && !anyAuthFailure) {
            val nowSec = System.currentTimeMillis() / 1000L
            val minAllowedReplaySec = nowSec - 3540L // marjă 60s față de limita OpenSky de 3600s
            val recentEndRaw = minOf(endSec, nowSec - 15L)
            val recentEnd = recentEndRaw - (recentEndRaw % 5L)
            val recentBeginRaw = maxOf(clampedBegin, minAllowedReplaySec)
            val recentBegin = recentBeginRaw + ((5L - (recentBeginRaw % 5L)) % 5L)

            if (recentEnd - recentBegin >= 90L) {
                val spanSec = recentEnd - recentBegin
                val stepSec = if (spanSec <= 30L * 60L) 60L else 90L
                val timestamps = mutableListOf<Long>()
                var t = recentBegin
                while (t < recentEnd && timestamps.size < 39) {
                    timestamps.add(t)
                    t += stepSec
                }
                if (timestamps.isEmpty() || timestamps.last() != recentEnd) {
                    timestamps.add(recentEnd)
                }

                // Cutie ~20 km în jurul LRCK (~0.18 deg² <= 25 deg² => doar 1 credit API / snapshot)
                val rLamin = String.format(Locale.US, "%.4f", AirportConfig.LAT - 0.18)
                val rLamax = String.format(Locale.US, "%.4f", AirportConfig.LAT + 0.18)
                val rLomin = String.format(Locale.US, "%.4f", AirportConfig.LON - 0.25)
                val rLomax = String.format(Locale.US, "%.4f", AirportConfig.LON + 0.25)

                val snapshots = mutableListOf<Pair<Long, List<RawAircraft>>>()
                var abortReplay = false
                for (chunk in timestamps.chunked(4)) {
                    if (abortReplay) break
                    val futs = chunk.map { ts ->
                        ts to fetchExecutor.submit(Callable {
                            val url =
                                "https://opensky-network.org/api/states/all?time=$ts&lamin=$rLamin&lomin=$rLomin&lamax=$rLamax&lomax=$rLomax&extended=1"
                            val body = httpGetOpenSkyAuthenticated(url, 8_000, id, secret)
                            parseOpenSky(body)
                        })
                    }
                    for ((ts, fut) in futs) {
                        try {
                            val list = fut.get(9_500L, TimeUnit.MILLISECONDS)
                            snapshots.add(ts to list)
                        } catch (e: Exception) {
                            fut.cancel(true)
                            val cause = e.cause ?: e
                            val msg = cause.message.orEmpty()
                            when {
                                cause is OpenSkyAuthException || msg.contains("HTTP 401") || msg.contains("HTTP 403") -> {
                                    anyAuthFailure = true
                                    errors.add("Replay OpenSky: acces refuzat ($msg)")
                                    abortReplay = true
                                }
                                msg.contains("HTTP 429") -> {
                                    openSkyBackoffUntilMs = System.currentTimeMillis() + OPENSKY_429_BACKOFF_MS
                                    errors.add("Limită zilnică OpenSky atinsă (HTTP 429)")
                                    abortReplay = true
                                }
                            }
                        }
                    }
                }

                if (snapshots.size >= 2) {
                    checkedReplay = true
                    anySuccess = true
                    replayEvents.addAll(replayHistoricalSnapshots(snapshots))
                }
            }
        }

        // Deduplicare internă strictă (fără dubluri pe ID / hex / indicativ / înmatriculare în ±30 min)
        // și filtrare exactă în intervalul solicitat [clampedBegin, endSec]
        val windowStartMs = clampedBegin * 1000L - 5 * 60_000L
        val windowEndMs = endSec * 1000L + 5 * 60_000L
        val combined = ArrayList<FlightEvent>(batchEvents.size + replayEvents.size)
        for (cand in (batchEvents + replayEvents)) {
            if (cand.timestampMs !in windowStartMs..windowEndMs) continue
            val candHex = cand.hex.trim().lowercase(Locale.US)
            val candCs = normalizeRegistrationKey(cand.callsign)
            val candReg = normalizeRegistrationKey(cand.registration.orEmpty())
            val dup = combined.any { existing ->
                if (existing.id.isNotBlank() && existing.id == cand.id) return@any true
                if (existing.type != cand.type) return@any false
                if (abs(existing.timestampMs - cand.timestampMs) > 30 * 60_000L) return@any false
                val exHex = existing.hex.trim().lowercase(Locale.US)
                if (exHex.isNotEmpty() && exHex == candHex) return@any true
                val exCs = normalizeRegistrationKey(existing.callsign)
                if (exCs.length >= 4 && exCs == candCs) return@any true
                val exReg = normalizeRegistrationKey(existing.registration.orEmpty())
                if (exReg.length >= 4 && exReg == candReg) return@any true
                if (exCs.length >= 4 && candReg.length >= 4 && exCs == candReg) return@any true
                if (exReg.length >= 4 && candCs.length >= 4 && exReg == candCs) return@any true
                false
            }
            if (!dup) combined.add(cand)
        }

        val enriched = combined.map { enrichEvent(it) }
        val spanHours = ((endSec - clampedBegin + 1800L) / 3600L).coerceAtLeast(1L)
        val scopes = mutableListOf<String>()
        if (checkedReplay) scopes.add("ultimele 60 min (replay radar)")
        if (checkedBatch) scopes.add("arhiva OpenSky (${spanHours}h)")
        val note = if (scopes.isNotEmpty()) {
            "Verificat: ${scopes.joinToString(" + ")}."
        } else {
            null
        }

        return if (anySuccess || enriched.isNotEmpty()) {
            BackfillResult(ok = true, events = enriched, summaryNote = note)
        } else {
            BackfillResult(
                ok = false,
                events = emptyList(),
                error = errors.distinct().joinToString(" | ").ifBlank { "OpenSky nu a răspuns." },
                needsAuth = anyAuthFailure
            )
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

        if (type != null) {
            resolveKnownAircraftModel(type)?.let { type = it }
        }
        if (airline.isNullOrBlank()) {
            airline = resolveKnownAirline(event.callsign)
                ?: resolveKnownSchoolOrGaOperator(event.callsign, reg.orEmpty())
        }

        return event.copy(
            registration = reg,
            aircraftType = type,
            airline = airline,
            route = route
        )
    }

    fun parseReadsbV2(body: String, forceMilitary: Boolean = false): List<RawAircraft> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("ac") ?: root.optJSONArray("aircraft") ?: JSONArray()
        val out = ArrayList<RawAircraft>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hex = o.optString("hex").trim().lowercase(Locale.US)
            if (hex.isEmpty()) continue

            var lat = if (o.has("lat") && !o.isNull("lat")) o.optDouble("lat", Double.NaN) else Double.NaN
            var lon = if (o.has("lon") && !o.isNull("lon")) o.optDouble("lon", Double.NaN) else Double.NaN

            // Suport MLAT / zboruri militare & școală cu poziție intermitentă în obiectul lastPosition (până la 180s)
            if (!lat.isFinite() || !lon.isFinite()) {
                val lp = o.optJSONObject("lastPosition")
                if (lp != null) {
                    val seenPos = lp.optDouble("seen_pos", 999.0)
                    if (seenPos <= 180.0) {
                        lat = lp.optDouble("lat", Double.NaN)
                        lon = lp.optDouble("lon", Double.NaN)
                    }
                }
            }
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
            val callsign = o.optString("flight").trim()
            val registration = o.optString("r").trim()
            val aircraftType = o.optString("t").trim()
            val emitterCat = o.optString("category").trim().uppercase(Locale.US)
            val dbFlags = o.optInt("dbFlags", 0)
            val isMil = forceMilitary || ((dbFlags and 1) != 0) || isLikelyMilitary(callsign, hex)
            val srcType = o.optString("type").trim()

            val (isGa, gaCat) = classifyGeneralAviation(
                callsign = callsign,
                registration = registration,
                aircraftType = aircraftType,
                emitterCategory = emitterCat,
                military = isMil,
                sourceType = srcType,
                altBaroFt = altBaroFt ?: altGeomFt,
                gsKt = gsKt,
                lat = lat,
                lon = lon
            )
            val schoolOp = if (!isMil) resolveKnownSchoolOrGaOperator(callsign, registration) else null

            out.add(
                RawAircraft(
                    hex = hex,
                    callsign = callsign,
                    registration = registration,
                    aircraftType = aircraftType,
                    squawk = o.optString("squawk").trim(),
                    emergencyStatus = emStatus,
                    lat = lat,
                    lon = lon,
                    altBaroFt = altBaroFt,
                    altGeomFt = altGeomFt,
                    onGround = onGround,
                    gsKt = gsKt,
                    trackDeg = trackDeg,
                    verticalRateFpm = baroRate ?: geomRate,
                    airlineHint = schoolOp,
                    military = isMil,
                    sourceType = srcType,
                    generalAviation = isGa,
                    gaCategory = gaCat
                )
            )
        }
        return out
    }

    private val ognMarkerRegex = Regex("<m\\s+[^>]*a=\"([^\"]+)\"")
    private val hex6Regex = Regex("^[0-9A-Fa-f]{6}$")

    /**
     * Parsează răspunsul XML al Open Glider Network (live.glidernet.org/lxml.php):
     * <m a="lat,lon,cn,reg,alt_m,hh:mm:ss,age_s,track_deg,speed_kmh,vz_ms,ac_type,receiver,device_id,ogn_id"/>
     */
    fun parseOgnLiveXml(xml: String): List<RawAircraft> {
        if (xml.isBlank()) return emptyList()
        val out = ArrayList<RawAircraft>()
        for (match in ognMarkerRegex.findAll(xml)) {
            val parts = match.groupValues[1].split(",")
            if (parts.size < 11) continue

            val lat = parts[0].trim().toDoubleOrNull() ?: continue
            val lon = parts[1].trim().toDoubleOrNull() ?: continue
            if (!lat.isFinite() || !lon.isFinite() || (lat == 0.0 && lon == 0.0)) continue

            val cn = parts[2].trim()
            val rawReg = parts[3].trim()
            val altM = parts[4].trim().toDoubleOrNull()
            val ageSec = parts[6].trim().toIntOrNull() ?: 0
            if (ageSec > 240) continue

            val trackDeg = parts[7].trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt()
                ?.takeIf { it in 0..360 }?.let { if (it == 360) 0 else it }
            val speedKmh = parts[8].trim().toDoubleOrNull()
            val gsKt = speedKmh?.takeIf { it.isFinite() && it >= 0.0 }?.let { (it * 0.539957).roundToInt() }
            val vRateMs = parts[9].trim().toDoubleOrNull()
            val vRateFpm = vRateMs?.takeIf { it.isFinite() }?.let { (it * 196.8504).roundToInt() }
            val typeCode = parts[10].trim().toIntOrNull() ?: 0

            // Ignoră obstacolele fixe (15) și parașutiștii (4)
            if (typeCode == 4 || typeCode == 15) continue

            val deviceIdRaw = parts.getOrNull(12)?.trim().orEmpty()
            val ognHash = parts.getOrNull(13)?.trim().orEmpty()
            val cleanDeviceHex = deviceIdRaw.takeIf { hex6Regex.matches(it) }?.lowercase(Locale.US)
            val hex = cleanDeviceHex ?: when {
                ognHash.isNotEmpty() -> "ogn_${ognHash.lowercase(Locale.US)}"
                rawReg.isNotEmpty() -> "ogn_${rawReg.lowercase(Locale.US)}"
                else -> continue
            }

            val isAnonymousReg = rawReg.isEmpty() ||
                rawReg.equals("0", ignoreCase = true) ||
                rawReg.equals("hidden", ignoreCase = true) ||
                rawReg.equals(ognHash, ignoreCase = true) ||
                (rawReg.length == 8 && rawReg.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })

            val ddbCached = cleanDeviceHex?.let { ognDdbCache[it] }
            val ddbCn = ddbCached?.cn?.takeIf { it.isNotBlank() }
            val reg = ddbCached?.registration?.takeIf { it.isNotBlank() }
                ?: if (isAnonymousReg) "" else rawReg.uppercase(Locale.US)
            val callsign = when {
                reg.isNotEmpty() -> reg.replace("-", "").replace(" ", "")
                ddbCn != null -> ddbCn.uppercase(Locale.US)
                cn.isNotEmpty() && !cn.startsWith("_") -> cn.uppercase(Locale.US)
                cleanDeviceHex != null -> cleanDeviceHex.uppercase(Locale.US)
                else -> hex.removePrefix("ogn_").uppercase(Locale.US)
            }

            val altFt = altM?.takeIf { it.isFinite() && it > -500.0 }?.let { (it * 3.28084).roundToInt() }
            val onGround = altFt != null && (altFt - AirportConfig.ELEVATION_FT) <= 150 && (gsKt ?: 0) < 15

            val (defaultModel, baseCategory) = when (typeCode) {
                1 -> "Planor / TMG" to "PLANOR"
                2 -> "Avion remorcher" to "GA"
                3 -> "Elicopter" to "ELICOPTER"
                5 -> "Avion parașutare" to "GA"
                6, 7 -> "Deltaplan / Parapantă" to "ULM"
                8 -> "Avion ușor (piston)" to "GA"
                9 -> "Avion turbină / Jet ușor" to "GA"
                11, 12 -> "Balon / Dirijabil" to "GA"
                13 -> "UAV / Dronă OGN" to "ULM"
                else -> "Aeronavă OGN/FLARM" to "GA"
            }

            val isMil = isLikelyMilitary(callsign, hex)
            val schoolOp = if (!isMil) resolveKnownSchoolOrGaOperator(callsign, reg) else null
            val model = ddbCached?.aircraftModel?.takeIf { it.isNotBlank() } ?: defaultModel
            val distTuzla = haversineKm(lat, lon, AirportConfig.TUZLA_LAT, AirportConfig.TUZLA_LON)
            val finalCat = when {
                isMil -> ""
                schoolOp != null && baseCategory == "GA" -> "ȘCOALĂ"
                baseCategory == "GA" && typeCode == 8 && distTuzla <= 35.0 -> "ȘCOALĂ"
                else -> baseCategory
            }

            out.add(
                RawAircraft(
                    hex = hex,
                    callsign = callsign,
                    registration = reg,
                    aircraftType = model,
                    lat = lat,
                    lon = lon,
                    altBaroFt = altFt,
                    altGeomFt = altFt,
                    onGround = onGround,
                    gsKt = gsKt,
                    trackDeg = trackDeg,
                    verticalRateFpm = vRateFpm,
                    airlineHint = schoolOp,
                    military = isMil,
                    sourceType = "ogn_flarm",
                    generalAviation = !isMil,
                    gaCategory = finalCat
                )
            )
        }
        return out
    }

    /**
     * Parsează răspunsul JSON din OGN Devices Database (ddb.glidernet.org/download/?j=1&device_id=...).
     */
    fun parseOgnDdbJson(body: String): OgnDeviceInfo? {
        val root = JSONObject(body)
        val arr = root.optJSONArray("devices") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("device_id").trim().uppercase(Locale.US)
            if (id.isEmpty()) continue
            val identified = o.optString("identified", "Y").trim()
            val model = o.optString("aircraft_model").trim().takeIf { it.isNotEmpty() && it != "null" }
            val reg = o.optString("registration").trim().takeIf { it.isNotEmpty() && it != "null" }
            val cn = o.optString("cn").trim().takeIf { it.isNotEmpty() && it != "null" }
            val devType = o.optString("device_type").trim().takeIf { it.isNotEmpty() && it != "null" }
            if (identified.equals("N", ignoreCase = true) && model == null && reg == null) continue
            return OgnDeviceInfo(
                deviceId = id,
                aircraftModel = model,
                registration = reg,
                cn = cn,
                deviceType = devType
            )
        }
        return null
    }

    private fun fetchOgnDdbDevice(deviceIdHex: String): OgnDeviceInfo? {
        val clean = deviceIdHex.trim().removePrefix("ogn_").uppercase(Locale.US)
        if (!hex6Regex.matches(clean)) return null
        val cacheKey = clean.lowercase(Locale.US)
        ognDdbCache[cacheKey]?.let { return it }

        val urls = listOf(
            "https://ddb.glidernet.org/download/?j=1&device_id=$clean",
            "http://ddb.glidernet.org/download/?j=1&device_id=$clean"
        )
        for (url in urls) {
            try {
                val body = httpGet(url, 3500)
                val info = parseOgnDdbJson(body)
                if (info != null) {
                    ognDdbCache[cacheKey] = info
                    return info
                }
                return null
            } catch (_: Exception) {
                // Încercăm fallback HTTP dacă HTTPS eșuează
            }
        }
        return null
    }

    /**
     * Clasifică aeronavele de aviație generală / școală / elicoptere / planoare / ULM
     * pe baza codului ICAO de aeronavă, categoriei ADS-B, sursei OGN, operatorilor locali
     * și profilului de zbor jos/lent (ex. Diamond DV20 la 2.500 ft și 40 kt lângă Tuzla).
     */
    fun classifyGeneralAviation(
        callsign: String,
        registration: String = "",
        aircraftType: String = "",
        emitterCategory: String = "",
        military: Boolean = false,
        sourceType: String = "",
        altBaroFt: Int? = null,
        gsKt: Int? = null,
        lat: Double = AirportConfig.LAT,
        lon: Double = AirportConfig.LON
    ): Pair<Boolean, String> {
        if (military) return false to ""

        val t = aircraftType.trim().uppercase(Locale.US)
        val cat = emitterCategory.trim().uppercase(Locale.US)
        val cs = callsign.trim().uppercase(Locale.US)
        val reg = registration.trim().uppercase(Locale.US)
        val distTuzla = haversineKm(lat, lon, AirportConfig.TUZLA_LAT, AirportConfig.TUZLA_LON)

        if (cat == "A7" || t in helicopterTypes) return true to "ELICOPTER"
        if (cat == "B1" || t in gliderTypes) return true to "PLANOR"
        if (cat == "B4" || t in ultralightTypes) return true to "ULM"
        if (t in schoolTrainerTypes) return true to "ȘCOALĂ"
        if (t in generalAviationTypes) {
            return true to if (distTuzla <= 35.0 && resolveKnownSchoolOrGaOperator(cs, reg) != null) "ȘCOALĂ" else "GA"
        }

        if (resolveKnownSchoolOrGaOperator(cs, reg) != null) {
            return true to "ȘCOALĂ"
        }

        if (cat == "A1") {
            val isLocalSchool = distTuzla <= 35.0 || reg.startsWith("YR-") || cs.startsWith("YR")
            return true to if (isLocalSchool) "ȘCOALĂ" else "GA"
        }

        if (sourceType.contains("ogn", ignoreCase = true)) {
            return true to "GA"
        }

        // Heuristică pentru surse fără tip aeronavă (ex. OpenSky / Mode-S MLAT):
        // indicativ tip înmatriculare privată + altitudine joasă + viteză mică
        if (!isCommercialAirlineCallsign(cs) && looksLikePrivateGaRegistration(cs, reg)) {
            val lowAndSlow = (altBaroFt == null || altBaroFt <= 12_500) && (gsKt == null || gsKt <= 165)
            if (lowAndSlow) {
                return true to if (distTuzla <= 40.0) "ȘCOALĂ" else "GA"
            }
        }

        return false to ""
    }

    private fun isCommercialAirlineCallsign(callsign: String): Boolean {
        val cs = callsign.trim().uppercase(Locale.US)
        if (cs.length < 4) return false
        val prefix = cs.take(3)
        if (prefix in knownAirlines && cs[3].isDigit()) return true
        return false
    }

    private fun looksLikePrivateGaRegistration(callsign: String, registration: String): Boolean {
        val candidates = listOf(registration, callsign)
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.length >= 4 }

        for (raw in candidates) {
            val compact = raw.replace("-", "")
            // Înmatriculări românești GA/școală/ULM (exclude seriile comerciale TAROM/HiSky/Animawings/DanAir)
            if (compact.startsWith("YR") && compact.length == 5) {
                val third = compact[2]
                if (third.isDigit()) return true // YR-1xxx..YR-9xxx (ULM / planoare / avioane ușoare)
                val suffix = compact.substring(2)
                if (suffix.all { it.isLetter() }) {
                    val commercialPrefixes = setOf("BA", "BG", "BM", "AT", "AG", "AS", "DS", "BE", "SK", "WI", "WO", "JO", "KI", "LC", "LD", "LA")
                    if (suffix.take(2) !in commercialPrefixes) return true
                }
            }
            if (compact.startsWith("YR") && compact.length == 6 && compact.substring(2).all { it.isDigit() }) {
                return true // ULM românesc YR-5xxx
            }
            // Înmatriculări europene/US tipice GA: D-E..., D-K..., D-M..., D-H..., OE-A/C/D..., LZ-..., HA-..., SP-..., OM-..., OK-..., N123...
            if (raw.startsWith("D-E") || raw.startsWith("D-K") || raw.startsWith("D-M") || raw.startsWith("D-H")) return true
            if (compact.length == 5 && (compact.startsWith("DE") || compact.startsWith("DK") || compact.startsWith("DM") || compact.startsWith("DH")) && compact.all { it.isLetter() }) return true
            if (raw.startsWith("LZ-") || raw.startsWith("HA-") || raw.startsWith("OM-") || raw.startsWith("OK-") || raw.startsWith("SP-") || raw.startsWith("OE-")) return true
            if (compact.startsWith("N") && compact.length in 4..6 && compact[1].isDigit()) return true
        }
        return false
    }

    /**
     * Identifică școlile de aviație și operatorii locali din zona Dobrogea / România
     * atunci când baza ADS-B nu are proprietarul completat.
     */
    fun resolveKnownSchoolOrGaOperator(callsign: String, registration: String = ""): String? {
        val normList = listOf(registration, callsign)
            .map { normalizeRegistrationKey(it) }
            .filter { it.length >= 4 }

        for (code in normList) {
            if (code.startsWith("SMURD") || code.startsWith("IGAV")) {
                return "IGAv / SMURD România"
            }
            // Regional Air Services — Aerodrom Tuzla (LRTZ): flota Diamond DV20/DA20/DA40 (YR-SC*, YR-RA*, YR-TZ*)
            if (code.startsWith("YRSC") || code.startsWith("YRRA") || code.startsWith("YRTZ")) {
                return "Regional Air Services — Tuzla (LRTZ)"
            }
            // Școala Superioară de Aviație Civilă (SSAvC): seria YR-MD*
            if (code.startsWith("YRMD")) {
                return "Școala Superioară de Aviație Civilă (SSAvC)"
            }
            // Aeroclubul României / ULM / planoare românești: YR-1xxx..YR-5xxx
            if (code.startsWith("YR") && code.length in 5..6 && code[2].isDigit()) {
                return "Aeroclubul României / Aviație ușoară (ULM)"
            }
        }
        return null
    }

    fun resolveKnownAircraftModel(icaoType: String): String? {
        val code = icaoType.trim().uppercase(Locale.US)
        if (code.isEmpty()) return null
        return knownGaAircraftModels[code]
    }

    private val schoolTrainerTypes = setOf(
        "DV20", "DA20", "DA40", "DA42",
        "C150", "C152", "C172",
        "P202", "P206", "P208", "P2002", "P2006", "P2010", "P06T",
        "P28A", "P28R", "P28T", "PA28", "PA34", "PA44",
        "AT3", "PS28", "Z242", "Z142", "Z42", "Z43",
        "DR40", "TB9", "TB10", "R200", "A210", "TOBA", "G115", "S22T"
    )

    private val generalAviationTypes = setOf(
        "C177", "C182", "C206", "C208", "C210",
        "PA18", "PA24", "PA32", "PA46",
        "SR20", "SR22", "BE33", "BE35", "BE36", "BE58", "BE76",
        "M20P", "M20T", "DA50", "DA62", "TB20",
        "PC12", "TBM7", "TBM8", "TBM9", "AN2", "BN2P", "DHC2"
    )

    private val helicopterTypes = setOf(
        "R22", "R44", "R66", "G2CA",
        "EC20", "EC30", "EC35", "EC45", "H125", "H130", "H135", "H145",
        "AS50", "AS55", "AS65", "B06", "B206", "B407", "B429",
        "A109", "A119", "A139", "AW39", "S76", "S92",
        "MI8", "MI17", "PUMA", "SA30", "H500", "MD52", "ALOU"
    )

    private val gliderTypes = setOf(
        "GLID", "LS4", "LS8", "ASW20", "ASW27", "ASW28", "ASK21", "ASK23",
        "DG40", "DG80", "DG10", "IS28", "IS29", "IS32", "PW5", "PW6", "DUOD", "VENT"
    )

    private val ultralightTypes = setOf(
        "ULAC", "EV97", "EVSS", "FK9", "FK14", "WT9", "VL3",
        "FDCT", "CTLS", "TL20", "TL30", "PIPI", "SW4", "PISI", "SIRA",
        "GYRO", "MTO", "IAR46", "ICP", "SAVN", "A22", "A32", "BR23"
    )

    private val knownGaAircraftModels = mapOf(
        "DV20" to "Diamond DV20 Katana",
        "DA20" to "Diamond DA20 Katana",
        "DA40" to "Diamond DA40 Diamond Star",
        "DA42" to "Diamond DA42 Twin Star",
        "DA62" to "Diamond DA62",
        "C150" to "Cessna 150",
        "C152" to "Cessna 152",
        "C172" to "Cessna 172 Skyhawk",
        "C182" to "Cessna 182 Skylane",
        "C206" to "Cessna 206 Stationair",
        "C208" to "Cessna 208 Caravan",
        "P202" to "Tecnam P2002 Sierra",
        "P2002" to "Tecnam P2002 Sierra",
        "P206" to "Tecnam P2006T",
        "P06T" to "Tecnam P2006T",
        "P2006" to "Tecnam P2006T",
        "P208" to "Tecnam P2008",
        "P28A" to "Piper PA-28 Cherokee",
        "PA28" to "Piper PA-28 Cherokee",
        "PA34" to "Piper PA-34 Seneca",
        "PA44" to "Piper PA-44 Seminole",
        "SR20" to "Cirrus SR20",
        "SR22" to "Cirrus SR22",
        "PS28" to "Czech Sport Aircraft PS-28 Cruiser",
        "Z242" to "Zlin Z-242L",
        "Z142" to "Zlin Z-142",
        "DR40" to "Robin DR400",
        "EV97" to "Evektor EV-97 Eurostar",
        "VL3" to "JMB VL-3 Evolution",
        "G2CA" to "Guimbal Cabri G2 (Elicopter școală)",
        "R22" to "Robinson R22",
        "R44" to "Robinson R44",
        "EC35" to "Airbus H135 / EC135",
        "H135" to "Airbus H135 / EC135",
        "H145" to "Airbus H145 / EC145",
        "AS50" to "Airbus H125 / AS350 Écureuil",
        "A139" to "Leonardo AW139"
    )

    fun isLikelyMilitary(callsign: String, hex: String): Boolean {
        val h = hex.trim().lowercase(Locale.US)
        if (h.startsWith("ae") || h.startsWith("af") || h.startsWith("43c")) return true
        val cs = callsign.trim().uppercase(Locale.US)
        if (cs.isEmpty()) return false
        return militaryPrefixes.any { cs.startsWith(it) }
    }

    private val militaryPrefixes = listOf(
        "ROF", "RCH", "NATO", "RRR", "FORTE", "LAGR", "NCHO", "JAKE",
        "HOMER", "BART", "GAF", "FAF", "IAM", "BAF", "NAF", "HAF",
        "TUAF", "PLF", "CNV", "CFC", "SHF", "ASY", "SAM", "SPAR",
        "USAF", "ARMY", "NAVY", "MMF", "MRAF", "DUKE", "KING", "REACH"
    )

    fun parseOpenSky(body: String): List<RawAircraft> {
        val states = JSONObject(body).optJSONArray("states") ?: return emptyList()
        val out = ArrayList<RawAircraft>(states.length())
        for (i in 0 until states.length()) {
            val s = states.optJSONArray(i) ?: continue
            val hex = s.optString(0).trim().lowercase(Locale.US)
            val lon = s.optDouble(5, Double.NaN)
            val lat = s.optDouble(6, Double.NaN)
            if (hex.isEmpty() || !lat.isFinite() || !lon.isFinite()) continue

            val onGround = s.optBoolean(8, false)
            val baroM = s.optDouble(7, Double.NaN)
            val geoM = s.optDouble(13, Double.NaN)
            val velMs = s.optDouble(9, Double.NaN)
            val track = s.optDouble(10, Double.NaN)
            val vrateMs = s.optDouble(11, Double.NaN)
            val cs = s.optString(1).trim()
            val altBaroFt = if (!onGround && baroM.isFinite()) (baroM * 3.28084).roundToInt() else null
            val altGeomFt = if (geoM.isFinite()) (geoM * 3.28084).roundToInt() else null
            val gsKt = if (velMs.isFinite()) (velMs * 1.94384).roundToInt() else null
            val isMil = isLikelyMilitary(cs, hex)

            // OpenSky category (index 17 cu extended=1: 2 = Light, 8 = Rotorcraft, 9 = Glider, 12 = Ultralight)
            val osCat = if (s.length() > 17) s.optInt(17, 0) else 0
            val mappedEmitter = when (osCat) {
                2 -> "A1"
                8 -> "A7"
                9 -> "B1"
                12 -> "B4"
                else -> ""
            }
            val (isGa, gaCat) = classifyGeneralAviation(
                callsign = cs,
                emitterCategory = mappedEmitter,
                military = isMil,
                sourceType = "opensky",
                altBaroFt = altBaroFt ?: altGeomFt,
                gsKt = gsKt,
                lat = lat,
                lon = lon
            )
            val schoolOp = if (!isMil) resolveKnownSchoolOrGaOperator(cs) else null

            out.add(
                RawAircraft(
                    hex = hex,
                    callsign = cs,
                    squawk = s.optString(14).trim(),
                    lat = lat,
                    lon = lon,
                    altBaroFt = altBaroFt,
                    altGeomFt = altGeomFt,
                    onGround = onGround,
                    gsKt = gsKt,
                    trackDeg = if (track.isFinite()) track.roundToInt() else null,
                    verticalRateFpm = if (vrateMs.isFinite()) (vrateMs * 196.8504).roundToInt() else null,
                    airlineHint = schoolOp,
                    military = isMil,
                    sourceType = "opensky",
                    generalAviation = isGa,
                    gaCategory = gaCat
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
     * pentru aeronava selectată pe radar (adsbdb.com + fallback hexdb.io + OGN DDB + dicționar local).
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

        var airline: String? = airlineHint?.takeIf { it.isNotBlank() }
            ?: resolveKnownAirline(normCs)
            ?: resolveKnownSchoolOrGaOperator(normCs)
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
            if (normCs.length >= 4 && !normCs.equals(normHex, ignoreCase = true) && !normHex.startsWith("ogn_")) {
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
                normCs.length >= 4 && !normCs.equals(normHex, ignoreCase = true) && !normHex.startsWith("ogn_")
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
            if (normHex.length == 6 && hex6Regex.matches(normHex)) {
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

                // 4. Fallback OGN Devices DataBase (ddb.glidernet.org) pentru aeronave ușoare / FLARM / OGN
                if (aircraftModel.isNullOrBlank() || registration.isNullOrBlank()) {
                    val ognInfo = fetchOgnDdbDevice(normHex)
                    if (ognInfo != null) {
                        if (registration.isNullOrBlank() && !ognInfo.registration.isNullOrBlank()) {
                            registration = ognInfo.registration
                        }
                        if (aircraftModel.isNullOrBlank() && !ognInfo.aircraftModel.isNullOrBlank()) {
                            aircraftModel = ognInfo.aircraftModel
                        }
                    }
                }
            }

            if (airline.isNullOrBlank()) {
                airline = resolveKnownSchoolOrGaOperator(normCs, registration.orEmpty())
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
        "LRTZ" to "Aerodrom Tuzla (LRTZ) — România",
        "TCE" to "Tulcea Delta Dunării (TCE / LRTC) — România",
        "LRTC" to "Tulcea Delta Dunării (TCE / LRTC) — România",
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

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun httpGet(
        urlStr: String,
        timeoutMs: Int,
        extraHeaders: Map<String, String> = emptyMap(),
        acceptHeader: String = "application/json"
    ): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", acceptHeader)
            setRequestProperty("User-Agent", USER_AGENT)
            for ((k, v) in extraHeaders) {
                setRequestProperty(k, v)
            }
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

    private fun httpPostForm(
        urlStr: String,
        formFields: Map<String, String>,
        timeoutMs: Int
    ): String {
        val payload = formFields.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            setFixedLengthStreamingMode(payload.size)
        }
        return try {
            conn.outputStream.use { out ->
                out.write(payload)
                out.flush()
            }
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
