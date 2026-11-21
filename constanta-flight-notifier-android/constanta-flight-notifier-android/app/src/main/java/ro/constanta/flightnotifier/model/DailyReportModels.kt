package ro.constanta.flightnotifier.model

data class DailyFlightEntry(
    val id: String,
    val airline: String,
    val callsign: String,
    val aircraftType: String,
    val registration: String,
    val landingTime: String,
    val takeoffTime: String,
    val sortTimeMs: Long,
    var passengers: Int = 0,
    var holdBaggage: Int = 0,
    var pmr: Int = 0,
    val manual: Boolean = false
)

data class ProhibitedItems(
    val projectileDevices: Int = 0,
    val stunningDevices: Int = 0,
    val sharpObjects: Int = 0,
    val tools: Int = 0,
    val bluntInstruments: Int = 0,
    val explosivesIncendiary: Int = 0
) {
    val total: Int
        get() = projectileDevices + stunningDevices + sharpObjects + tools + bluntInstruments + explosivesIncendiary
}

data class DailyReportData(
    val dateLabel: String,
    val supervisor: String,
    val flights: List<DailyFlightEntry>,
    val shootingSession: Boolean,
    val securityCommittee: Boolean,
    val pnsaExemptions: Boolean,
    val prohibitedItems: ProhibitedItems,
    val otherNotes: String = ""
)

data class SavedDailyReport(
    val id: String,
    val createdAtMs: Long,
    val dateLabel: String,
    val supervisor: String,
    val flightCount: Int,
    val totalPassengers: Int,
    val totalHoldBaggage: Int,
    val totalPmr: Int,
    val totalProhibited: Int,
    val pdfFileName: String,
    val otherNotesPreview: String = ""
)
