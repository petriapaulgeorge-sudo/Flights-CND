package ro.constanta.flightnotifier.model

data class DroneTarget(
    val id: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val courseDeg: Int? = null,
    val speedKmh: Int? = null,
    val label: String = "",
    val distToDobrogeaKm: Double,
    val insideDobrogea: Boolean,
    val nearestLandmark: String,
    val distToCndKm: Double,
    val bearingFromCnd: String,
    val source: String,
    val timestampMs: Long,
    val simulated: Boolean = false
)

data class BorderRegionAlarm(
    val regionId: String,
    val regionName: String,
    val alertType: String,
    val active: Boolean,
    val startedAt: String = "",
    val source: String = "UkraineAlarm"
)

data class DroneAlertRecord(
    val id: String,
    val timestampMs: Long,
    val title: String,
    val details: String,
    val distToDobrogeaKm: Double? = null,
    val insideDobrogea: Boolean = false,
    val landmark: String = "",
    val source: String = "DIMAP-API",
    val simulated: Boolean = false
)

data class DroneMonitorSnapshot(
    val updatedAtMs: Long? = null,
    val dimapConfigured: Boolean = false,
    val dimapOk: Boolean = false,
    val dimapStatus: String = "Neconfigurat — adaugă URL și cheia DIMAP-API (@di_helper)",
    val ukraineAlarmConfigured: Boolean = false,
    val ukraineAlarmOk: Boolean = false,
    val ukraineAlarmStatus: String = "Neconfigurat (opțional — Raion Izmail / Odesa)",
    val nearbyTargets: List<DroneTarget> = emptyList(),
    val alertTargets: List<DroneTarget> = emptyList(),
    val activeBorderAlarms: List<BorderRegionAlarm> = emptyList(),
    val totalTargetsParsed: Int = 0
)
