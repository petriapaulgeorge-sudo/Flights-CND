package ro.constanta.flightnotifier.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.constanta.flightnotifier.R
import ro.constanta.flightnotifier.core.AdsbClient
import ro.constanta.flightnotifier.core.AircraftRouteDetails
import ro.constanta.flightnotifier.core.AirportConfig
import ro.constanta.flightnotifier.core.DailyReportBuilder
import ro.constanta.flightnotifier.core.DailyReportPdfExporter
import ro.constanta.flightnotifier.core.EventSearch
import ro.constanta.flightnotifier.core.PdfExporter
import ro.constanta.flightnotifier.core.RunwayConditions
import ro.constanta.flightnotifier.core.RunwayStatusClient
import ro.constanta.flightnotifier.data.FlightRepository
import ro.constanta.flightnotifier.data.LocalRunwayNote
import ro.constanta.flightnotifier.model.ActiveAircraft
import ro.constanta.flightnotifier.model.DailyFlightEntry
import ro.constanta.flightnotifier.model.DailyReportData
import ro.constanta.flightnotifier.model.EventSearchFilter
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.model.ProhibitedItems
import ro.constanta.flightnotifier.notify.NotificationHelper
import ro.constanta.flightnotifier.service.FlightMonitorService
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var radarView: RadarCanvasView
    private lateinit var tvEmergencyBanner: TextView
    private lateinit var tvSelectedAircraft: TextView
    private lateinit var chipGroupZoom: ChipGroup
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvEmptyRecent: TextView
    private lateinit var tvHistorySummary: TextView
    private lateinit var tvEmptyHistory: TextView
    private lateinit var btnToggleMonitor: Button
    private lateinit var swLanding: SwitchMaterial
    private lateinit var swTakeoff: SwitchMaterial
    private lateinit var swApproach: SwitchMaterial
    private lateinit var swEmg7700: SwitchMaterial
    private lateinit var swEmgPan: SwitchMaterial
    private lateinit var swEmg7600: SwitchMaterial
    private lateinit var swEmg7500: SwitchMaterial
    private lateinit var btnDeleteRecentSim: Button
    private lateinit var tabLayout: TabLayout
    private lateinit var paneRadar: View
    private lateinit var paneRunway: View
    private lateinit var paneHistory: View
    private lateinit var paneReport: View
    private lateinit var paneAbout: View

    // Stare pistă
    private lateinit var tvRwyFlightCat: TextView
    private lateinit var tvRwyObsTime: TextView
    private lateinit var tvRwySurface: TextView
    private lateinit var tvRwyRecommended: TextView
    private lateinit var tvRwyDetails: TextView
    private lateinit var tvRwyRawMetar: TextView
    private lateinit var chipGroupRwyState: ChipGroup
    private lateinit var chipGroupRwycc: ChipGroup
    private lateinit var etRunwayNote: TextInputEditText
    private var runwayLoadedOnce = false

    // Istoric & căutare
    private lateinit var etSearchQuery: TextInputEditText
    private lateinit var chipGroupType: ChipGroup
    private lateinit var btnFromDate: Button
    private lateinit var btnFromTime: Button
    private lateinit var btnToDate: Button
    private lateinit var btnToTime: Button
    private lateinit var btnSyncMissed: Button
    private lateinit var btnDeleteHistorySim: Button
    private lateinit var btnDeleteAllSim: Button

    // Raport zilnic
    private lateinit var tvReportDate: TextView
    private lateinit var chipGroupSupervisor: ChipGroup
    private lateinit var tilOtherSupervisor: View
    private lateinit var etOtherSupervisor: TextInputEditText
    private lateinit var tvReportEmptyFlights: TextView
    private lateinit var reportFlightsContainer: LinearLayout
    private lateinit var chkShooting: CheckBox
    private lateinit var chkSecurityCommittee: CheckBox
    private lateinit var chkPnsaExemptions: CheckBox
    private lateinit var etProhProjectiles: EditText
    private lateinit var etProhStunning: EditText
    private lateinit var etProhSharp: EditText
    private lateinit var etProhTools: EditText
    private lateinit var etProhBlunt: EditText
    private lateinit var etProhExplosives: EditText
    private lateinit var tvProhTotal: TextView
    private lateinit var etReportOtherNotes: TextInputEditText

    private lateinit var recentAdapter: EventsAdapter
    private lateinit var historyAdapter: EventsAdapter

    private var allEvents: List<FlightEvent> = emptyList()
    private var filteredEvents: List<FlightEvent> = emptyList()
    private var currentFilter = EventSearchFilter()
    private var fromCal: Calendar? = null
    private var toCal: Calendar? = null

    // Aeronava selectată pe radar
    private var selectedAircraftHex: String? = null
    private var selectedAircraftDetails: AircraftRouteDetails? = null
    private var selectedAircraftDetailsJob: Job? = null

    // Stare raport zilnic
    private var reportDateCal: Calendar = Calendar.getInstance()
    private var dailyFlights: MutableList<DailyFlightEntry> = mutableListOf()
    private val manualDailyFlightsByDate = HashMap<String, MutableList<DailyFlightEntry>>()
    private val flightInputsById = HashMap<String, Triple<Int, Int, Int>>()
    private var lastRenderedDailySig: String = ""
    private var manualFlightSeq = 0

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

    private val createHistoryPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            exportFilteredEventsToPdf(uri)
        }
    }

    private val createDailyPdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            exportDailyReportToUri(uri)
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
        setupRadarControls()
        setupRunwayStatusControls()
        setupMonitorControls()
        setupHistorySearchControls()
        setupDailyReportControls()
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
        tvEmergencyBanner = findViewById(R.id.tv_emergency_banner)
        tvSelectedAircraft = findViewById(R.id.tv_selected_aircraft)
        chipGroupZoom = findViewById(R.id.chip_group_zoom)
        tvStatus = findViewById(R.id.tv_status)
        tvStats = findViewById(R.id.tv_stats)
        tvEmptyRecent = findViewById(R.id.tv_empty_recent)
        tvHistorySummary = findViewById(R.id.tv_history_summary)
        tvEmptyHistory = findViewById(R.id.tv_empty_history)
        btnToggleMonitor = findViewById(R.id.btn_toggle_monitor)
        swLanding = findViewById(R.id.switch_landing)
        swTakeoff = findViewById(R.id.switch_takeoff)
        swApproach = findViewById(R.id.switch_approach)
        swEmg7700 = findViewById(R.id.switch_emg_7700)
        swEmgPan = findViewById(R.id.switch_emg_pan)
        swEmg7600 = findViewById(R.id.switch_emg_7600)
        swEmg7500 = findViewById(R.id.switch_emg_7500)
        btnDeleteRecentSim = findViewById(R.id.btn_delete_recent_sim)
        tabLayout = findViewById(R.id.tab_layout)
        paneRadar = findViewById(R.id.pane_radar)
        paneRunway = findViewById(R.id.pane_runway)
        paneHistory = findViewById(R.id.pane_history)
        paneReport = findViewById(R.id.pane_report)
        paneAbout = findViewById(R.id.pane_about)

        tvRwyFlightCat = findViewById(R.id.tv_rwy_flight_cat)
        tvRwyObsTime = findViewById(R.id.tv_rwy_obs_time)
        tvRwySurface = findViewById(R.id.tv_rwy_surface)
        tvRwyRecommended = findViewById(R.id.tv_rwy_recommended)
        tvRwyDetails = findViewById(R.id.tv_rwy_details)
        tvRwyRawMetar = findViewById(R.id.tv_rwy_raw_metar)
        chipGroupRwyState = findViewById(R.id.chip_group_rwy_state)
        chipGroupRwycc = findViewById(R.id.chip_group_rwycc)
        etRunwayNote = findViewById(R.id.et_runway_note)

        etSearchQuery = findViewById(R.id.et_search_query)
        chipGroupType = findViewById(R.id.chip_group_type)
        btnFromDate = findViewById(R.id.btn_from_date)
        btnFromTime = findViewById(R.id.btn_from_time)
        btnToDate = findViewById(R.id.btn_to_date)
        btnToTime = findViewById(R.id.btn_to_time)
        btnSyncMissed = findViewById(R.id.btn_sync_missed)
        btnDeleteHistorySim = findViewById(R.id.btn_delete_history_sim)
        btnDeleteAllSim = findViewById(R.id.btn_delete_all_sim)

        tvReportDate = findViewById(R.id.tv_report_date)
        chipGroupSupervisor = findViewById(R.id.chip_group_supervisor)
        tilOtherSupervisor = findViewById(R.id.til_other_supervisor)
        etOtherSupervisor = findViewById(R.id.et_other_supervisor)
        tvReportEmptyFlights = findViewById(R.id.tv_report_empty_flights)
        reportFlightsContainer = findViewById(R.id.report_flights_container)
        chkShooting = findViewById(R.id.chk_shooting)
        chkSecurityCommittee = findViewById(R.id.chk_security_committee)
        chkPnsaExemptions = findViewById(R.id.chk_pnsa_exemptions)
        etProhProjectiles = findViewById(R.id.et_proh_projectiles)
        etProhStunning = findViewById(R.id.et_proh_stunning)
        etProhSharp = findViewById(R.id.et_proh_sharp)
        etProhTools = findViewById(R.id.et_proh_tools)
        etProhBlunt = findViewById(R.id.et_proh_blunt)
        etProhExplosives = findViewById(R.id.et_proh_explosives)
        tvProhTotal = findViewById(R.id.tv_proh_total)
        etReportOtherNotes = findViewById(R.id.et_report_other_notes)

        val openFlight: (FlightEvent) -> Unit = { evt ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(evt.adsbExchangeUrl)))
        }

        val rvRecent: RecyclerView = findViewById(R.id.rv_recent_events)
        recentAdapter = EventsAdapter(openFlight) { selectedCount ->
            btnDeleteRecentSim.visibility = if (selectedCount > 0) View.VISIBLE else View.GONE
            if (selectedCount > 0) {
                btnDeleteRecentSim.text = "Șterge simulate selectate ($selectedCount)"
            }
        }
        rvRecent.layoutManager = LinearLayoutManager(this)
        rvRecent.adapter = recentAdapter

        val rvHistory: RecyclerView = findViewById(R.id.rv_history_events)
        historyAdapter = EventsAdapter(openFlight) { selectedCount ->
            btnDeleteHistorySim.visibility = if (selectedCount > 0) View.VISIBLE else View.GONE
            if (selectedCount > 0) {
                btnDeleteHistorySim.text = "Șterge simulate selectate ($selectedCount)"
            }
        }
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                paneRadar.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                paneRunway.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
                paneHistory.visibility = if (tab.position == 2) View.VISIBLE else View.GONE
                paneReport.visibility = if (tab.position == 3) View.VISIBLE else View.GONE
                paneAbout.visibility = if (tab.position == 4) View.VISIBLE else View.GONE
                if (tab.position == 1 && !runwayLoadedOnce) {
                    refreshRunwayConditions()
                }
                if (tab.position == 3) {
                    refreshDailyReportFlights(forceRender = false)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        findViewById<Button>(R.id.btn_open_history).setOnClickListener {
            tabLayout.getTabAt(2)?.select()
        }
    }

    private fun setupRadarControls() {
        chipGroupZoom.setOnCheckedStateChangeListener { _, checkedIds ->
            val range = when (checkedIds.firstOrNull()) {
                R.id.chip_zoom_15 -> 15.0
                R.id.chip_zoom_45 -> 45.0
                R.id.chip_zoom_100 -> 100.0
                else -> 25.0
            }
            radarView.setRangeKm(range)
        }

        radarView.setOnAircraftSelectedListener { ac: ActiveAircraft? ->
            selectedAircraftDetailsJob?.cancel()
            if (ac == null) {
                selectedAircraftHex = null
                selectedAircraftDetails = null
                tvSelectedAircraft.visibility = View.GONE
            } else {
                selectedAircraftHex = ac.hex
                val cached = AdsbClient.getCachedAircraftRouteDetails(ac.hex, ac.callsign)
                selectedAircraftDetails = cached
                renderSelectedAircraftPanel(ac, cached, loading = cached == null)

                if (cached == null) {
                    val targetHex = ac.hex
                    val targetCallsign = ac.callsign
                    val hintAirline = ac.airlineHint
                    val hintRoute = ac.routeHint
                    selectedAircraftDetailsJob = lifecycleScope.launch {
                        val fetched = withContext(Dispatchers.IO) {
                            AdsbClient.fetchAircraftRouteDetails(
                                hex = targetHex,
                                callsign = targetCallsign,
                                airlineHint = hintAirline,
                                routeHint = hintRoute
                            )
                        }
                        if (selectedAircraftHex == targetHex) {
                            selectedAircraftDetails = fetched
                            val latestAc = FlightRepository.snapshot.value.activeAircraft
                                .firstOrNull { it.hex == targetHex } ?: ac
                            renderSelectedAircraftPanel(latestAc, fetched, loading = false)
                        }
                    }
                }
            }
        }
    }

    private fun renderSelectedAircraftPanel(
        ac: ActiveAircraft,
        details: AircraftRouteDetails?,
        loading: Boolean
    ) {
        val alt = if (ac.onGround) "LA SOL" else "${ac.altAglFt ?: "—"} ft AGL"
        val vs = ac.verticalRateFpm?.let { if (it > 0) "+$it fpm" else "$it fpm" } ?: "—"
        val model = details?.aircraftModel?.takeIf { it.isNotBlank() }
            ?: ac.aircraftType.ifBlank { ac.hex.uppercase(Locale.US) }
        val reg = details?.registration?.takeIf { it.isNotBlank() }
            ?: ac.registration.takeIf { it.isNotBlank() }
        val regPart = if (reg != null) " ($reg)" else ""
        val sqStr = if (ac.squawk.isNotBlank()) " • SQ ${ac.squawk}" else ""
        val emInfo = AirportConfig.emergencyInfo(ac.squawk, ac.emergencyStatus)
        val emTag = if (emInfo != null) " • [${emInfo.shortBadge}]" else ""

        val airlineText = details?.airline?.takeIf { it.isNotBlank() }
            ?: ac.airlineHint?.takeIf { it.isNotBlank() }
            ?: AdsbClient.resolveKnownAirline(ac.callsign)
            ?: if (loading) "Se caută…" else "Indisponibilă în baza ADS-B"

        val originText = details?.origin?.takeIf { it.isNotBlank() }
            ?: if (loading) "Se caută…" else "Indisponibilă (fără plan de zbor public)"

        val destText = details?.destination?.takeIf { it.isNotBlank() }
            ?: if (loading) "Se caută…" else "Indisponibilă (fără plan de zbor public)"

        tvSelectedAircraft.text = buildString {
            append("${ac.callsign} • $model$regPart$sqStr$emTag\n")
            append("Companie: $airlineText\n")
            append("Origine: $originText\n")
            append("Destinație: $destText\n")
            append("Telemetrie: $alt • ${ac.gsKt ?: "—"} kt • V/S $vs • ${ac.distKm} km de LRCK")
        }
        tvSelectedAircraft.visibility = View.VISIBLE
    }

    // =========================================================================
    // TAB 2: STARE PISTĂ LRCK (METAR/TAF LIVE & EVALUARE RWYCC)
    // =========================================================================
    private fun setupRunwayStatusControls() {
        val saved = FlightRepository.getLocalRunwayNote()
        etRunwayNote.setText(saved.note)

        val stateChipId = when (saved.operationalState) {
            "Atenție meteo" -> R.id.chip_rwy_caution
            "Restricționată" -> R.id.chip_rwy_restricted
            "Închisă" -> R.id.chip_rwy_closed
            else -> R.id.chip_rwy_open
        }
        chipGroupRwyState.check(stateChipId)

        val rwyccChipId = when {
            saved.rwyccCode.startsWith("5") -> R.id.chip_rwycc_5
            saved.rwyccCode.startsWith("4") -> R.id.chip_rwycc_4
            saved.rwyccCode.startsWith("3") -> R.id.chip_rwycc_3
            saved.rwyccCode.startsWith("2") -> R.id.chip_rwycc_2
            saved.rwyccCode.startsWith("1") -> R.id.chip_rwycc_1
            saved.rwyccCode.startsWith("0") -> R.id.chip_rwycc_0
            else -> R.id.chip_rwycc_6
        }
        chipGroupRwycc.check(rwyccChipId)

        findViewById<Button>(R.id.btn_refresh_runway).setOnClickListener {
            refreshRunwayConditions()
        }

        findViewById<Button>(R.id.btn_save_runway_note).setOnClickListener {
            val opState = when (chipGroupRwyState.checkedChipId) {
                R.id.chip_rwy_caution -> "Atenție meteo"
                R.id.chip_rwy_restricted -> "Restricționată"
                R.id.chip_rwy_closed -> "Închisă"
                else -> "Operațională"
            }
            val rwycc = when (chipGroupRwycc.checkedChipId) {
                R.id.chip_rwycc_5 -> "5 — Umed / Bun"
                R.id.chip_rwycc_4 -> "4 — Mediu-Bun"
                R.id.chip_rwycc_3 -> "3 — Mediu"
                R.id.chip_rwycc_2 -> "2 — Mediu-Slab"
                R.id.chip_rwycc_1 -> "1 — Slab"
                R.id.chip_rwycc_0 -> "0 — Gheață / Critic"
                else -> "6 — Uscat"
            }
            val noteText = etRunwayNote.text?.toString()?.trim().orEmpty()
            FlightRepository.saveLocalRunwayNote(LocalRunwayNote(opState, rwycc, noteText))
            Toast.makeText(this, "Starea locală a pistei a fost salvată ($opState, RWYCC $rwycc).", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshRunwayConditions() {
        runwayLoadedOnce = true
        tvRwyObsTime.text = getString(R.string.runway_loading)
        lifecycleScope.launch {
            val cond = withContext(Dispatchers.IO) {
                RunwayStatusClient.fetchLiveRunwayConditions()
            }
            renderRunwayConditions(cond)
        }
    }

    private fun renderRunwayConditions(c: RunwayConditions) {
        if (!c.ok) {
            tvRwyFlightCat.text = "N/A"
            tvRwyFlightCat.setBackgroundColor(Color.parseColor("#64748B"))
            tvRwyObsTime.text = "Nu s-a putut contacta serverul METAR"
            tvRwySurface.text = "Datele meteo live LRCK sunt indisponibile temporar."
            tvRwyRecommended.text = "Pistă recomandată: —"
            tvRwyDetails.text = c.error ?: "Verifică conexiunea la internet și apasă Actualizează."
            tvRwyRawMetar.text = c.rawMetar
            return
        }

        val catColor = when (c.flightCategory) {
            "LIFR" -> Color.parseColor("#B91C1C")
            "IFR" -> Color.parseColor("#DC2626")
            "MVFR" -> Color.parseColor("#0369A1")
            else -> Color.parseColor("#0F766E")
        }
        tvRwyFlightCat.text = c.flightCategory
        tvRwyFlightCat.setBackgroundColor(catColor)
        tvRwyObsTime.text = "${c.observationTime} (${c.source})"

        tvRwySurface.text = "Stare suprafață estimată: ${c.surfaceEstimate}"
        tvRwySurface.setTextColor(
            if (c.icingRisk || c.surfaceEstimate.contains("ATENȚIE")) Color.parseColor("#DC2626")
            else ContextCompat.getColor(this, R.color.text_primary)
        )
        tvRwyRecommended.text = "Pistă activă recomandată: ${c.recommendedRunway}"

        val windCompText = buildString {
            val w18 = c.rwy18Wind
            val w36 = c.rwy36Wind
            if (w18 != null && w36 != null) {
                append("\n• RWY 18 (180°): ${formatHeadTail(w18.headwindKt)}, lateral ${w18.crosswindKt} kt")
                append("\n• RWY 36 (360°): ${formatHeadTail(w36.headwindKt)}, lateral ${w36.crosswindKt} kt")
            }
        }

        tvRwyDetails.text = buildString {
            append("Vânt: ${c.windSummary}")
            append(windCompText)
            append("\nVizibilitate: ${c.visibilityText}")
            append("\nPlafon / Nori: ${c.ceilingText}")
            append("\nTemperatură: ${c.tempDewText}")
            if (c.icingRisk) append("  [RISC POLEI / ÎNGHEȚ]")
            append("\nPresiune QNH: ${c.qnhText}")
            append("\nFenomene meteo: ${c.weatherPhenomena}")
            append("\nRVR / Coduri pistă: ${c.rvrAndStateGroups}")
        }

        tvRwyRawMetar.text = buildString {
            append("METAR: ${c.rawMetar}")
            if (!c.rawTaf.isNullOrBlank()) {
                append("\n\nTAF: ${c.rawTaf}")
            }
        }
    }

    private fun formatHeadTail(kt: Int): String {
        return when {
            kt > 0 -> "frontal +$kt kt"
            kt < 0 -> "spate ${kt} kt"
            else -> "frontal 0 kt"
        }
    }

    private fun setupMonitorControls() {
        val s = FlightRepository.settings.value
        swLanding.isChecked = s.notifyLanding
        swTakeoff.isChecked = s.notifyTakeoff
        swApproach.isChecked = s.notifyApproach
        swEmg7700.isChecked = s.notifyEmergency7700
        swEmgPan.isChecked = s.notifyEmergencyPan
        swEmg7600.isChecked = s.notifyEmergency7600
        swEmg7500.isChecked = s.notifyEmergency7500

        val saveToggle = {
            FlightRepository.updateSettings(
                FlightRepository.settings.value.copy(
                    notifyLanding = swLanding.isChecked,
                    notifyTakeoff = swTakeoff.isChecked,
                    notifyApproach = swApproach.isChecked,
                    notifyEmergency7700 = swEmg7700.isChecked,
                    notifyEmergencyPan = swEmgPan.isChecked,
                    notifyEmergency7600 = swEmg7600.isChecked,
                    notifyEmergency7500 = swEmg7500.isChecked
                )
            )
        }
        swLanding.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swTakeoff.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swApproach.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swEmg7700.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swEmgPan.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swEmg7600.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swEmg7500.setOnCheckedChangeListener { _, _ -> saveToggle() }

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

        findViewById<Button>(R.id.btn_sim_emergency).setOnClickListener {
            val labels = arrayOf(
                "Cod 7700 — MAYDAY (Urgență generală)",
                "PAN-PAN — Urgență medicală / LIFEGUARD",
                "Cod 7600 — Pierdere comunicații radio (NORDO)",
                "Cod 7500 — Intervenție ilegală (HIJACK)"
            )
            val kinds = arrayOf("emergency_7700", "emergency_pan", "emergency_7600", "emergency_7500")
            AlertDialog.Builder(this)
                .setTitle("Alege tipul de urgență de simulat")
                .setItems(labels) { _, which ->
                    chipGroupZoom.check(R.id.chip_zoom_45)
                    ensureNotificationPermissionAndAction(
                        Intent(this, FlightMonitorService::class.java).apply {
                            action = FlightMonitorService.ACTION_SIMULATE
                            putExtra(FlightMonitorService.EXTRA_SIM_KIND, kinds[which])
                            putExtra(FlightMonitorService.EXTRA_SIM_RUNWAY, "18")
                        }
                    )
                }
                .setNegativeButton("Anulează", null)
                .show()
        }

        btnDeleteRecentSim.setOnClickListener {
            val ids = recentAdapter.getSelectedSimulatedIds()
            val removed = FlightRepository.deleteSimulatedEventsByIds(ids)
            recentAdapter.clearSelection()
            if (removed > 0) {
                Toast.makeText(
                    this,
                    "Au fost șterse $removed zboruri simulate.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // =========================================================================
    // TAB 3: ISTORIC & CĂUTARE PE INTERVALE
    // =========================================================================
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
                R.id.chip_type_emergency -> FlightEventType.EMERGENCY
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
            createHistoryPdfLauncher.launch("SAT_CND_Istoric_$stamp.pdf")
        }

        btnSyncMissed.setOnClickListener {
            syncMissedFlightsManually()
        }

        btnDeleteHistorySim.setOnClickListener {
            val ids = historyAdapter.getSelectedSimulatedIds()
            val removed = FlightRepository.deleteSimulatedEventsByIds(ids)
            historyAdapter.clearSelection()
            if (removed > 0) {
                Toast.makeText(
                    this,
                    "Au fost șterse $removed zboruri simulate.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        btnDeleteAllSim.setOnClickListener {
            val simCount = allEvents.count { it.simulated }
            if (simCount == 0) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Ștergi toate zborurile simulate?")
                .setMessage("Vor fi șterse toate cele $simCount zboruri de test (zborurile reale rămân intacte).")
                .setPositiveButton("Șterge simulatele") { _, _ ->
                    val removed = FlightRepository.deleteAllSimulatedEvents()
                    recentAdapter.clearSelection()
                    historyAdapter.clearSelection()
                    Toast.makeText(
                        this,
                        "Au fost șterse $removed zboruri simulate.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("Anulează", null)
                .show()
        }

        findViewById<Button>(R.id.btn_clear_events).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Golești istoricul?")
                .setMessage("Toate aterizările, decolările și alertele salvate pe telefon vor fi șterse.")
                .setPositiveButton("Șterge") { _, _ -> FlightRepository.clearEvents() }
                .setNegativeButton("Anulează", null)
                .show()
        }

        updateIntervalButtons()
    }

    private fun syncMissedFlightsManually() {
        btnSyncMissed.isEnabled = false
        btnSyncMissed.text = "Se sincronizează din OpenSky…"
        lifecycleScope.launch {
            val nowMs = System.currentTimeMillis()
            val nowSec = nowMs / 1000L
            val lastSyncMs = FlightRepository.getLastOnlineSyncMs()
            val maxLookbackMs = 48L * 3600_000L
            val defaultLookbackMs = 24L * 3600_000L
            val beginMs = if (lastSyncMs > 0L && (nowMs - lastSyncMs) in 60_000L..maxLookbackMs) {
                (lastSyncMs - 10 * 60_000L).coerceAtLeast(nowMs - maxLookbackMs)
            } else {
                nowMs - defaultLookbackMs
            }

            val result = withContext(Dispatchers.IO) {
                AdsbClient.fetchMissedFlightsFromOpenSky(beginMs / 1000L, nowSec)
            }
            btnSyncMissed.isEnabled = true
            btnSyncMissed.text = getString(R.string.btn_sync_missed)

            if (result.ok) {
                val added = FlightRepository.mergeRecoveredEvents(result.events)
                FlightRepository.setLastOnlineSyncMs(nowMs)
                val msg = if (added > 0) {
                    "Au fost recuperate $added zboruri din perioada offline (OpenSky)."
                } else {
                    "Sincronizare completă: nu au fost găsite zboruri noi de recuperat din OpenSky."
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "OpenSky indisponibil temporar: ${result.error ?: "verifică internetul"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // =========================================================================
    // TAB 4: RAPORT ZILNIC (TURKISH AIRLINES & WIZZ AIR)
    // =========================================================================
    private fun setupDailyReportControls() {
        updateReportDateLabel()

        findViewById<Button>(R.id.btn_report_pick_date).setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    reportDateCal.set(Calendar.YEAR, year)
                    reportDateCal.set(Calendar.MONTH, month)
                    reportDateCal.set(Calendar.DAY_OF_MONTH, day)
                    updateReportDateLabel()
                    refreshDailyReportFlights(forceRender = true)
                },
                reportDateCal.get(Calendar.YEAR),
                reportDateCal.get(Calendar.MONTH),
                reportDateCal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        chipGroupSupervisor.setOnCheckedStateChangeListener { _, checkedIds ->
            val isOther = checkedIds.firstOrNull() == R.id.chip_sup_other
            tilOtherSupervisor.visibility = if (isOther) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btn_add_manual_flight).setOnClickListener {
            showAddManualFlightDialog()
        }

        val prohibitedWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val p = readProhibitedItems()
                tvProhTotal.text = "Total articole interzise: ${p.total}"
            }
        }
        listOf(
            etProhProjectiles, etProhStunning, etProhSharp,
            etProhTools, etProhBlunt, etProhExplosives
        ).forEach { it.addTextChangedListener(prohibitedWatcher) }

        findViewById<Button>(R.id.btn_daily_generate_pdf).setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(reportDateCal.time)
            createDailyPdfLauncher.launch("SAT_CND_Raport_Zilnic_$stamp.pdf")
        }

        findViewById<Button>(R.id.btn_daily_share_pdf).setOnClickListener {
            shareDailyReportPdf()
        }

        refreshDailyReportFlights(forceRender = true)
    }

    private fun updateReportDateLabel() {
        tvReportDate.text = "Data: ${dateFmt.format(reportDateCal.time)}"
    }

    private fun currentReportDateKey(): String = dateFmt.format(reportDateCal.time)

    private fun refreshDailyReportFlights(forceRender: Boolean) {
        val dateKey = currentReportDateKey()
        val autoFlights = DailyReportBuilder.buildDailyFlights(allEvents, reportDateCal, flightInputsById)
        val manualFlights = manualDailyFlightsByDate[dateKey].orEmpty().map { m ->
            val saved = flightInputsById[m.id]
            if (saved != null) {
                m.copy(passengers = saved.first, holdBaggage = saved.second, pmr = saved.third)
            } else {
                m
            }
        }
        val combined = (autoFlights + manualFlights).sortedBy { it.sortTimeMs }
        val sig = "$dateKey|" + combined.joinToString(";") {
            "${it.id}:${it.landingTime}:${it.takeoffTime}:${it.aircraftType}:${it.registration}"
        }

        dailyFlights = combined.toMutableList()
        if (forceRender || sig != lastRenderedDailySig) {
            lastRenderedDailySig = sig
            renderDailyFlightCards()
        }
    }

    private fun renderDailyFlightCards() {
        reportFlightsContainer.removeAllViews()
        tvReportEmptyFlights.visibility = if (dailyFlights.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        for (entry in dailyFlights) {
            val card = inflater.inflate(R.layout.item_daily_flight, reportFlightsContainer, false)
            card.findViewById<TextView>(R.id.tv_df_airline).text = "${entry.airline} • ${entry.callsign}"
            card.findViewById<TextView>(R.id.tv_df_aircraft).text =
                "Tip: ${entry.aircraftType}  •  Înmatriculare: ${entry.registration}"
            card.findViewById<TextView>(R.id.tv_df_landing_time).text = entry.landingTime
            card.findViewById<TextView>(R.id.tv_df_takeoff_time).text = entry.takeoffTime

            val btnRemove = card.findViewById<Button>(R.id.btn_df_remove)
            if (entry.manual) {
                btnRemove.visibility = View.VISIBLE
                btnRemove.setOnClickListener {
                    manualDailyFlightsByDate[currentReportDateKey()]?.removeAll { it.id == entry.id }
                    flightInputsById.remove(entry.id)
                    refreshDailyReportFlights(forceRender = true)
                }
            }

            val etPax = card.findViewById<TextInputEditText>(R.id.et_df_passengers)
            val etBags = card.findViewById<TextInputEditText>(R.id.et_df_baggage)
            val etPmr = card.findViewById<TextInputEditText>(R.id.et_df_pmr)

            if (entry.passengers > 0) etPax.setText(entry.passengers.toString())
            if (entry.holdBaggage > 0) etBags.setText(entry.holdBaggage.toString())
            if (entry.pmr > 0) etPmr.setText(entry.pmr.toString())

            val syncInputs = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    entry.passengers = parseIntOrZero(etPax.text?.toString())
                    entry.holdBaggage = parseIntOrZero(etBags.text?.toString())
                    entry.pmr = parseIntOrZero(etPmr.text?.toString())
                    flightInputsById[entry.id] = Triple(entry.passengers, entry.holdBaggage, entry.pmr)
                }
            }
            etPax.addTextChangedListener(syncInputs)
            etBags.addTextChangedListener(syncInputs)
            etPmr.addTextChangedListener(syncInputs)

            reportFlightsContainer.addView(card)
        }
    }

    private fun showAddManualFlightDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_manual_flight, null)
        val rbTurkish = view.findViewById<RadioButton>(R.id.rb_manual_turkish)
        val etCallsign = view.findViewById<EditText>(R.id.et_manual_callsign)
        val etType = view.findViewById<EditText>(R.id.et_manual_type)
        val etReg = view.findViewById<EditText>(R.id.et_manual_reg)
        val etLand = view.findViewById<EditText>(R.id.et_manual_land)
        val etTakeoff = view.findViewById<EditText>(R.id.et_manual_takeoff)

        AlertDialog.Builder(this)
            .setTitle("Adaugă cursă în raport")
            .setView(view)
            .setPositiveButton("Adaugă") { _, _ ->
                manualFlightSeq += 1
                val airline = if (rbTurkish.isChecked) "Turkish Airlines" else "Wizz Air"
                val defaultCs = if (rbTurkish.isChecked) "THY" else "WZZ"
                val entry = DailyFlightEntry(
                    id = "manual_${System.currentTimeMillis()}_$manualFlightSeq",
                    airline = airline,
                    callsign = etCallsign.text.toString().trim().uppercase(Locale.US).ifBlank { defaultCs },
                    aircraftType = etType.text.toString().trim().uppercase(Locale.US).ifBlank { "—" },
                    registration = etReg.text.toString().trim().uppercase(Locale.US).ifBlank { "—" },
                    landingTime = etLand.text.toString().trim().ifBlank { "—" },
                    takeoffTime = etTakeoff.text.toString().trim().ifBlank { "—" },
                    sortTimeMs = System.currentTimeMillis(),
                    manual = true
                )
                manualDailyFlightsByDate.getOrPut(currentReportDateKey()) { mutableListOf() }.add(entry)
                refreshDailyReportFlights(forceRender = true)
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun readSelectedSupervisor(): String {
        return when (chipGroupSupervisor.checkedChipId) {
            R.id.chip_sup_lia -> "LIA"
            R.id.chip_sup_mca -> "MCA"
            R.id.chip_sup_other -> etOtherSupervisor.text?.toString()?.trim()?.ifBlank { "Altul" } ?: "Altul"
            else -> "PPG"
        }
    }

    private fun readProhibitedItems(): ProhibitedItems {
        return ProhibitedItems(
            projectileDevices = parseIntOrZero(etProhProjectiles.text?.toString()),
            stunningDevices = parseIntOrZero(etProhStunning.text?.toString()),
            sharpObjects = parseIntOrZero(etProhSharp.text?.toString()),
            tools = parseIntOrZero(etProhTools.text?.toString()),
            bluntInstruments = parseIntOrZero(etProhBlunt.text?.toString()),
            explosivesIncendiary = parseIntOrZero(etProhExplosives.text?.toString())
        )
    }

    private fun collectDailyReportData(): DailyReportData {
        return DailyReportData(
            dateLabel = dateFmt.format(reportDateCal.time),
            supervisor = readSelectedSupervisor(),
            flights = dailyFlights.map { it.copy() },
            shootingSession = chkShooting.isChecked,
            securityCommittee = chkSecurityCommittee.isChecked,
            pnsaExemptions = chkPnsaExemptions.isChecked,
            prohibitedItems = readProhibitedItems(),
            otherNotes = etReportOtherNotes.text?.toString()?.trim().orEmpty()
        )
    }

    private fun exportDailyReportToUri(uri: Uri) {
        val report = collectDailyReportData()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        DailyReportPdfExporter.exportToStream(out, report)
                    } ?: error("Nu s-a putut deschide fișierul pentru scriere.")
                }
            }
            if (result.isSuccess) {
                Toast.makeText(
                    this@MainActivity,
                    "Raport zilnic PDF salvat (${report.dateLabel}).",
                    Toast.LENGTH_LONG
                ).show()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Raport zilnic PDF generat")
                    .setMessage("Raportul pentru ${report.dateLabel} (Supervizor: ${report.supervisor}) a fost salvat. Vrei să îl deschizi acum?")
                    .setPositiveButton("Deschide PDF") { _, _ -> openPdfUri(uri) }
                    .setNegativeButton("Închide", null)
                    .show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Eroare la generare PDF: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun shareDailyReportPdf() {
        val report = collectDailyReportData()
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(reportDateCal.time)
        lifecycleScope.launch {
            val fileResult = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "reports").apply { mkdirs() }
                    val pdfFile = File(dir, "SAT_CND_Raport_Zilnic_$stamp.pdf")
                    FileOutputStream(pdfFile).use { out ->
                        DailyReportPdfExporter.exportToStream(out, report)
                    }
                    pdfFile
                }
            }
            val pdfFile = fileResult.getOrNull()
            if (pdfFile == null) {
                Toast.makeText(
                    this@MainActivity,
                    "Eroare pregătire PDF: ${fileResult.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val contentUri = FileProvider.getUriForFile(
                this@MainActivity,
                "${packageName}.fileprovider",
                pdfFile
            )
            val totPax = report.flights.sumOf { it.passengers }
            val summaryText = buildString {
                append("Raport zilnic SAT - Monitorizare trafic CND (${report.dateLabel})\n")
                append("Supervizor: ${report.supervisor}\n")
                append("Curse Turkish/Wizz: ${report.flights.size} | Pasageri: $totPax\n")
                append("Articole interzise: ${report.prohibitedItems.total}")
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "Raport zilnic CND — ${report.dateLabel} (${report.supervisor})")
                putExtra(Intent.EXTRA_TEXT, summaryText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Trimite raportul pe WhatsApp / Email"))
        }
    }

    private fun openPdfUri(uri: Uri) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(viewIntent) }.onFailure {
            Toast.makeText(
                this,
                "Nu există o aplicație instalată pentru vizualizare PDF.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun parseIntOrZero(raw: String?): Int {
        return raw?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    // =========================================================================
    // HELPERS ISTORIC & OBSERVARE FLUX LIVE
    // =========================================================================
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
        val emergencies = matches.count { it.type == FlightEventType.EMERGENCY }
        tvHistorySummary.text = "Rezultate: ${matches.size} (${landings} ateriz., ${takeoffs} decol., ${emergencies} urgențe)"
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
                    .setPositiveButton("Deschide PDF") { _, _ -> openPdfUri(uri) }
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
                        val selHex = selectedAircraftHex
                        if (selHex != null) {
                            val updatedAc = snap.activeAircraft.firstOrNull { it.hex == selHex }
                            if (updatedAc != null) {
                                val loading = selectedAircraftDetails == null &&
                                    selectedAircraftDetailsJob?.isActive == true
                                renderSelectedAircraftPanel(updatedAc, selectedAircraftDetails, loading)
                            } else {
                                selectedAircraftHex = null
                                selectedAircraftDetails = null
                                tvSelectedAircraft.visibility = View.GONE
                            }
                        }
                        val updated = snap.updatedAtMs?.let { clockFmt.format(Date(it)) } ?: "—"
                        tvStatus.text = when {
                            snap.providerOk -> "Live (${snap.provider}) • ${snap.activeAircraft.size} pe radar • $updated"
                            snap.providerError != null -> "Sursă ADS-B indisponibilă temporar • $updated"
                            FlightRepository.isMonitoring.value -> "Se conectează la fluxul ADS-B…"
                            else -> "Monitorizare oprită. Apasă „Pornește monitorizarea 24/7”."
                        }

                        val activeEmergencies = snap.activeAircraft.filter {
                            it.phase == "emergency" ||
                                it.phase == "emergency_pan" ||
                                AirportConfig.emergencyInfo(it.squawk, it.emergencyStatus) != null
                        }
                        if (activeEmergencies.isNotEmpty()) {
                            val hasCritical = activeEmergencies.any { ac ->
                                val info = AirportConfig.emergencyInfo(ac.squawk, ac.emergencyStatus)
                                info != null && info.code != "PAN"
                            }
                            tvEmergencyBanner.setBackgroundColor(
                                Color.parseColor(if (hasCritical) "#DC2626" else "#EA580C")
                            )
                            tvEmergencyBanner.text = activeEmergencies.joinToString("\n") { ac ->
                                val info = AirportConfig.emergencyInfo(ac.squawk, ac.emergencyStatus)
                                val prefix = if (info?.code == "PAN") "ALERTĂ PAN-PAN" else "ALERTĂ URGENȚĂ"
                                val desc = info?.description ?: "COD URGENȚĂ ${ac.squawk}"
                                "$prefix: ${ac.callsign} • $desc • ${ac.distKm} km de LRCK (${ac.altAglFt ?: "—"} ft)"
                            }
                            tvEmergencyBanner.visibility = View.VISIBLE
                        } else {
                            tvEmergencyBanner.visibility = View.GONE
                        }
                    }
                }
                launch {
                    FlightRepository.events.collect { list ->
                        allEvents = list
                        val recent = list.take(5)
                        recentAdapter.submitList(recent)
                        tvEmptyRecent.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        btnDeleteAllSim.visibility = if (list.any { it.simulated }) View.VISIBLE else View.GONE
                        val landings = list.count { it.type == FlightEventType.LANDING }
                        val takeoffs = list.count { it.type == FlightEventType.TAKEOFF }
                        val emergencies = list.count { it.type == FlightEventType.EMERGENCY }
                        tvStats.text = "Total: ${list.size} • Aterizări: $landings • Decolări: $takeoffs • Urgențe: $emergencies"
                        applyHistoryFilter()
                        refreshDailyReportFlights(forceRender = false)
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
