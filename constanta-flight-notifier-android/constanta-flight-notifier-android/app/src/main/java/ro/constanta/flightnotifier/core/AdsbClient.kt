package ro.constanta.flightnotifier.core

import org.json.JSONArray
import org.json.JSONObject
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.RawAircraft
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

data class AdsbFetchResult(
    val ok: Boolean,
    val provider: String?,
    val aircraft: List<RawAircraft>,
    val error: String? = null
)

object AdsbClient {

    private const val USER_AGENT = "ConstantaFlightNotifierAndroid/1.0 (LRCK-CND)"
    private const val TIMEOUT_MS = 8000

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

            out.add(
                RawAircraft(
                    hex = hex.lowercase(),
                    callsign = o.optString("flight").trim(),
                    registration = o.optString("r").trim(),
                    aircraftType = o.optString("t").trim(),
                    squawk = o.optString("squawk").trim(),
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

    private fun httpGet(urlStr: String, timeoutMs: Int): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                return reader.readText()
            }
        } finally {
            conn.disconnect()
        }
    }
}
