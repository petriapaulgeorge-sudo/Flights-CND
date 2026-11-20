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
import ro.constanta.flightnotifier.data.FlightRepository
import ro.constanta.flightnotifier.model.FeedSnapshot
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.model.RawAircraft
import ro.constanta.flightnotifier.notify.NotificationHelper

class FlightMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
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
                startForegroundSafe("Monitorizare ADS-B activă în jurul LRCK…")
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

    private fun pollOnce() {
        val settings = FlightRepository.settings.value
        val result = AdsbClient.fetchLiveAircraft(settings.pollRadiusNm)
        if (result.ok) {
            lastLiveAircraft = result.aircraft
            lastProvider = result.provider
            lastProviderOk = true
            lastProviderError = null
        } else {
            lastProviderOk = false
            lastProviderError = result.error
        }
        processCombinedTraffic(System.currentTimeMillis())
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
            }
            if (!shouldNotify) continue

            val enriched = if (rawEvt.simulated) rawEvt else AdsbClient.enrichEvent(rawEvt)
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
    }
}
