package ro.constanta.flightnotifier.core

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
    const val GROUND_ALT_AGL_FT = 250
    const val TAKEOFF_ALT_AGL_FT = 300
    const val LATE_TAKEOFF_MAX_ALT_AGL_FT = 2400
    const val APPROACH_MAX_ALT_AGL_FT = 4500
    const val TOUCHDOWN_LOSS_ALT_AGL_FT = 1600
    const val APPROACH_ALERT_DIST_KM = 12.0
    const val APPROACH_ALERT_MAX_ALT_AGL_FT = 3200
    const val MIN_AIRBORNE_SPEED_KT = 65
    const val MIN_CLIMB_RATE_FPM = 300
    const val MIN_DESCENT_RATE_FPM = -250
    const val SIGNAL_LOSS_TIMEOUT_MS = 28_000L
    const val STALE_TRACK_TIMEOUT_MS = 180_000L
    const val EVENT_COOLDOWN_MS = 10 * 60_000L
}
