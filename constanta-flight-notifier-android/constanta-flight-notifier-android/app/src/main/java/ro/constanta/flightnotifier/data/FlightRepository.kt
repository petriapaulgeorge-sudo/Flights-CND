package ro.constanta.flightnotifier.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ro.constanta.flightnotifier.core.FlightDetector
import ro.constanta.flightnotifier.core.FlightSimulator
import ro.constanta.flightnotifier.model.FeedSnapshot
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.model.MonitorSettings
import java.io.File

data class LocalRunwayNote(
    val operationalState: String = "Operațională",
    val rwyccCode: String = "6 — Uscat (frânare bună)",
    val note: String = ""
)

object FlightRepository {

    private const val PREFS_NAME = "constanta_flight_notifier_prefs"
    private const val LEGACY_KEY_EVENTS = "events_json"
    private const val EVENTS_FILE_NAME = "flight_events.json"
    private const val MAX_EVENTS = 2000

    val detector = FlightDetector()
    val simulator = FlightSimulator()

    private val _snapshot = MutableStateFlow(FeedSnapshot())
    val snapshot: StateFlow<FeedSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableStateFlow<List<FlightEvent>>(emptyList())
    val events: StateFlow<List<FlightEvent>> = _events.asStateFlow()

    private val _settings = MutableStateFlow(MonitorSettings())
    val settings: StateFlow<MonitorSettings> = _settings.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    @Volatile
    private var initialized = false
    private var prefs: SharedPreferences? = null
    private var eventsFile: File? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appCtx = context.applicationContext
        val appPrefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = appPrefs
        eventsFile = File(appCtx.filesDir, EVENTS_FILE_NAME)

        _settings.value = MonitorSettings(
            pollIntervalSec = appPrefs.getInt("pollIntervalSec", 15).coerceIn(8, 120),
            pollRadiusNm = appPrefs.getInt("pollRadiusNm", 25).coerceIn(10, 60),
            notifyLanding = appPrefs.getBoolean("notifyLanding", true),
            notifyTakeoff = appPrefs.getBoolean("notifyTakeoff", true),
            notifyApproach = appPrefs.getBoolean("notifyApproach", false),
            notifyEmergency = appPrefs.getBoolean("notifyEmergency", true),
            autoStartOnBoot = appPrefs.getBoolean("autoStartOnBoot", true)
        )
        _events.value = loadEvents(appPrefs)
        initialized = true
    }

    fun updateSnapshot(next: FeedSnapshot) {
        _snapshot.value = next
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
            ?.putBoolean("notifyEmergency", next.notifyEmergency)
            ?.putBoolean("autoStartOnBoot", next.autoStartOnBoot)
            ?.apply()
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
        val next = (listOf(event) + _events.value).take(MAX_EVENTS)
        _events.value = next
        saveEvents(next)
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
                    put("emergencyLabel", e.emergencyLabel ?: JSONObject.NULL)
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
                        emergencyLabel = o.optString("emergencyLabel").takeIf { it.isNotBlank() && it != "null" }
                    )
                )
            }
            if (file != null && !file.exists() && out.isNotEmpty()) {
                saveEvents(out)
                appPrefs.edit().remove(LEGACY_KEY_EVENTS).apply()
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
