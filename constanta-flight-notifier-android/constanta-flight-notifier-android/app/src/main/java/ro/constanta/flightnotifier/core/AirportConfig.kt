package ro.constanta.flightnotifier.core

import java.util.Locale

data class EmergencyInfo(
    val code: String,        // "7700", "PAN", "7600", "7500"
    val shortBadge: String,  // "7700", "PAN-PAN", "7600", "7500"
    val description: String
)

object AirportConfig {
    const val NAME = "Aeroportul Internațional Mihail Kogălniceanu Constanța"
    const val IATA = "CND"
    const val ICAO = "LRCK"
    const val LAT = 44.3622
    const val LON = 28.4883
    const val ELEVATION_FT = 353
    const val RUNWAY = "18/36"

    // Praguri de detecție
    const val AIRPORT_ZONE_KM = 4.8
    const val TAKEOFF_MAX_DIST_KM = 11.5
    const val LATE_TAKEOFF_MAX_DIST_KM = 7.5
    const val APPROACH_ZONE_KM = 16.0
    const val TOUCHDOWN_LOSS_DIST_KM = 5.5
    const val EMERGENCY_RADIUS_KM = 100.0
    const val EMERGENCY_POLL_RADIUS_NM = 55 // ~102 km
    const val GROUND_ALT_AGL_FT = 250
    const val TAKEOFF_ALT_AGL_FT = 300
    const val LATE_TAKEOFF_MAX_ALT_AGL_FT = 2400
    const val APPROACH_MAX_ALT_AGL_FT = 4500
    const val TOUCHDOWN_LOSS_ALT_AGL_FT = 1600
    const val APPROACH_ALERT_DIST_KM = 12.0
    const val APPROACH_ALERT_MAX_ALT_AGL_FT = 3200
    const val GO_AROUND_MAX_MIN_ALT_FT = 1800
    const val GO_AROUND_MAX_APPROACH_DIST_KM = 8.5
    const val GO_AROUND_MAX_DIST_KM = 14.0
    const val GO_AROUND_MIN_CLIMB_RATE_FPM = 600
    const val GO_AROUND_MIN_ALT_GAIN_FT = 220
    const val MIN_AIRBORNE_SPEED_KT = 65
    const val MIN_CLIMB_RATE_FPM = 300
    const val MIN_DESCENT_RATE_FPM = -250
    const val SIGNAL_LOSS_TIMEOUT_MS = 28_000L
    const val STALE_TRACK_TIMEOUT_MS = 180_000L
    const val EVENT_COOLDOWN_MS = 10 * 60_000L

    /**
     * Detectează codurile de urgență ICAO din squawk (7700/7600/7500) și din câmpul ADS-B
     * emergency (general, lifeguard/minfuel -> PAN-PAN, nordo, unlawful).
     */
    fun emergencyInfo(squawk: String?, emergencyStatus: String? = null): EmergencyInfo? {
        val sq = squawk?.trim().orEmpty()
        val em = emergencyStatus?.trim()?.lowercase(Locale.US).orEmpty()

        return when {
            sq == "7500" || em == "unlawful" -> EmergencyInfo(
                code = "7500",
                shortBadge = "7500",
                description = "7500 — Intervenție ilegală / Deturnare (HIJACK)"
            )
            sq == "7700" || em == "general" || em == "downed" -> EmergencyInfo(
                code = "7700",
                shortBadge = "7700",
                description = "7700 — Urgență generală (MAYDAY — pericol grav și iminent)"
            )
            sq == "7600" || em == "nordo" -> EmergencyInfo(
                code = "7600",
                shortBadge = "7600",
                description = "7600 — Pierdere comunicații radio (NORDO)"
            )
            em == "lifeguard" || em == "minfuel" || em == "pan" || em == "pan-pan" -> {
                val detail = when (em) {
                    "lifeguard" -> "Urgență medicală / prioritate LIFEGUARD"
                    "minfuel" -> "Combustibil minim (MINFUEL)"
                    else -> "Situație urgentă fără pericol imediat de viață"
                }
                EmergencyInfo(
                    code = "PAN",
                    shortBadge = "PAN-PAN",
                    description = "PAN-PAN — $detail"
                )
            }
            else -> null
        }
    }

    fun emergencyDescription(squawk: String?, emergencyStatus: String? = null): String? {
        return emergencyInfo(squawk, emergencyStatus)?.description
    }
}
