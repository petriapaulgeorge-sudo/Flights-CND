package ro.constanta.flightnotifier.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ro.constanta.flightnotifier.core.DailyReportPdfExporter
import ro.constanta.flightnotifier.core.DobrogeaDroneAlertTracker
import ro.constanta.flightnotifier.core.FlightDetector
import ro.constanta.flightnotifier.core.FlightSimulator
import ro.constanta.flightnotifier.model.DailyReportData
import ro.constanta.flightnotifier.model.DroneAlertRecord
import ro.constanta.flightnotifier.model.DroneMonitorSnapshot
import ro.constanta.flightnotifier.model.FeedSnapshot
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.model.MonitorSettings
import ro.constanta.flightnotifier.model.RomatsaAirspaceSnapshot
import ro.constanta.flightnotifier.model.SavedDailyReport
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class LocalRunwayNote(
    val operationalState: String = "Operațională",
    val rwyccCode: String = "6 — Uscat (frânare bună)",
    val note: String = ""
)

object FlightRepository {

    private const val PREFS_NAME = "constanta_flight_notifier_prefs"
    private const val LEGACY_KEY_EVENTS = "events_json"
    private const val EVENTS_FILE_NAME = "flight_events.json"
    private const val SAVED_REPORTS_FILE_NAME = "saved_daily_reports.json"
    private const val SAVED_REPORTS_DIR_NAME = "saved_reports"
    private const val DRONE_ALERTS_FILE_NAME = "dobrogea_drone_alerts.json"
    private const val MAX_EVENTS = 2000
    private const val MAX_SAVED_REPORTS = 200
    private const val MAX_DRONE_ALERTS = 60

    val detector = FlightDetector()
    val simulator = FlightSimulator()
    val droneTracker = DobrogeaDroneAlertTracker()

    private val _snapshot = MutableStateFlow(FeedSnapshot())
    val snapshot: StateFlow<FeedSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableStateFlow<List<FlightEvent>>(emptyList())
    val events: StateFlow<List<FlightEvent>> = _events.asStateFlow()

    private val _savedReports = MutableStateFlow<List<SavedDailyReport>>(emptyList())
    val savedReports: StateFlow<List<SavedDailyReport>> = _savedReports.asStateFlow()

    private val _droneSnapshot = MutableStateFlow(DroneMonitorSnapshot())
    val droneSnapshot: StateFlow<DroneMonitorSnapshot> = _droneSnapshot.asStateFlow()

    private val _romatsaSnapshot = MutableStateFlow(RomatsaAirspaceSnapshot())
    val romatsaSnapshot: StateFlow<RomatsaAirspaceSnapshot> = _romatsaSnapshot.asStateFlow()

    private val _droneAlerts = MutableStateFlow<List<DroneAlertRecord>>(emptyList())
    val droneAlerts: StateFlow<List<DroneAlertRecord>> = _droneAlerts.asStateFlow()

    private val _settings = MutableStateFlow(MonitorSettings())
    val settings: StateFlow<MonitorSettings> = _settings.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    @Volatile
    private var initialized = false
    private var prefs: SharedPreferences? = null
    private var eventsFile: File? = null
    private var savedReportsFile: File? = null
    private var savedReportsDir: File? = null
    private var droneAlertsFile: File? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appCtx = context.applicationContext
        val appPrefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = appPrefs
        eventsFile = File(appCtx.filesDir, EVENTS_FILE_NAME)
        savedReportsFile = File(appCtx.filesDir, SAVED_REPORTS_FILE_NAME)
        savedReportsDir = File(appCtx.filesDir, SAVED_REPORTS_DIR_NAME).apply { mkdirs() }
        droneAlertsFile = File(appCtx.filesDir, DRONE_ALERTS_FILE_NAME)

        val legacyEmg = appPrefs.getBoolean("notifyEmergency", true)
        _settings.value = MonitorSettings(
            pollIntervalSec = appPrefs.getInt("pollIntervalSec", 15).coerceIn(8, 120),
            pollRadiusNm = appPrefs.getInt("pollRadiusNm", 25).coerceIn(10, 60),
            notifyLanding = appPrefs.getBoolean("notifyLanding", true),
            notifyTakeoff = appPrefs.getBoolean("notifyTakeoff", true),
            notifyApproach = appPrefs.getBoolean("notifyApproach", false),
            notifyGoAround = appPrefs.getBoolean("notifyGoAround", true),
            notifyEmergency7700 = appPrefs.getBoolean("notifyEmergency7700", legacyEmg),
            notifyEmergencyPan = appPrefs.getBoolean("notifyEmergencyPan", legacyEmg),
            notifyEmergency7600 = appPrefs.getBoolean("notifyEmergency7600", legacyEmg),
            notifyEmergency7500 = appPrefs.getBoolean("notifyEmergency7500", legacyEmg),
            autoStartOnBoot = appPrefs.getBoolean("autoStartOnBoot", true),
            flyItalyApiKey = appPrefs.getString("flyItalyApiKey", null)
                ?.takeIf { it.isNotBlank() }
                ?: MonitorSettings.DEFAULT_FLYITALY_API_KEY,
            openSkyClientId = appPrefs.getString("openSkyClientId", null)
                ?.takeIf { it.isNotBlank() }
                ?: MonitorSettings.DEFAULT_OPENSKY_CLIENT_ID,
            openSkyClientSecret = appPrefs.getString("openSkyClientSecret", null)
                ?.takeIf { it.isNotBlank() }
                ?: MonitorSettings.DEFAULT_OPENSKY_CLIENT_SECRET,
            notifyDroneDobrogea = appPrefs.getBoolean("notifyDroneDobrogea", true),
            droneAlertRadiusKm = appPrefs.getInt("droneAlertRadiusKm", 10).coerceIn(5, 50),
            dimapApiUrl = appPrefs.getString("dimapApiUrl", null)
                ?.takeIf { it.isNotBlank() }
                ?: ro.constanta.flightnotifier.core.DobrogeaDroneMonitor.DEFAULT_DIMAP_API_URL,
            dimapApiKey = appPrefs.getString("dimapApiKey", "") ?: "",
            ukraineAlarmApiKey = appPrefs.getString("ukraineAlarmApiKey", "") ?: ""
        )
        _events.value = loadEvents(appPrefs)
        _savedReports.value = loadSavedReports()
        _droneAlerts.value = loadDroneAlerts()
        initialized = true
    }

    fun updateSnapshot(next: FeedSnapshot) {
        _snapshot.value = next
    }

    fun updateDroneSnapshot(next: DroneMonitorSnapshot) {
        _droneSnapshot.value = next
    }

    fun updateRomatsaSnapshot(next: RomatsaAirspaceSnapshot) {
        _romatsaSnapshot.value = next
    }

    fun setMonitoring(running: Boolean) {
        _isMonitoring.value = running
    }

    fun updateSettings(next: MonitorSettings) {
        _settings.value = next
        prefs?.edit()
            ?.putInt("pollIntervalSec", next.pollIntervalSec)
            ?.putInt("pollRadiusNm", next.pollRadiusNm)
            ?.putBoolean("notifyLanding", next.notifyLanding)
            ?.putBoolean("notifyTakeoff", next.notifyTakeoff)
            ?.putBoolean("notifyApproach", next.notifyApproach)
            ?.putBoolean("notifyGoAround", next.notifyGoAround)
            ?.putBoolean("notifyEmergency7700", next.notifyEmergency7700)
            ?.putBoolean("notifyEmergencyPan", next.notifyEmergencyPan)
            ?.putBoolean("notifyEmergency7600", next.notifyEmergency7600)
            ?.putBoolean("notifyEmergency7500", next.notifyEmergency7500)
            ?.putBoolean("autoStartOnBoot", next.autoStartOnBoot)
            ?.putString("flyItalyApiKey", next.flyItalyApiKey.trim())
            ?.putString("openSkyClientId", next.openSkyClientId.trim())
            ?.putString("openSkyClientSecret", next.openSkyClientSecret.trim())
            ?.putBoolean("notifyDroneDobrogea", next.notifyDroneDobrogea)
            ?.putInt("droneAlertRadiusKm", next.droneAlertRadiusKm.coerceIn(5, 50))
            ?.putString("dimapApiUrl", next.dimapApiUrl.trim())
            ?.putString("dimapApiKey", next.dimapApiKey.trim())
            ?.putString("ukraineAlarmApiKey", next.ukraineAlarmApiKey.trim())
            ?.apply()
    }

    fun getLastOnlineSyncMs(): Long = prefs?.getLong("lastOnlineSyncMs", 0L) ?: 0L

    fun setLastOnlineSyncMs(tsMs: Long) {
        prefs?.edit()?.putLong("lastOnlineSyncMs", tsMs)?.apply()
    }

    fun getLocalRunwayNote(): LocalRunwayNote {
        val p = prefs ?: return LocalRunwayNote()
        return LocalRunwayNote(
            operationalState = p.getString("rwy_op_state", "Operațională") ?: "Operațională",
            rwyccCode = p.getString("rwy_rwycc", "6 — Uscat (frânare bună)") ?: "6 — Uscat (frânare bună)",
            note = p.getString("rwy_note", "") ?: ""
        )
    }

    fun saveLocalRunwayNote(note: LocalRunwayNote) {
        prefs?.edit()
            ?.putString("rwy_op_state", note.operationalState)
            ?.putString("rwy_rwycc", note.rwyccCode)
            ?.putString("rwy_note", note.note)
            ?.apply()
    }

    @Synchronized
    fun addEvent(event: FlightEvent) {
        val next = (listOf(event) + _events.value)
            .sortedByDescending { it.timestampMs }
            .take(MAX_EVENTS)
        _events.value = next
        saveEvents(next)
    }

    /**
     * Verifică dacă două evenimente reale reprezintă același zbor (fără dubluri):
     * același tip + fereastră ±30 minute + potrivire pe ID, ICAO24 hex, indicativ
     * sau înmatriculare normalizată (inclusiv încrucișat ADS-B / OGN / OpenSky).
     */
    fun isDuplicateFlightEvent(existing: FlightEvent, candidate: FlightEvent): Boolean {
        if (existing.simulated || candidate.simulated) return false
        if (existing.id.isNotBlank() && existing.id == candidate.id) return true
        if (existing.type != candidate.type) return false
        if (abs(existing.timestampMs - candidate.timestampMs) > 30 * 60_000L) return false

        val hexA = existing.hex.trim().lowercase(Locale.US)
        val hexB = candidate.hex.trim().lowercase(Locale.US)
        if (hexA.isNotEmpty() && hexA == hexB) return true

        val csA = normalizeFlightKey(existing.callsign)
        val csB = normalizeFlightKey(candidate.callsign)
        if (csA.length >= 4 && csA == csB) return true

        val regA = normalizeFlightKey(existing.registration.orEmpty())
        val regB = normalizeFlightKey(candidate.registration.orEmpty())
        if (regA.length >= 4 && regA == regB) return true

        if (csA.length >= 4 && regB.length >= 4 && csA == regB) return true
        if (regA.length >= 4 && csB.length >= 4 && regA == csB) return true

        return false
    }

    fun deduplicateFlightEvents(
        existing: List<FlightEvent>,
        candidates: List<FlightEvent>
    ): List<FlightEvent> {
        if (candidates.isEmpty()) return emptyList()
        val seen = existing.toMutableList()
        val uniqueNew = mutableListOf<FlightEvent>()
        for (cand in candidates) {
            if (seen.none { isDuplicateFlightEvent(it, cand) }) {
                seen.add(cand)
                uniqueNew.add(cand)
            }
        }
        return uniqueNew
    }

    private fun normalizeFlightKey(raw: String): String {
        return raw.trim().uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
    }

    /**
     * Îmbină zborurile recuperate din OpenSky cu istoricul local, garantând ZERO dubluri
     * (verificare pe ID, hex ICAO24, callsign și înmatriculare în fereastra ±30 minute).
     * Returnează numărul de zboruri nou adăugate.
     */
    @Synchronized
    fun mergeRecoveredEvents(recovered: List<FlightEvent>): Int {
        if (recovered.isEmpty()) return 0
        val current = _events.value
        val uniqueNew = deduplicateFlightEvents(current, recovered)
        if (uniqueNew.isNotEmpty()) {
            val sorted = (current + uniqueNew).sortedByDescending { it.timestampMs }.take(MAX_EVENTS)
            _events.value = sorted
            saveEvents(sorted)
        }
        return uniqueNew.size
    }

    /**
     * Șterge doar evenimentele simulate ale căror ID-uri sunt în [ids].
     */
    @Synchronized
    fun deleteSimulatedEventsByIds(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val before = _events.value.size
        val remaining = _events.value.filterNot { it.simulated && ids.contains(it.id) }
        val removed = before - remaining.size
        if (removed > 0) {
            _events.value = remaining
            saveEvents(remaining)
        }
        return removed
    }

    @Synchronized
    fun deleteAllSimulatedEvents(): Int {
        val before = _events.value.size
        val remaining = _events.value.filterNot { it.simulated }
        val removed = before - remaining.size
        if (removed > 0) {
            _events.value = remaining
            saveEvents(remaining)
        }
        return removed
    }

    @Synchronized
    fun clearEvents() {
        _events.value = emptyList()
        runCatching { eventsFile?.delete() }
        prefs?.edit()?.remove(LEGACY_KEY_EVENTS)?.apply()
    }

    private fun saveEvents(list: List<FlightEvent>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("type", e.type.name)
                    put("timestampMs", e.timestampMs)
                    put("hex", e.hex)
                    put("callsign", e.callsign)
                    put("registration", e.registration ?: JSONObject.NULL)
                    put("aircraftType", e.aircraftType ?: JSONObject.NULL)
                    put("airline", e.airline ?: JSONObject.NULL)
                    put("route", e.route ?: JSONObject.NULL)
                    put("runway", e.runway)
                    put("distKm", e.distKm)
                    put("altAglFt", e.altAglFt ?: JSONObject.NULL)
                    put("gsKt", e.gsKt ?: JSONObject.NULL)
                    put("detectionReason", e.detectionReason)
                    put("simulated", e.simulated)
                    put("squawk", e.squawk ?: JSONObject.NULL)
                    put("emergencyCode", e.emergencyCode ?: JSONObject.NULL)
                    put("emergencyLabel", e.emergencyLabel ?: JSONObject.NULL)
                    put("goAroundSummary", e.goAroundSummary ?: JSONObject.NULL)
                    put("minApproachAltFt", e.minApproachAltFt ?: JSONObject.NULL)
                }
            )
        }
        val json = arr.toString()
        val file = eventsFile
        if (file != null) {
            runCatching { file.writeText(json, Charsets.UTF_8) }
        }
    }

    private fun loadEvents(appPrefs: SharedPreferences): List<FlightEvent> {
        val file = eventsFile
        val raw = when {
            file != null && file.exists() -> runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            else -> appPrefs.getString(LEGACY_KEY_EVENTS, null)
        } ?: return emptyList()

        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<FlightEvent>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = runCatching { FlightEventType.valueOf(o.optString("type")) }.getOrNull() ?: continue
                out.add(
                    FlightEvent(
                        id = o.optString("id"),
                        type = type,
                        timestampMs = o.optLong("timestampMs"),
                        hex = o.optString("hex"),
                        callsign = o.optString("callsign"),
                        registration = o.optString("registration").takeIf { it.isNotBlank() && it != "null" },
                        aircraftType = o.optString("aircraftType").takeIf { it.isNotBlank() && it != "null" },
                        airline = o.optString("airline").takeIf { it.isNotBlank() && it != "null" },
                        route = o.optString("route").takeIf { it.isNotBlank() && it != "null" },
                        runway = o.optString("runway", "18/36"),
                        distKm = o.optDouble("distKm", 0.0),
                        altAglFt = if (o.isNull("altAglFt")) null else o.optInt("altAglFt"),
                        gsKt = if (o.isNull("gsKt")) null else o.optInt("gsKt"),
                        detectionReason = o.optString("detectionReason"),
                        simulated = o.optBoolean("simulated", false),
                        squawk = o.optString("squawk").takeIf { it.isNotBlank() && it != "null" },
                        emergencyCode = o.optString("emergencyCode").takeIf { it.isNotBlank() && it != "null" },
                        emergencyLabel = o.optString("emergencyLabel").takeIf { it.isNotBlank() && it != "null" },
                        goAroundSummary = o.optString("goAroundSummary").takeIf { it.isNotBlank() && it != "null" },
                        minApproachAltFt = if (o.isNull("minApproachAltFt")) null else o.optInt("minApproachAltFt")
                    )
                )
            }
            if (file != null && !file.exists() && out.isNotEmpty()) {
                saveEvents(out)
                appPrefs.edit().remove(LEGACY_KEY_EVENTS).apply()
            }
            out.sortedByDescending { it.timestampMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Arhivează PDF-ul generat în directorul intern persistent `files/saved_reports/`
     * și adaugă intrarea în istoricul de rapoarte al secțiunii Raport.
     */
    @Synchronized
    fun archiveDailyReportPdf(context: Context, report: DailyReportData): Pair<SavedDailyReport, File> {
        init(context)
        val dir = savedReportsDir ?: File(context.applicationContext.filesDir, SAVED_REPORTS_DIR_NAME).apply { mkdirs() }
        val nowMs = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        val safeSup = report.supervisor.replace(Regex("[^A-Za-z0-9_-]"), "").take(12).ifBlank { "SUP" }
        val fileName = "SAT_CND_Raport_${stamp}_$safeSup.pdf"
        val pdfFile = File(dir, fileName)

        FileOutputStream(pdfFile).use { out ->
            DailyReportPdfExporter.exportToStream(out, report)
        }

        val record = SavedDailyReport(
            id = "rep_${nowMs}_$safeSup",
            createdAtMs = nowMs,
            dateLabel = report.dateLabel,
            supervisor = report.supervisor,
            flightCount = report.flights.size,
            totalPassengers = report.flights.sumOf { it.passengers },
            totalHoldBaggage = report.flights.sumOf { it.holdBaggage },
            totalPmr = report.flights.sumOf { it.pmr },
            totalProhibited = report.prohibitedItems.total,
            pdfFileName = fileName,
            otherNotesPreview = report.otherNotes.trim().replace("\n", " ").take(120)
        )

        val next = (listOf(record) + _savedReports.value)
            .sortedByDescending { it.createdAtMs }
            .take(MAX_SAVED_REPORTS)
        _savedReports.value = next
        saveSavedReports(next)
        return record to pdfFile
    }

    fun getSavedReportFile(context: Context, record: SavedDailyReport): File {
        val dir = savedReportsDir ?: File(context.applicationContext.filesDir, SAVED_REPORTS_DIR_NAME)
        return File(dir, record.pdfFileName)
    }

    @Synchronized
    fun deleteSavedReport(context: Context, id: String): Boolean {
        val target = _savedReports.value.firstOrNull { it.id == id } ?: return false
        runCatching { getSavedReportFile(context, target).delete() }
        val remaining = _savedReports.value.filterNot { it.id == id }
        _savedReports.value = remaining
        saveSavedReports(remaining)
        return true
    }

    private fun saveSavedReports(list: List<SavedDailyReport>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("createdAtMs", r.createdAtMs)
                    put("dateLabel", r.dateLabel)
                    put("supervisor", r.supervisor)
                    put("flightCount", r.flightCount)
                    put("totalPassengers", r.totalPassengers)
                    put("totalHoldBaggage", r.totalHoldBaggage)
                    put("totalPmr", r.totalPmr)
                    put("totalProhibited", r.totalProhibited)
                    put("pdfFileName", r.pdfFileName)
                    put("otherNotesPreview", r.otherNotesPreview)
                }
            )
        }
        val file = savedReportsFile
        if (file != null) {
            runCatching { file.writeText(arr.toString(), Charsets.UTF_8) }
        }
    }

    private fun loadSavedReports(): List<SavedDailyReport> {
        val file = savedReportsFile
        if (file == null || !file.exists()) return emptyList()
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<SavedDailyReport>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val pdfName = o.optString("pdfFileName")
                if (id.isBlank() || pdfName.isBlank()) continue
                out.add(
                    SavedDailyReport(
                        id = id,
                        createdAtMs = o.optLong("createdAtMs"),
                        dateLabel = o.optString("dateLabel", "—"),
                        supervisor = o.optString("supervisor", "—"),
                        flightCount = o.optInt("flightCount", 0),
                        totalPassengers = o.optInt("totalPassengers", 0),
                        totalHoldBaggage = o.optInt("totalHoldBaggage", 0),
                        totalPmr = o.optInt("totalPmr", 0),
                        totalProhibited = o.optInt("totalProhibited", 0),
                        pdfFileName = pdfName,
                        otherNotesPreview = o.optString("otherNotesPreview", "")
                    )
                )
            }
            out.sortedByDescending { it.createdAtMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addDroneAlert(record: DroneAlertRecord) {
        val next = (listOf(record) + _droneAlerts.value)
            .sortedByDescending { it.timestampMs }
            .take(MAX_DRONE_ALERTS)
        _droneAlerts.value = next
        saveDroneAlerts(next)
    }

    @Synchronized
    fun clearSimulatedDroneAlerts(): Int {
        val before = _droneAlerts.value.size
        val remaining = _droneAlerts.value.filterNot { it.simulated }
        val removed = before - remaining.size
        if (removed > 0) {
            _droneAlerts.value = remaining
            saveDroneAlerts(remaining)
        }
        return removed
    }

    @Synchronized
    fun clearAllDroneAlerts() {
        _droneAlerts.value = emptyList()
        runCatching { droneAlertsFile?.delete() }
    }

    private fun saveDroneAlerts(list: List<DroneAlertRecord>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("timestampMs", a.timestampMs)
                    put("title", a.title)
                    put("details", a.details)
                    put("distToDobrogeaKm", a.distToDobrogeaKm ?: JSONObject.NULL)
                    put("insideDobrogea", a.insideDobrogea)
                    put("landmark", a.landmark)
                    put("source", a.source)
                    put("simulated", a.simulated)
                }
            )
        }
        val file = droneAlertsFile
        if (file != null) {
            runCatching { file.writeText(arr.toString(), Charsets.UTF_8) }
        }
    }

    private fun loadDroneAlerts(): List<DroneAlertRecord> {
        val file = droneAlertsFile
        if (file == null || !file.exists()) return emptyList()
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<DroneAlertRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                out.add(
                    DroneAlertRecord(
                        id = id,
                        timestampMs = o.optLong("timestampMs"),
                        title = o.optString("title", "Alertă dronă Dobrogea"),
                        details = o.optString("details", ""),
                        distToDobrogeaKm = if (o.isNull("distToDobrogeaKm")) null else o.optDouble("distToDobrogeaKm"),
                        insideDobrogea = o.optBoolean("insideDobrogea", false),
                        landmark = o.optString("landmark", ""),
                        source = o.optString("source", "DIMAP-API"),
                        simulated = o.optBoolean("simulated", false)
                    )
                )
            }
            out.sortedByDescending { it.timestampMs }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
