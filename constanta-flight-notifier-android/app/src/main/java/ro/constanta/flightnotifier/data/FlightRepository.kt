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
object FlightRepository {
    private const val PREFS_NAME = "constanta_flight_notifier_prefs"
    private const val KEY_EVENTS = "events_json"
    private const val MAX_EVENTS = 200
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
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = appPrefs
        _settings.value = MonitorSettings(
            pollIntervalSec = appPrefs.getInt("pollIntervalSec", 15).coerceIn(8, 120),
            pollRadiusNm = appPrefs.getInt("pollRadiusNm", 25).coerceIn(10, 60),
            notifyLanding = appPrefs.getBoolean("notifyLanding", true),
            notifyTakeoff = appPrefs.getBoolean("notifyTakeoff", true),
            notifyApproach = appPrefs.getBoolean("notifyApproach", false),
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
            ?.putBoolean("autoStartOnBoot", next.autoStartOnBoot)
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
        prefs?.edit()?.remove(KEY_EVENTS)?.apply()
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
                }
            )
        }
        prefs?.edit()?.putString(KEY_EVENTS, arr.toString())?.apply()
    }
    private fun loadEvents(appPrefs: SharedPreferences): List<FlightEvent> {
        val raw = appPrefs.getString(KEY_EVENTS, null) ?: return emptyList()
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
                        simulated = o.optBoolean("simulated", false)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
