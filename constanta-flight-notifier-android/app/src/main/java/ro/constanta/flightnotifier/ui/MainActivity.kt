package ro.constanta.flightnotifier.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.constanta.flightnotifier.R
import ro.constanta.flightnotifier.core.EventSearch
import ro.constanta.flightnotifier.core.PdfExporter
import ro.constanta.flightnotifier.data.FlightRepository
import ro.constanta.flightnotifier.model.EventSearchFilter
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.notify.NotificationHelper
import ro.constanta.flightnotifier.service.FlightMonitorService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var radarView: RadarCanvasView
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvEmptyRecent: TextView
    private lateinit var tvHistorySummary: TextView
    private lateinit var tvEmptyHistory: TextView
    private lateinit var btnToggleMonitor: Button
    private lateinit var swLanding: SwitchMaterial
    private lateinit var swTakeoff: SwitchMaterial
    private lateinit var swApproach: SwitchMaterial
    private lateinit var tabLayout: TabLayout
    private lateinit var paneRadar: View
    private lateinit var paneHistory: View

    private lateinit var etSearchQuery: TextInputEditText
    private lateinit var chipGroupType: ChipGroup
    private lateinit var btnFromDate: Button
    private lateinit var btnFromTime: Button
    private lateinit var btnToDate: Button
    private lateinit var btnToTime: Button

    private lateinit var recentAdapter: EventsAdapter
    private lateinit var historyAdapter: EventsAdapter

    private var allEvents: List<FlightEvent> = emptyList()
    private var filteredEvents: List<FlightEvent> = emptyList()
    private var currentFilter = EventSearchFilter()
    private var fromCal: Calendar? = null
    private var toCal: Calendar? = null

    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale("ro", "RO"))
    private val timeFmt = SimpleDateFormat("HH:mm", Locale("ro", "RO"))

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

    private val createPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            exportFilteredEventsToPdf(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applySafeSystemBarInsets()

        FlightRepository.init(this)
        NotificationHelper.ensureChannels(this)

        bindViews()
        setupTabs()
        setupMonitorControls()
        setupHistorySearchControls()
        observeState()
    }

    /**
     * Coboară automat conținutul sub bara de stare (status bar + cameră/notch) și deasupra barei
     * de navigare Android, inclusiv pe Android 15 (edge-to-edge obligatoriu).
     */
    private fun applySafeSystemBarInsets() {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }

        val root = findViewById<View>(R.id.root_container)
        val extraTopPx = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                insets.left,
                insets.top + extraTopPx,
                insets.right,
                insets.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun bindViews() {
        radarView = findViewById(R.id.radar_view)
        tvStatus = findViewById(R.id.tv_status)
        tvStats = findViewById(R.id.tv_stats)
        tvEmptyRecent = findViewById(R.id.tv_empty_recent)
        tvHistorySummary = findViewById(R.id.tv_history_summary)
        tvEmptyHistory = findViewById(R.id.tv_empty_history)
        btnToggleMonitor = findViewById(R.id.btn_toggle_monitor)
        swLanding = findViewById(R.id.switch_landing)
        swTakeoff = findViewById(R.id.switch_takeoff)
        swApproach = findViewById(R.id.switch_approach)
        tabLayout = findViewById(R.id.tab_layout)
        paneRadar = findViewById(R.id.pane_radar)
        paneHistory = findViewById(R.id.pane_history)

        etSearchQuery = findViewById(R.id.et_search_query)
        chipGroupType = findViewById(R.id.chip_group_type)
        btnFromDate = findViewById(R.id.btn_from_date)
        btnFromTime = findViewById(R.id.btn_from_time)
        btnToDate = findViewById(R.id.btn_to_date)
        btnToTime = findViewById(R.id.btn_to_time)

        val openFlight: (FlightEvent) -> Unit = { evt ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(evt.adsbExchangeUrl)))
        }

        val rvRecent: RecyclerView = findViewById(R.id.rv_recent_events)
        recentAdapter = EventsAdapter(openFlight)
        rvRecent.layoutManager = LinearLayoutManager(this)
        rvRecent.adapter = recentAdapter

        val rvHistory: RecyclerView = findViewById(R.id.rv_history_events)
        historyAdapter = EventsAdapter(openFlight)
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val showHistory = tab.position == 1
                paneRadar.visibility = if (showHistory) View.GONE else View.VISIBLE
                paneHistory.visibility = if (showHistory) View.VISIBLE else View.GONE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        findViewById<Button>(R.id.btn_open_history).setOnClickListener {
            tabLayout.getTabAt(1)?.select()
        }
    }

    private fun setupMonitorControls() {
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
    }

    private fun setupHistorySearchControls() {
        etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                currentFilter = currentFilter.copy(query = s?.toString().orEmpty())
                applyHistoryFilter()
            }
        })

        chipGroupType.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedType = when (checkedIds.firstOrNull()) {
                R.id.chip_type_landing -> FlightEventType.LANDING
                R.id.chip_type_takeoff -> FlightEventType.TAKEOFF
                else -> null
            }
            currentFilter = currentFilter.copy(type = selectedType)
            applyHistoryFilter()
        }

        findViewById<Button>(R.id.btn_preset_today).setOnClickListener {
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val end = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }
            setDateRange(start, end)
        }

        findViewById<Button>(R.id.btn_preset_24h).setOnClickListener {
            val end = Calendar.getInstance()
            val start = (end.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, -24) }
            setDateRange(start, end)
        }

        findViewById<Button>(R.id.btn_preset_7d).setOnClickListener {
            val end = Calendar.getInstance()
            val start = (end.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
            setDateRange(start, end)
        }

        findViewById<Button>(R.id.btn_preset_all).setOnClickListener {
            setDateRange(null, null)
        }

        btnFromDate.setOnClickListener { pickDate(isFrom = true) }
        btnFromTime.setOnClickListener { pickTime(isFrom = true) }
        btnToDate.setOnClickListener { pickDate(isFrom = false) }
        btnToTime.setOnClickListener { pickTime(isFrom = false) }

        findViewById<Button>(R.id.btn_reset_filters).setOnClickListener {
            etSearchQuery.setText("")
            chipGroupType.check(R.id.chip_type_all)
            fromCal = null
            toCal = null
            currentFilter = EventSearchFilter()
            updateIntervalButtons()
            applyHistoryFilter()
        }

        findViewById<Button>(R.id.btn_export_pdf).setOnClickListener {
            if (filteredEvents.isEmpty()) {
                Toast.makeText(
                    this,
                    "Nu există rezultate de exportat pentru filtrul curent.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            createPdfLauncher.launch("Raport_Zboruri_Constanta_$stamp.pdf")
        }

        findViewById<Button>(R.id.btn_clear_events).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Golești istoricul?")
                .setMessage("Toate aterizările și decolările salvate pe telefon vor fi șterse.")
                .setPositiveButton("Șterge") { _, _ -> FlightRepository.clearEvents() }
                .setNegativeButton("Anulează", null)
                .show()
        }

        updateIntervalButtons()
    }

    private fun setDateRange(start: Calendar?, end: Calendar?) {
        fromCal = start
        toCal = end
        currentFilter = currentFilter.copy(
            fromMs = start?.timeInMillis,
            toMs = end?.timeInMillis
        )
        updateIntervalButtons()
        applyHistoryFilter()
    }

    private fun pickDate(isFrom: Boolean) {
        val base = (if (isFrom) fromCal else toCal)?.clone() as? Calendar
            ?: Calendar.getInstance().apply {
                if (isFrom) {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                } else {
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }
            }

        DatePickerDialog(
            this,
            { _, year, month, day ->
                base.set(Calendar.YEAR, year)
                base.set(Calendar.MONTH, month)
                base.set(Calendar.DAY_OF_MONTH, day)
                if (isFrom) {
                    fromCal = base
                    currentFilter = currentFilter.copy(fromMs = base.timeInMillis)
                } else {
                    toCal = base
                    currentFilter = currentFilter.copy(toMs = base.timeInMillis)
                }
                updateIntervalButtons()
                applyHistoryFilter()
            },
            base.get(Calendar.YEAR),
            base.get(Calendar.MONTH),
            base.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pickTime(isFrom: Boolean) {
        val base = (if (isFrom) fromCal else toCal)?.clone() as? Calendar
            ?: Calendar.getInstance()

        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                base.set(Calendar.HOUR_OF_DAY, hourOfDay)
                base.set(Calendar.MINUTE, minute)
                base.set(Calendar.SECOND, if (isFrom) 0 else 59)
                base.set(Calendar.MILLISECOND, if (isFrom) 0 else 999)
                if (isFrom) {
                    fromCal = base
                    currentFilter = currentFilter.copy(fromMs = base.timeInMillis)
                } else {
                    toCal = base
                    currentFilter = currentFilter.copy(toMs = base.timeInMillis)
                }
                updateIntervalButtons()
                applyHistoryFilter()
            },
            base.get(Calendar.HOUR_OF_DAY),
            base.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun updateIntervalButtons() {
        val f = fromCal
        val t = toCal
        btnFromDate.text = if (f != null) dateFmt.format(f.time) else getString(R.string.btn_pick_date)
        btnFromTime.text = if (f != null) timeFmt.format(f.time) else getString(R.string.btn_pick_time)
        btnToDate.text = if (t != null) dateFmt.format(t.time) else getString(R.string.btn_pick_date)
        btnToTime.text = if (t != null) timeFmt.format(t.time) else getString(R.string.btn_pick_time)
    }

    private fun applyHistoryFilter() {
        val matches = EventSearch.filterEvents(allEvents, currentFilter)
        filteredEvents = matches
        historyAdapter.submitList(matches)
        tvEmptyHistory.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE

        val landings = matches.count { it.type == FlightEventType.LANDING }
        val takeoffs = matches.count { it.type == FlightEventType.TAKEOFF }
        tvHistorySummary.text = "Rezultate: ${matches.size} (${landings} aterizări, ${takeoffs} decolări)"
    }

    private fun exportFilteredEventsToPdf(uri: Uri) {
        val snapshotEvents = filteredEvents.toList()
        val snapshotFilter = currentFilter
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        PdfExporter.exportToStream(out, snapshotEvents, snapshotFilter)
                    } ?: error("Nu s-a putut deschide fișierul pentru scriere.")
                }
            }
            if (result.isSuccess) {
                Toast.makeText(
                    this@MainActivity,
                    "PDF salvat (${snapshotEvents.size} zboruri).",
                    Toast.LENGTH_LONG
                ).show()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Raport PDF exportat")
                    .setMessage("Fișierul PDF cu ${snapshotEvents.size} zboruri a fost salvat. Vrei să îl deschizi acum?")
                    .setPositiveButton("Deschide PDF") { _, _ ->
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { startActivity(viewIntent) }.onFailure {
                            Toast.makeText(
                                this@MainActivity,
                                "Nu există o aplicație instalată pentru vizualizare PDF.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .setNegativeButton("Închide", null)
                    .show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Eroare la export PDF: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun observeState() {
        val clockFmt = SimpleDateFormat("HH:mm:ss", Locale("ro", "RO"))
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
                        val updated = snap.updatedAtMs?.let { clockFmt.format(Date(it)) } ?: "—"
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
                        allEvents = list
                        val recent = list.take(5)
                        recentAdapter.submitList(recent)
                        tvEmptyRecent.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        val landings = list.count { it.type == FlightEventType.LANDING }
                        val takeoffs = list.count { it.type == FlightEventType.TAKEOFF }
                        tvStats.text = "Total salvate: ${list.size} • Aterizări: $landings • Decolări: $takeoffs"
                        applyHistoryFilter()
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
