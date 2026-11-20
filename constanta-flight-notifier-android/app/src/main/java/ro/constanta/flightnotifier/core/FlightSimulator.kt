package ro.constanta.flightnotifier.core

import ro.constanta.flightnotifier.model.RawAircraft
import kotlin.math.max
import kotlin.math.roundToInt

class FlightSimulator {

    private data class SimFlight(
        val hex: String,
        val kind: String,
        val runway: String,
        val callsign: String,
        val registration: String,
        val aircraftType: String,
        val airline: String,
        val route: String,
        val startedAtMs: Long,
        val durationMs: Long = 24_000L
    )

    private val fleet = listOf(
        arrayOf("WZZ3128", "HA-LZN", "A21N", "Wizz Air", "LTN → CND"),
        arrayOf("THY1048", "TC-JSO", "B738", "Turkish Airlines", "IST → CND"),
        arrayOf("ROT209", "YR-ATJ", "AT76", "TAROM", "OTP → CND"),
        arrayOf("AUA781", "OE-LBO", "A320", "Austrian Airlines", "VIE → CND")
    )

    private val active = mutableListOf<SimFlight>()
    private var seq = 0

    @Synchronized
    fun spawn(kind: String, runway: String) {
        seq += 1
        val f = fleet[seq % fleet.size]
        val rwy = if (runway == "36") "36" else "18"
        val route = if (kind == "takeoff") f[4].split(" → ").reversed().joinToString(" → ") else f[4]
        active.add(
            SimFlight(
                hex = "sim%03x".format(seq),
                kind = if (kind == "takeoff") "takeoff" else "landing",
                runway = rwy,
                callsign = f[0],
                registration = f[1],
                aircraftType = f[2],
                airline = f[3],
                route = route,
                startedAtMs = System.currentTimeMillis()
            )
        )
    }

    @Synchronized
    fun hasActive(): Boolean = active.isNotEmpty()

    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): List<RawAircraft> {
        active.removeAll { nowMs - it.startedAtMs > it.durationMs }
        return active.map { s ->
            val p = ((nowMs - s.startedAtMs).toDouble() / s.durationMs).coerceIn(0.0, 1.0)
            val dirSign = if (s.runway == "18") -1.0 else 1.0
            val track = if (s.runway == "18") 182 else 2

            if (s.kind == "landing") {
                val distKm = if (p < 0.74) 11.5 * (1.0 - p / 0.74) else -1.3 * ((p - 0.74) / 0.26)
                val onGround = p >= 0.72
                val altAgl = if (onGround) 0 else (2600.0 * (1.0 - p / 0.72)).roundToInt()
                val gs = if (onGround) max(18, (95.0 - (p - 0.72) * 250.0).roundToInt()) else (152.0 - p * 28.0).roundToInt()
                RawAircraft(
                    hex = s.hex,
                    callsign = s.callsign,
                    registration = s.registration,
                    aircraftType = s.aircraftType,
                    lat = AirportConfig.LAT - dirSign * (distKm / 111.1),
                    lon = AirportConfig.LON,
                    altBaroFt = if (onGround) null else AirportConfig.ELEVATION_FT + altAgl,
                    altGeomFt = AirportConfig.ELEVATION_FT + altAgl,
                    onGround = onGround,
                    gsKt = gs,
                    trackDeg = track,
                    verticalRateFpm = if (onGround) 0 else -750,
                    simulated = true,
                    airlineHint = s.airline,
                    routeHint = s.route
                )
            } else {
                val onGround = p < 0.24
                val ap = max(0.0, (p - 0.24) / 0.76)
                val distKm = if (onGround) -1.2 + p * 6.0 else 0.3 + ap * 11.5
                val altAgl = if (onGround) 0 else (ap * 3800.0).roundToInt()
                val gs = if (onGround) (25.0 + p * 420.0).roundToInt() else (145.0 + ap * 110.0).roundToInt()
                RawAircraft(
                    hex = s.hex,
                    callsign = s.callsign,
                    registration = s.registration,
                    aircraftType = s.aircraftType,
                    lat = AirportConfig.LAT + dirSign * (distKm / 111.1),
                    lon = AirportConfig.LON + if (ap > 0.6) (ap - 0.6) * 0.02 else 0.0,
                    altBaroFt = if (onGround) null else AirportConfig.ELEVATION_FT + altAgl,
                    altGeomFt = AirportConfig.ELEVATION_FT + altAgl,
                    onGround = onGround,
                    gsKt = gs,
                    trackDeg = track,
                    verticalRateFpm = if (onGround) 0 else 1850,
                    simulated = true,
                    airlineHint = s.airline,
                    routeHint = s.route
                )
            }
        }
    }
}
