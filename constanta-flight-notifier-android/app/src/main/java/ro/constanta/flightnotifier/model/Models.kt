package ro.constanta.flightnotifier.model

enum class FlightEventType {
    LANDING,
    TAKEOFF,
    APPROACH
}

data class RawAircraft(
    val hex: String,
    val callsign: String = "",
    val registration: String = "",
    val aircraftType: String = "",
    val squawk: String = "",
    val lat: Double,
    val lon: Double,
    val altBaroFt: Int? = null,
    val altGeomFt: Int? = null,
    val onGround: Boolean = false,
    val gsKt: Int? = null,
    val trackDeg: Int? = null,
    val verticalRateFpm: Int? = null,
    val simulated: Boolean = false,
    val airlineHint: String? = null,
    val routeHint: String? = null
)

data class ActiveAircraft(
    val hex: String,
    val callsign: String,
    val registration: String,
    val aircraftType: String,
    val phase: String,
    val simulated: Boolean,
    val lat: Double,
    val lon: Double,
    val distKm: Double,
    val altAglFt: Int?,
    val onGround: Boolean,
    val gsKt: Int?,
    val trackDeg: Int?,
    val verticalRateFpm: Int?,
    val runway: String,
    val trail: List<Pair<Double, Double>>,
    val lastSeenMs: Long
)

data class FlightEvent(
    val id: String,
    val type: FlightEventType,
    val timestampMs: Long,
    val hex: String,
    val callsign: String,
    val registration: String? = null,
    val aircraftType: String? = null,
    val airline: String? = null,
    val route: String? = null,
    val runway: String,
    val distKm: Double,
    val altAglFt: Int?,
    val gsKt: Int?,
    val detectionReason: String,
    val simulated: Boolean = false
) {
    val adsbExchangeUrl: String
        get() = "https://globe.adsbexchange.com/?icao=$hex"
}

data class EventSearchFilter(
    val query: String = "",
    val type: FlightEventType? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null
)

data class MonitorSettings(
    val pollIntervalSec: Int = 15,
    val pollRadiusNm: Int = 25,
    val notifyLanding: Boolean = true,
    val notifyTakeoff: Boolean = true,
    val notifyApproach: Boolean = false,
    val autoStartOnBoot: Boolean = true
)

data class FeedSnapshot(
    val updatedAtMs: Long? = null,
    val provider: String? = null,
    val providerOk: Boolean = false,
    val providerError: String? = null,
    val activeAircraft: List<ActiveAircraft> = emptyList()
)
