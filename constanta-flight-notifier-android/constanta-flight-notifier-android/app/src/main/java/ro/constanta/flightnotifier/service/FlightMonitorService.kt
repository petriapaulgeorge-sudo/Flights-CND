package ro.constanta.flightnotifier.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.constanta.flightnotifier.core.AdsbClient
import ro.constanta.flightnotifier.core.DobrogeaDroneMonitor
import ro.constanta.flightnotifier.data.FlightRepository
import ro.constanta.flightnotifier.model.DroneAlertRecord
import ro.constanta.flightnotifier.model.FeedSnapshot
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.model.RawAircraft
import ro.constanta.flightnotifier.notify.NotificationHelper

class FlightMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var dronePollJob: Job? = null
    private var simJob: Job? = null

    @Volatile
    private var lastLiveAircraft: List<RawAircraft> = emptyList()

    @Volatile
    private var lastProvider: String? = null

    @Volatile
    private var lastProviderOk: Boolean = false

    @Volatile
    private var lastProviderError: String? = null

    override fun onCreate() {
        super.onCreate()
        FlightRepository.init(this)
        NotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                FlightRepository.setMonitoring(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SIMULATE -> {
                val kind = intent.getStringExtra(EXTRA_SIM_KIND) ?: "landing"
                val runway = intent.getStringExtra(EXTRA_SIM_RUNWAY) ?: "18"
                startForegroundSafe("Simulare activă pe Pista $runway…")
                FlightRepository.simulator.spawn(kind, runway)
                ensureSimLoop()
                if (pollJob?.isActive != true) {
                    FlightRepository.setMonitoring(true)
                    startPollLoop()
                }
                return START_STICKY
            }
            ACTION_POLL_NOW -> {
                startForegroundSafe("Actualizare radar LRCK…")
                scope.launch { pollOnce() }
                if (pollJob?.isActive != true) {
                    FlightRepository.setMonitoring(true)
                    startPollLoop()
                }
                return START_STICKY
            }
            else -> {
                startForegroundSafe("Monitorizare ADS-B & Dobrogea activă…")
                FlightRepository.setMonitoring(true)
                if (pollJob?.isActive != true) {
                    startPollLoop()
                }
                return START_STICKY
            }
        }
    }

    private fun startForegroundSafe(statusText: String) {
        val notif = NotificationHelper.buildMonitorNotification(this, statusText)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NotificationHelper.NOTIF_ID_MONITOR, notif, serviceType)
    }

    private fun updateMonitorNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NotificationHelper.NOTIF_ID_MONITOR,
            NotificationHelper.buildMonitorNotification(this, statusText)
        )
    }

    private fun startPollLoop() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                pollOnce()
                val intervalMs = FlightRepository.settings.value.pollIntervalSec.coerceIn(8, 120) * 1000L
                delay(intervalMs)
            }
        }
        startDronePollLoop()
    }

    private fun startDronePollLoop() {
        dronePollJob?.cancel()
        dronePollJob = scope.launch {
            while (isActive) {
                val settings = FlightRepository.settings.value
                if (settings.notifyDroneDobrogea && settings.hasAnyDroneSourceConfigured) {
                    pollDronesAndNotify(this@FlightMonitorService)
                }
                delay(20_000L)
            }
        }
    }

    private fun ensureSimLoop() {
        if (simJob?.isActive == true) return
        simJob = scope.launch {
            while (isActive && FlightRepository.simulator.hasActive()) {
                processCombinedTraffic(System.currentTimeMillis())
                delay(1000L)
            }
            processCombinedTraffic(System.currentTimeMillis())
        }
    }

    @Volatile
    private var backfillInProgress = false

    private fun pollOnce() {
        val settings = FlightRepository.settings.value
        val effectiveRadiusNm = if (settings.anyEmergencyEnabled) {
            maxOf(settings.pollRadiusNm, ro.constanta.flightnotifier.core.AirportConfig.EMERGENCY_POLL_RADIUS_NM)
        } else {
            maxOf(settings.pollRadiusNm, ro.constanta.flightnotifier.core.AirportConfig.EMERGENCY_POLL_RADIUS_NM)
        }
        val result = AdsbClient.fetchLiveAircraft(
            radiusNm = effectiveRadiusNm,
            flyItalyApiKey = settings.flyItalyApiKey
        )
        val nowMs = System.currentTimeMillis()
        if (result.ok) {
            val prevOnlineMs = FlightRepository.getLastOnlineSyncMs()
            FlightRepository.setLastOnlineSyncMs(nowMs)

            // Dacă telefonul a fost offline >= 10 minute, recuperează automat zborurile ratate din OpenSky
            if (prevOnlineMs > 0L && nowMs - prevOnlineMs >= 10 * 60_000L && !backfillInProgress) {
                triggerAutoBackfill(prevOnlineMs, nowMs)
            }

            lastLiveAircraft = result.aircraft
            lastProvider = result.provider
            lastProviderOk = true
            lastProviderError = null
        } else {
            lastProviderOk = false
            lastProviderError = result.error
        }
        processCombinedTraffic(nowMs)
    }

    private fun triggerAutoBackfill(fromMs: Long, toMs: Long) {
        backfillInProgress = true
        scope.launch {
            try {
                val res = AdsbClient.fetchMissedFlightsFromOpenSky(fromMs / 1000L, toMs / 1000L)
                if (res.ok && res.events.isNotEmpty()) {
                    val added = FlightRepository.mergeRecoveredEvents(res.events)
                    if (added > 0) {
                        NotificationHelper.notifyBackfillRecovered(this@FlightMonitorService, added)
                    }
                }
            } finally {
                backfillInProgress = false
            }
        }
    }

    private fun processCombinedTraffic(nowMs: Long) {
        val simAircraft = FlightRepository.simulator.snapshot(nowMs)
        val combined = lastLiveAircraft + simAircraft
        val detection = FlightRepository.detector.processSnapshot(combined, nowMs)

        FlightRepository.updateSnapshot(
            FeedSnapshot(
                updatedAtMs = nowMs,
                provider = lastProvider,
                providerOk = lastProviderOk,
                providerError = lastProviderError,
                activeAircraft = detection.activeAircraft
            )
        )

        val count = detection.activeAircraft.size
        val statusText = if (lastProviderOk) {
            "Activ ($lastProvider) • $count aeronave în zonă"
        } else if (simAircraft.isNotEmpty()) {
            "Simulare activă • $count aeronave pe radar"
        } else {
            "Reconectare sursă ADS-B…"
        }
        updateMonitorNotification(statusText)

        val settings = FlightRepository.settings.value
        for (rawEvt in detection.events) {
            val shouldNotify = when (rawEvt.type) {
                FlightEventType.LANDING -> settings.notifyLanding
                FlightEventType.TAKEOFF -> settings.notifyTakeoff
                FlightEventType.APPROACH -> settings.notifyApproach
                FlightEventType.GO_AROUND -> settings.notifyGoAround
                FlightEventType.EMERGENCY -> when (rawEvt.emergencyCode ?: rawEvt.squawk) {
                    "7700" -> settings.notifyEmergency7700
                    "PAN" -> settings.notifyEmergencyPan
                    "7600" -> settings.notifyEmergency7600
                    "7500" -> settings.notifyEmergency7500
                    else -> settings.anyEmergencyEnabled
                }
            }
            if (!shouldNotify) continue

            var enriched = if (rawEvt.simulated) rawEvt else AdsbClient.enrichEvent(rawEvt)
            if (enriched.type == FlightEventType.GO_AROUND) {
                val liveCond = runCatching {
                    ro.constanta.flightnotifier.core.RunwayStatusClient.fetchLiveRunwayConditions()
                }.getOrNull()
                val localNote = FlightRepository.getLocalRunwayNote()
                val otherOnRunway = detection.activeAircraft.any { other ->
                    other.hex != enriched.hex && other.onGround && other.distKm <= 3.0
                } || FlightRepository.events.value.any { prev ->
                    prev.hex != enriched.hex &&
                        (prev.type == FlightEventType.LANDING || prev.type == FlightEventType.TAKEOFF) &&
                        (nowMs - prev.timestampMs) in 0L..180_000L
                }
                val summary = ro.constanta.flightnotifier.core.RunwayStatusClient.analyzeGoAroundReason(
                    runway = enriched.runway,
                    minApproachAltFt = enriched.minApproachAltFt,
                    conditions = liveCond,
                    localOperationalState = localNote.operationalState,
                    localRwyccCode = localNote.rwyccCode,
                    localNote = localNote.note,
                    recentRunwayTraffic = otherOnRunway
                )
                enriched = enriched.copy(goAroundSummary = summary)
            }
            FlightRepository.addEvent(enriched)
            NotificationHelper.notifyFlightEvent(this, enriched)
        }
    }

    override fun onDestroy() {
        FlightRepository.setMonitoring(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "ro.constanta.flightnotifier.ACTION_START"
        const val ACTION_STOP = "ro.constanta.flightnotifier.ACTION_STOP"
        const val ACTION_POLL_NOW = "ro.constanta.flightnotifier.ACTION_POLL_NOW"
        const val ACTION_SIMULATE = "ro.constanta.flightnotifier.ACTION_SIMULATE"
        const val EXTRA_SIM_KIND = "sim_kind"
        const val EXTRA_SIM_RUNWAY = "sim_runway"

        /**
         * Interoghează sursele configurate pentru zona Dobrogei (DIMAP-API + UkraineAlarm Izmail/Odesa),
         * actualizează snapshot-ul live și emite notificări push pentru țintele <= pragul configurat (implicit 10 km).
         */
        fun pollDronesAndNotify(context: Context) {
            FlightRepository.init(context)
            NotificationHelper.ensureChannels(context)
            val settings = FlightRepository.settings.value
            val nowMs = System.currentTimeMillis()
            val snap = DobrogeaDroneMonitor.pollDobrogeaSources(
                dimapApiUrl = settings.dimapApiUrl,
                dimapApiKey = settings.dimapApiKey,
                ukraineAlarmApiKey = settings.ukraineAlarmApiKey,
                alertRadiusKm = settings.droneAlertRadiusKm,
                nowMs = nowMs
            )
            FlightRepository.updateDroneSnapshot(snap)

            if (!settings.notifyDroneDobrogea) return

            val freshDrones = FlightRepository.droneTracker.filterNewDroneAlerts(snap.alertTargets, nowMs)
            for (target in freshDrones) {
                val title = if (target.insideDobrogea) {
                    "DRONĂ ÎN SPAȚIUL AERIAN DOBROGEA"
                } else {
                    "DRONĂ LA ${target.distToDobrogeaKm} KM DE DOBROGEA"
                }
                val details = buildString {
                    append("${target.type} • Reper: ${target.nearestLandmark}")
                    append(" • ${target.distToCndKm} km ${target.bearingFromCnd} de CND")
                    if (target.courseDeg != null) append(" • Curs ${target.courseDeg}°")
                    if (target.label.isNotBlank()) append(" • ${target.label}")
                }
                FlightRepository.addDroneAlert(
                    DroneAlertRecord(
                        id = "dr_${target.id}_$nowMs",
                        timestampMs = nowMs,
                        title = title,
                        details = details,
                        distToDobrogeaKm = target.distToDobrogeaKm,
                        insideDobrogea = target.insideDobrogea,
                        landmark = target.nearestLandmark,
                        source = target.source,
                        simulated = target.simulated
                    )
                )
                NotificationHelper.notifyDobrogeaDroneAlert(context, target, settings.droneAlertRadiusKm)
            }

            val highPriorityBorderAlarms = snap.activeBorderAlarms.filter { alarm ->
                alarm.regionName.contains("Izmail", ignoreCase = true) ||
                    !alarm.alertType.contains("GALBEN", ignoreCase = true)
            }
            val freshBorderAlarms = FlightRepository.droneTracker.filterNewBorderAlarms(highPriorityBorderAlarms)
            for (alarm in freshBorderAlarms) {
                FlightRepository.addDroneAlert(
                    DroneAlertRecord(
                        id = "ua_${alarm.regionId}_$nowMs",
                        timestampMs = nowMs,
                        title = "ALERTĂ AERIANĂ GRANIȚĂ DOBROGEA",
                        details = "${alarm.regionName} • Tip: ${alarm.alertType}",
                        distToDobrogeaKm = null,
                        insideDobrogea = false,
                        landmark = alarm.regionName,
                        source = alarm.source,
                        simulated = false
                    )
                )
                NotificationHelper.notifyDobrogeaBorderAlarm(context, alarm)
            }
        }

        /**
         * Simulează o dronă care se apropie la < 10 km de Dobrogea (sau intră în spațiul aerian)
         * pentru verificarea notificării push, a calculului de distanță și a bannerului live.
         */
        fun triggerSimulatedDobrogeaDrone(context: Context, scenario: String = "izmail") {
            FlightRepository.init(context)
            NotificationHelper.ensureChannels(context)
            val nowMs = System.currentTimeMillis()
            val settings = FlightRepository.settings.value
            val simTarget = DobrogeaDroneMonitor.createSimulatedDobrogeaDrone(scenario, nowMs)

            val prev = FlightRepository.droneSnapshot.value
            val nextNearby = (listOf(simTarget) + prev.nearbyTargets.filterNot { it.simulated })
                .sortedBy { it.distToDobrogeaKm }
            val nextAlerts = nextNearby.filter {
                it.insideDobrogea || it.distToDobrogeaKm <= settings.droneAlertRadiusKm.toDouble()
            }
            FlightRepository.updateDroneSnapshot(
                prev.copy(
                    updatedAtMs = nowMs,
                    nearbyTargets = nextNearby,
                    alertTargets = nextAlerts
                )
            )

            val title = if (simTarget.insideDobrogea) {
                "DRONĂ ÎN DOBROGEA [SIMULARE]"
            } else {
                "DRONĂ LA ${simTarget.distToDobrogeaKm} KM DE DOBROGEA [SIMULARE]"
            }
            val details = buildString {
                append("${simTarget.type} • Reper: ${simTarget.nearestLandmark}")
                append(" • ${simTarget.distToCndKm} km ${simTarget.bearingFromCnd} de CND")
                if (simTarget.courseDeg != null) append(" • Curs ${simTarget.courseDeg}°")
                if (simTarget.label.isNotBlank()) append(" • ${simTarget.label}")
            }
            FlightRepository.addDroneAlert(
                DroneAlertRecord(
                    id = "sim_${simTarget.id}_$nowMs",
                    timestampMs = nowMs,
                    title = title,
                    details = details,
                    distToDobrogeaKm = simTarget.distToDobrogeaKm,
                    insideDobrogea = simTarget.insideDobrogea,
                    landmark = simTarget.nearestLandmark,
                    source = "SIMULARE",
                    simulated = true
                )
            )
            NotificationHelper.notifyDobrogeaDroneAlert(context, simTarget, settings.droneAlertRadiusKm)
        }
    }
}
