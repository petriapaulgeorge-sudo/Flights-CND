package ro.constanta.flightnotifier.core

import ro.constanta.flightnotifier.model.ActiveAircraft
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.model.RawAircraft
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

data class DetectionResult(
    val events: List<FlightEvent>,
    val activeAircraft: List<ActiveAircraft>
)

class FlightDetector {

    private data class Sample(
        val ts: Long,
        val lat: Double,
        val lon: Double,
        val distKm: Double,
        val altAglFt: Int?,
        val onGround: Boolean,
        val gsKt: Int?,
        val trackDeg: Int?,
        val verticalRateFpm: Int?
    )

    private class TrackState(
        val hex: String,
        var callsign: String,
        var registration: String,
        var aircraftType: String,
        var simulated: Boolean,
        var airlineHint: String?,
        var routeHint: String?,
        var lastSeenMs: Long,
        var latest: Sample
    ) {
        var everOnGroundAtAirport: Boolean = false
        var everAirborneNearAirport: Boolean = false
        var approachingAirport: Boolean = false
        var maxAltAglFt: Int = latest.altAglFt ?: 0
        var phase: String = "tracking"
        val history: MutableList<Sample> = mutableListOf()
    }

    private val tracks = LinkedHashMap<String, TrackState>()
    private val cooldowns = HashMap<String, Long>()
    private var eventSeq = 0

    @Synchronized
    fun processSnapshot(aircraftList: List<RawAircraft>, nowMs: Long = System.currentTimeMillis()): DetectionResult {
        val emitted = mutableListOf<FlightEvent>()
        val seenHex = HashSet<String>()

        for (raw in aircraftList) {
            val hex = raw.hex.trim().lowercase()
            if (hex.isEmpty() || !raw.lat.isFinite() || !raw.lon.isFinite()) continue
            seenHex.add(hex)

            val distKm = round(haversineKm(raw.lat, raw.lon, AirportConfig.LAT, AirportConfig.LON) * 100.0) / 100.0
            val altAglFt = computeAltAglFt(raw)
            val onGround = computeOnGround(raw, altAglFt)
            val callsign = raw.callsign.trim().uppercase()

            val sample = Sample(
                ts = nowMs,
                lat = raw.lat,
                lon = raw.lon,
                distKm = distKm,
                altAglFt = altAglFt,
                onGround = onGround,
                gsKt = raw.gsKt,
                trackDeg = raw.trackDeg,
                verticalRateFpm = raw.verticalRateFpm
            )

            val existing = tracks[hex]
            val track = if (existing == null) {
                TrackState(
                    hex = hex,
                    callsign = callsign,
                    registration = raw.registration,
                    aircraftType = raw.aircraftType,
                    simulated = raw.simulated,
                    airlineHint = raw.airlineHint,
                    routeHint = raw.routeHint,
                    lastSeenMs = nowMs,
                    latest = sample
                ).also { tracks[hex] = it }
            } else {
                if (callsign.isNotEmpty()) existing.callsign = callsign
                if (raw.registration.isNotEmpty()) existing.registration = raw.registration
                if (raw.aircraftType.isNotEmpty()) existing.aircraftType = raw.aircraftType
                if (raw.simulated) existing.simulated = true
                if (raw.airlineHint != null) existing.airlineHint = raw.airlineHint
                if (raw.routeHint != null) existing.routeHint = raw.routeHint
                existing.lastSeenMs = nowMs
                existing
            }

            val prev = track.history.lastOrNull()
            track.history.add(sample)
            if (track.history.size > 16) track.history.removeAt(0)
            track.latest = sample
            if (altAglFt != null && altAglFt > track.maxAltAglFt) {
                track.maxAltAglFt = altAglFt
            }

            if (onGround && distKm <= AirportConfig.AIRPORT_ZONE_KM) {
                track.everOnGroundAtAirport = true
            }

            val airborneEnough = !onGround &&
                (altAglFt == null || altAglFt >= AirportConfig.TAKEOFF_ALT_AGL_FT) &&
                (raw.gsKt == null || raw.gsKt >= AirportConfig.MIN_AIRBORNE_SPEED_KT)

            if (airborneEnough && distKm <= AirportConfig.APPROACH_ZONE_KM) {
                track.everAirborneNearAirport = true
            }

            val altDelta = if (prev?.altAglFt != null && altAglFt != null) altAglFt - prev.altAglFt else null
            val distDelta = if (prev != null) distKm - prev.distKm else null

            val isClimbing = (raw.verticalRateFpm != null && raw.verticalRateFpm >= AirportConfig.MIN_CLIMB_RATE_FPM) ||
                (altDelta != null && altDelta >= 220)
            val isDescending = (raw.verticalRateFpm != null && raw.verticalRateFpm <= AirportConfig.MIN_DESCENT_RATE_FPM) ||
                (altDelta != null && altDelta <= -180)

            // 1. DECOLARE (TAKEOFF)
            val classicTakeoff = track.everOnGroundAtAirport &&
                airborneEnough &&
                distKm <= AirportConfig.TAKEOFF_MAX_DIST_KM &&
                (isClimbing || (altAglFt != null && altAglFt >= 450))

            val first = track.history.firstOrNull()
            val startedLowNear = first != null &&
                first.distKm <= AirportConfig.LATE_TAKEOFF_MAX_DIST_KM &&
                first.altAglFt != null &&
                first.altAglFt <= AirportConfig.LATE_TAKEOFF_MAX_ALT_AGL_FT

            val climbedEnough = first?.altAglFt != null && altAglFt != null &&
                (altAglFt - first.altAglFt) >= 300

            val headingOutward = isHeadingAway(raw.lat, raw.lon, raw.trackDeg)
            val movingOutward = headingOutward || (distDelta != null && distDelta > 0.25)

            val lateTakeoff = !track.everOnGroundAtAirport &&
                !track.approachingAirport &&
                track.history.size >= 2 &&
                startedLowNear &&
                airborneEnough &&
                distKm <= AirportConfig.TAKEOFF_MAX_DIST_KM &&
                isClimbing &&
                climbedEnough &&
                movingOutward

            if (classicTakeoff || lateTakeoff) {
                emitEvent(
                    FlightEventType.TAKEOFF,
                    track,
                    nowMs,
                    if (classicTakeoff) "ground_to_air" else "low_altitude_climbout"
                )?.let {
                    track.phase = "departing"
                    track.everOnGroundAtAirport = false
                    track.approachingAirport = false
                    emitted.add(it)
                }
            }

            // 2. APROPIERE & ATERIZARE (APPROACH / LANDING)
            val headingInward = isHeadingToward(raw.lat, raw.lon, raw.trackDeg)
            val movingCloser = distDelta != null && distDelta < -0.15
            val notClimbing = !isClimbing &&
                (raw.verticalRateFpm == null || raw.verticalRateFpm <= 250) &&
                (altDelta == null || altDelta <= 150)

            val onFinalProfile = !onGround &&
                notClimbing &&
                distKm <= AirportConfig.APPROACH_ZONE_KM &&
                altAglFt != null &&
                altAglFt <= AirportConfig.APPROACH_MAX_ALT_AGL_FT &&
                (isDescending || (altAglFt <= 1800 && (headingInward || movingCloser || distKm <= 2.2))) &&
                (headingInward || movingCloser || (distKm <= 2.2 && !headingOutward))

            if (onFinalProfile) {
                track.approachingAirport = true
                track.phase = "approaching"

                if (
                    distKm <= AirportConfig.APPROACH_ALERT_DIST_KM &&
                    altAglFt <= AirportConfig.APPROACH_ALERT_MAX_ALT_AGL_FT &&
                    (headingInward || movingCloser) &&
                    (isRunwayAligned(raw.trackDeg) || headingInward)
                ) {
                    emitEvent(FlightEventType.APPROACH, track, nowMs, "final_approach")?.let {
                        emitted.add(it)
                    }
                }
            }

            val classicLanding = (track.everAirborneNearAirport || track.approachingAirport) &&
                onGround &&
                distKm <= AirportConfig.AIRPORT_ZONE_KM &&
                track.maxAltAglFt >= 300

            if (classicLanding) {
                emitEvent(FlightEventType.LANDING, track, nowMs, "air_to_ground")?.let {
                    track.phase = "landed"
                    track.approachingAirport = false
                    track.everAirborneNearAirport = false
                    track.everOnGroundAtAirport = true
                    emitted.add(it)
                }
            }

            if (onGround && distKm <= AirportConfig.AIRPORT_ZONE_KM) {
                if (track.phase != "landed") track.phase = "ground"
            } else if (track.phase != "departing" && track.phase != "approaching") {
                track.phase = if (altAglFt != null && altAglFt > 10_000) "overflight" else "airborne"
            }
        }

        // 3. ATERIZARE PRIN DISPARIȚIA SEMNALULUI PE FINALĂ
        val iterator = tracks.entries.iterator()
        while (iterator.hasNext()) {
            val (hex, track) = iterator.next()
            if (seenHex.contains(hex)) continue
            val missingMs = nowMs - track.lastSeenMs

            if (
                track.approachingAirport &&
                missingMs >= AirportConfig.SIGNAL_LOSS_TIMEOUT_MS &&
                track.latest.distKm <= AirportConfig.TOUCHDOWN_LOSS_DIST_KM &&
                track.latest.altAglFt != null &&
                track.latest.altAglFt <= AirportConfig.TOUCHDOWN_LOSS_ALT_AGL_FT &&
                (track.latest.verticalRateFpm == null || track.latest.verticalRateFpm <= 150)
            ) {
                emitEvent(FlightEventType.LANDING, track, nowMs, "final_signal_loss")?.let {
                    track.phase = "landed"
                    track.approachingAirport = false
                    emitted.add(it)
                }
            }

            if (missingMs >= AirportConfig.STALE_TRACK_TIMEOUT_MS) {
                iterator.remove()
            }
        }

        cooldowns.entries.removeIf { (_, ts) -> nowMs - ts > AirportConfig.EVENT_COOLDOWN_MS * 2 }

        val active = tracks.values
            .filter { nowMs - it.lastSeenMs < 45_000L }
            .map { t ->
                ActiveAircraft(
                    hex = t.hex,
                    callsign = t.callsign.ifEmpty { t.hex.uppercase() },
                    registration = t.registration,
                    aircraftType = t.aircraftType,
                    phase = t.phase,
                    simulated = t.simulated,
                    lat = t.latest.lat,
                    lon = t.latest.lon,
                    distKm = t.latest.distKm,
                    altAglFt = t.latest.altAglFt,
                    onGround = t.latest.onGround,
                    gsKt = t.latest.gsKt,
                    trackDeg = t.latest.trackDeg,
                    verticalRateFpm = t.latest.verticalRateFpm,
                    runway = inferRunway(t.latest.trackDeg, t.latest.lat),
                    trail = t.history.map { it.lat to it.lon },
                    lastSeenMs = t.lastSeenMs
                )
            }
            .sortedBy { it.distKm }

        return DetectionResult(emitted, active)
    }

    private fun emitEvent(
        type: FlightEventType,
        track: TrackState,
        nowMs: Long,
        reason: String
    ): FlightEvent? {
        val key = "${track.hex}:${type.name}"
        val lastFired = cooldowns[key]
        if (lastFired != null && nowMs - lastFired < AirportConfig.EVENT_COOLDOWN_MS) {
            return null
        }
        if (type == FlightEventType.LANDING) {
            val lastTakeoff = cooldowns["${track.hex}:${FlightEventType.TAKEOFF.name}"]
            if (lastTakeoff != null && nowMs - lastTakeoff < 120_000L) return null
        }

        cooldowns[key] = nowMs
        eventSeq += 1

        return FlightEvent(
            id = "$nowMs-$eventSeq-${track.hex}",
            type = type,
            timestampMs = nowMs,
            hex = track.hex,
            callsign = track.callsign.ifEmpty { track.hex.uppercase() },
            registration = track.registration.ifEmpty { null },
            aircraftType = track.aircraftType.ifEmpty { null },
            airline = track.airlineHint,
            route = track.routeHint,
            runway = inferRunway(track.latest.trackDeg, track.latest.lat),
            distKm = track.latest.distKm,
            altAglFt = track.latest.altAglFt,
            gsKt = track.latest.gsKt,
            detectionReason = reason,
            simulated = track.simulated
        )
    }

    companion object {
        private const val EARTH_RADIUS_KM = 6371.0088

        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2.0 * EARTH_RADIUS_KM * atan2(sqrt(a), sqrt(1.0 - a))
        }

        fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        fun angleDiff(a: Double, b: Double): Double {
            return abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)
        }

        fun inferRunway(trackDeg: Int?, lat: Double): String {
            if (trackDeg != null) {
                val d = trackDeg.toDouble()
                if (angleDiff(d, 180.0) <= 65.0) return "18"
                if (angleDiff(d, 0.0) <= 65.0) return "36"
            }
            return if (lat >= AirportConfig.LAT) "18" else "36"
        }

        private fun computeAltAglFt(raw: RawAircraft): Int? {
            if (raw.onGround) return 0
            val msl = raw.altBaroFt ?: raw.altGeomFt ?: return null
            return max(0, msl - AirportConfig.ELEVATION_FT)
        }

        private fun computeOnGround(raw: RawAircraft, altAglFt: Int?): Boolean {
            if (raw.onGround) return true
            if (altAglFt != null && altAglFt <= AirportConfig.GROUND_ALT_AGL_FT) {
                if (raw.gsKt == null || raw.gsKt < 120) return true
            }
            return false
        }

        private fun isHeadingToward(lat: Double, lon: Double, trackDeg: Int?): Boolean {
            if (trackDeg == null) return false
            val toAirport = bearingDeg(lat, lon, AirportConfig.LAT, AirportConfig.LON)
            return angleDiff(trackDeg.toDouble(), toAirport) <= 42.0
        }

        private fun isHeadingAway(lat: Double, lon: Double, trackDeg: Int?): Boolean {
            if (trackDeg == null) return false
            val fromAirport = bearingDeg(AirportConfig.LAT, AirportConfig.LON, lat, lon)
            return angleDiff(trackDeg.toDouble(), fromAirport) <= 55.0
        }

        private fun isRunwayAligned(trackDeg: Int?): Boolean {
            if (trackDeg == null) return false
            val d = trackDeg.toDouble()
            return angleDiff(d, 180.0) <= 35.0 || angleDiff(d, 0.0) <= 35.0
        }
    }
}
