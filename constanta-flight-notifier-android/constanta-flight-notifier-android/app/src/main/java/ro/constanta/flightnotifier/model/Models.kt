package ro.constanta.flightnotifier.model

enum class FlightEventType {
    LANDING,
    TAKEOFF,
    APPROACH,
    GO_AROUND,
    EMERGENCY
}

data class RawAircraft(
    val hex: String,
    val callsign: String = "",
    val registration: String = "",
    val aircraftType: String = "",
    val squawk: String = "",
    val emergencyStatus: String = "",
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
    val routeHint: String? = null,
    val military: Boolean = false,
    val sourceType: String = "",
    val generalAviation: Boolean = false,
    val gaCategory: String = ""
)

data class ActiveAircraft(
    val hex: String,
    val callsign: String,
    val registration: String,
    val aircraftType: String,
    val squawk: String = "",
    val emergencyStatus: String = "",
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
    val lastSeenMs: Long,
    val airlineHint: String? = null,
    val routeHint: String? = null,
    val military: Boolean = false,
    val sourceType: String = "",
    val generalAviation: Boolean = false,
    val gaCategory: String = ""
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
    val simulated: Boolean = false,
    val squawk: String? = null,
    val emergencyCode: String? = null,
    val emergencyLabel: String? = null,
    val goAroundSummary: String? = null,
    val minApproachAltFt: Int? = null
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
    val notifyGoAround: Boolean = true,
    val notifyEmergency7700: Boolean = true,
    val notifyEmergencyPan: Boolean = true,
    val notifyEmergency7600: Boolean = true,
    val notifyEmergency7500: Boolean = true,
    val autoStartOnBoot: Boolean = true,
    val flyItalyApiKey: String = DEFAULT_FLYITALY_API_KEY,
    val openSkyClientId: String = DEFAULT_OPENSKY_CLIENT_ID,
    val openSkyClientSecret: String = DEFAULT_OPENSKY_CLIENT_SECRET,
    val notifyDroneDobrogea: Boolean = true,
    val droneAlertRadiusKm: Int = 10,
    val dimapApiUrl: String = "https://api.dimap.live/api/polling",
    val dimapApiKey: String = "",
    val ukraineAlarmApiKey: String = ""
) {
    val anyEmergencyEnabled: Boolean
        get() = notifyEmergency7700 || notifyEmergencyPan || notifyEmergency7600 || notifyEmergency7500

    val hasAnyDroneSourceConfigured: Boolean
        get() = dimapApiUrl.isNotBlank() || ukraineAlarmApiKey.isNotBlank()

    val hasOpenSkyAuth: Boolean
        get() = openSkyClientId.isNotBlank() && openSkyClientSecret.isNotBlank()

    companion object {
        const val DEFAULT_FLYITALY_API_KEY = "fia_wv70-aRHvgorZIA3OG7KO_o_LRlCbl2YqnJm6H-IaQ8"
        const val DEFAULT_OPENSKY_CLIENT_ID = "petriapaulgeorge@gmail.com-api-client"
        const val DEFAULT_OPENSKY_CLIENT_SECRET = "2azMq7yL2Be3LGf56YsadVlLGPAqyXA1"
    }
}

data class FeedSnapshot(
    val updatedAtMs: Long? = null,
    val provider: String? = null,
    val providerOk: Boolean = false,
    val providerError: String? = null,
    val activeAircraft: List<ActiveAircraft> = emptyList()
)
