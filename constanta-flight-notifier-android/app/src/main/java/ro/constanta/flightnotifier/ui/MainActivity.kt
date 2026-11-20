package ro.constanta.flightnotifier.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch
import ro.constanta.flightnotifier.R
import ro.constanta.flightnotifier.data.FlightRepository
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.notify.NotificationHelper
import ro.constanta.flightnotifier.service.FlightMonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var radarView: RadarCanvasView
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnToggleMonitor: Button
    private lateinit var swLanding: SwitchMaterial
    private lateinit var swTakeoff: SwitchMaterial
    private lateinit var swApproach: SwitchMaterial
    private lateinit var adapter: EventsAdapter

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMonitorService()
        } else {
            Toast.makeText(
                this,
                "Permisiunea de notificări este necesară pentru alertele de zbor.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        FlightRepository.init(this)
        NotificationHelper.ensureChannels(this)

        radarView = findViewById(R.id.radar_view)
        tvStatus = findViewById(R.id.tv_status)
        tvStats = findViewById(R.id.tv_stats)
        tvEmpty = findViewById(R.id.tv_empty)
        btnToggleMonitor = findViewById(R.id.btn_toggle_monitor)
        swLanding = findViewById(R.id.switch_landing)
        swTakeoff = findViewById(R.id.switch_takeoff)
        swApproach = findViewById(R.id.switch_approach)

        val recycler: RecyclerView = findViewById(R.id.rv_events)
        adapter = EventsAdapter { evt ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(evt.adsbExchangeUrl)))
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val s = FlightRepository.settings.value
        swLanding.isChecked = s.notifyLanding
        swTakeoff.isChecked = s.notifyTakeoff
        swApproach.isChecked = s.notifyApproach

        val saveToggle = {
            FlightRepository.updateSettings(
                FlightRepository.settings.value.copy(
                    notifyLanding = swLanding.isChecked,
                    notifyTakeoff = swTakeoff.isChecked,
                    notifyApproach = swApproach.isChecked
                )
            )
        }
        swLanding.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swTakeoff.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swApproach.setOnCheckedChangeListener { _, _ -> saveToggle() }

        btnToggleMonitor.setOnClickListener {
            if (FlightRepository.isMonitoring.value) {
                stopMonitorService()
            } else {
                ensureNotificationPermissionAndStart()
            }
        }

        findViewById<Button>(R.id.btn_sim_landing).setOnClickListener {
            ensureNotificationPermissionAndAction(
                Intent(this, FlightMonitorService::class.java).apply {
                    action = FlightMonitorService.ACTION_SIMULATE
                    putExtra(FlightMonitorService.EXTRA_SIM_KIND, "landing")
                    putExtra(FlightMonitorService.EXTRA_SIM_RUNWAY, "18")
                }
            )
        }

        findViewById<Button>(R.id.btn_sim_takeoff).setOnClickListener {
            ensureNotificationPermissionAndAction(
                Intent(this, FlightMonitorService::class.java).apply {
                    action = FlightMonitorService.ACTION_SIMULATE
                    putExtra(FlightMonitorService.EXTRA_SIM_KIND, "takeoff")
                    putExtra(FlightMonitorService.EXTRA_SIM_RUNWAY, "36")
                }
            )
        }

        findViewById<Button>(R.id.btn_clear_events).setOnClickListener {
            FlightRepository.clearEvents()
        }

        observeState()
    }

    private fun observeState() {
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale("ro", "RO"))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    FlightRepository.isMonitoring.collect { running ->
                        btnToggleMonitor.text = if (running) {
                            getString(R.string.btn_stop_monitor)
                        } else {
                            getString(R.string.btn_start_monitor)
                        }
                    }
                }
                launch {
                    FlightRepository.snapshot.collect { snap ->
                        radarView.updateAircraft(snap.activeAircraft)
                        val updated = snap.updatedAtMs?.let { timeFmt.format(Date(it)) } ?: "—"
                        tvStatus.text = when {
                            snap.providerOk -> "Live (${snap.provider}) • ${snap.activeAircraft.size} pe radar • $updated"
                            snap.providerError != null -> "Sursă ADS-B indisponibilă temporar • $updated"
                            FlightRepository.isMonitoring.value -> "Se conectează la fluxul ADS-B…"
                            else -> "Monitorizare oprită. Apasă „Pornește monitorizarea 24/7”."
                        }
                    }
                }
                launch {
                    FlightRepository.events.collect { list ->
                        adapter.submitList(list)
                        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        val landings = list.count { it.type == FlightEventType.LANDING }
                        val takeoffs = list.count { it.type == FlightEventType.TAKEOFF }
                        tvStats.text = "Aterizări: $landings  •  Decolări: $takeoffs"
                    }
                }
            }
        }
    }

    private fun ensureNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMonitorService()
        }
    }

    private fun ensureNotificationPermissionAndAction(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startMonitorService() {
        val intent = Intent(this, FlightMonitorService::class.java).apply {
            action = FlightMonitorService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopMonitorService() {
        val intent = Intent(this, FlightMonitorService::class.java).apply {
            action = FlightMonitorService.ACTION_STOP
        }
        startService(intent)
    }
}
