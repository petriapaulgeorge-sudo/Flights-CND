package ro.constanta.flightnotifier.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
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
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
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
import ro.constanta.flightnotifier.core.DobrogeaDroneMonitor
import ro.constanta.flightnotifier.core.EventSearch
import ro.constanta.flightnotifier.core.PdfExporter
import ro.constanta.flightnotifier.core.RomatsaAirspaceClient
import ro.constanta.flightnotifier.core.RunwayConditions
import ro.constanta.flightnotifier.core.RunwayOperability
import ro.constanta.flightnotifier.core.RunwayStatusClient
import ro.constanta.flightnotifier.data.FlightRepository
import ro.constanta.flightnotifier.data.LocalRunwayNote
import ro.constanta.flightnotifier.model.ActiveAircraft
import ro.constanta.flightnotifier.model.DailyFlightEntry
import ro.constanta.flightnotifier.model.DailyReportData
import ro.constanta.flightnotifier.model.DroneAlertRecord
import ro.constanta.flightnotifier.model.DroneMonitorSnapshot
import ro.constanta.flightnotifier.model.EventSearchFilter
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.model.ProhibitedItems
import ro.constanta.flightnotifier.model.RomatsaAirspaceSnapshot
import ro.constanta.flightnotifier.model.RomatsaNotice
import ro.constanta.flightnotifier.model.RomatsaNoticeCategory
import ro.constanta.flightnotifier.model.SavedDailyReport
import ro.constanta.flightnotifier.notify.NotificationHelper
import ro.constanta.flightnotifier.service.FlightMonitorService
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvHeaderLivePill: TextView
    private lateinit var viewPager: ViewPager2
    private lateinit var sectionPages: List<View>

    private lateinit var radarView: RadarCanvasView
    private lateinit var tvEmergencyBanner: TextView
    private lateinit var tvRadarDroneBanner: TextView
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
    private lateinit var swGoAround: SwitchMaterial
    private lateinit var swEmg7700: SwitchMaterial
    private lateinit var swEmgPan: SwitchMaterial
    private lateinit var swEmg7600: SwitchMaterial
    private lateinit var swEmg7500: SwitchMaterial
    private lateinit var etFlyItalyKey: TextInputEditText
    private lateinit var tvRadarAlertsSummary: TextView
    private lateinit var tvRadarSourcesSummary: TextView
    private lateinit var btnDeleteRecentSim: Button
    private lateinit var tabLayout: TabLayout
    private lateinit var paneRadar: View
    private lateinit var paneRunway: View
    private lateinit var paneHistory: View
    private lateinit var paneReport: View
    private lateinit var paneDrone: View
    private lateinit var paneAbout: View

    // Stare pistă
    private lateinit var btnRwyOperability: MaterialButton
    private lateinit var tvRwyOperabilityDesc: TextView
    private lateinit var rwyLevelViews: List<TextView>
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
    private var lastRunwayConditions: RunwayConditions? = null
    private var lastRunwayOperability: RunwayOperability? = null

    // Istoric & căutare
    private lateinit var etSearchQuery: TextInputEditText
    private lateinit var chipGroupType: ChipGroup
    private lateinit var btnFromDate: Button
    private lateinit var btnFromTime: Button
    private lateinit var btnToDate: Button
    private lateinit var btnToTime: Button
    private lateinit var btnSyncMissed: Button
    private lateinit var tvOpenSkyAuthSummary: TextView
    private lateinit var tvOpenSkyAuthChevron: TextView
    private lateinit var bodyOpenSkyAuthAccordion: View
    private lateinit var etOpenSkyClientId: TextInputEditText
    private lateinit var etOpenSkyClientSecret: TextInputEditText
    private lateinit var btnSaveOpenSkyAuth: Button
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
    private lateinit var tvReportHistoryEmpty: TextView
    private lateinit var savedReportsContainer: LinearLayout

    // Drone Dobrogea (DIMAP.LIVE)
    private lateinit var tvDroneAlertBanner: TextView
    private lateinit var tvDroneSectorPill: TextView
    private lateinit var tvDroneApiSummary: TextView
    private lateinit var swDroneDobrogea: SwitchMaterial
    private lateinit var chipGroupDroneRadius: ChipGroup
    private lateinit var tvDroneStatus: TextView
    private lateinit var webViewDimap: WebView
    private lateinit var tvDroneTargetsList: TextView
    private lateinit var tvRomatsaStatus: TextView
    private lateinit var chipGroupRomatsaFilter: ChipGroup
    private lateinit var tvRomatsaEmpty: TextView
    private lateinit var romatsaNoticesContainer: LinearLayout
    private lateinit var btnRomatsaRefresh: Button
    private lateinit var tvDroneAlertsHistory: TextView
    private lateinit var etDimapApiUrl: TextInputEditText
    private lateinit var etDimapApiKey: TextInputEditText
    private lateinit var etUaApiKey: TextInputEditText
    private var dimapWebLoadedOnce = false
    private var romatsaLoadedOnce = false
    private var currentRomatsaFilter: Int = R.id.chip_romatsa_all
    private var currentDimapUrl: String = DobrogeaDroneMonitor.DIMAP_LITE_URL

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

        setupSectionPages()
        bindViews()
        setupTabs()
        setupAccordions()
        setupRadarControls()
        setupRunwayStatusControls()
        setupMonitorControls()
        setupHistorySearchControls()
        setupDailyReportControls()
        setupDroneDobrogeaControls()
        observeState()
        handleOpenTabIntent(intent)
    }

    private fun <T : View> pageView(id: Int): T {
        val direct: T? = findViewById(id)
        if (direct != null) return direct
        if (::sectionPages.isInitialized) {
            for (page in sectionPages) {
                val found: T? = page.findViewById(id)
                if (found != null) return found
            }
        }
        throw IllegalStateException("View not found in MainActivity pages for id=$id")
    }

    private fun <T : View> pageViewOrNull(id: Int): T? {
        val direct: T? = findViewById(id)
        if (direct != null) return direct
        if (::sectionPages.isInitialized) {
            for (page in sectionPages) {
                val found: T? = page.findViewById(id)
                if (found != null) return found
            }
        }
        return null
    }

    private fun setupSectionPages() {
        val inflater = LayoutInflater.from(this)
        paneRadar = inflater.inflate(R.layout.page_radar, null)
        paneRunway = inflater.inflate(R.layout.page_runway, null)
        paneHistory = inflater.inflate(R.layout.page_history, null)
        paneReport = inflater.inflate(R.layout.page_report, null)
        paneDrone = inflater.inflate(R.layout.page_drone, null)
        paneAbout = inflater.inflate(R.layout.page_about, null)
        sectionPages = listOf(paneRadar, paneRunway, paneHistory, paneReport, paneDrone, paneAbout)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenTabIntent(intent)
    }

    override fun onDestroy() {
        if (::webViewDimap.isInitialized) {
            runCatching { webViewDimap.destroy() }
        }
        super.onDestroy()
    }

    private fun handleOpenTabIntent(intent: Intent?) {
        val targetTab = intent?.getIntExtra(EXTRA_OPEN_TAB, -1) ?: -1
        if (targetTab in sectionPages.indices) {
            viewPager.setCurrentItem(targetTab, false)
        }
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

        val root = pageView<View>(R.id.root_container)
        val extraTopPx = (6 * resources.displayMetrics.density).toInt()
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
        tvHeaderLivePill = pageView(R.id.tv_header_live_pill)
        tabLayout = pageView(R.id.tab_layout)
        viewPager = pageView(R.id.view_pager)

        radarView = pageView(R.id.radar_view)
        tvEmergencyBanner = pageView(R.id.tv_emergency_banner)
        tvRadarDroneBanner = pageView(R.id.tv_radar_drone_banner)
        tvSelectedAircraft = pageView(R.id.tv_selected_aircraft)
        chipGroupZoom = pageView(R.id.chip_group_zoom)
        tvStatus = pageView(R.id.tv_status)
        tvStats = pageView(R.id.tv_stats)
        tvEmptyRecent = pageView(R.id.tv_empty_recent)
        tvHistorySummary = pageView(R.id.tv_history_summary)
        tvEmptyHistory = pageView(R.id.tv_empty_history)
        btnToggleMonitor = pageView(R.id.btn_toggle_monitor)
        swLanding = pageView(R.id.switch_landing)
        swTakeoff = pageView(R.id.switch_takeoff)
        swApproach = pageView(R.id.switch_approach)
        swGoAround = pageView(R.id.switch_go_around)
        swEmg7700 = pageView(R.id.switch_emg_7700)
        swEmgPan = pageView(R.id.switch_emg_pan)
        swEmg7600 = pageView(R.id.switch_emg_7600)
        swEmg7500 = pageView(R.id.switch_emg_7500)
        etFlyItalyKey = pageView(R.id.et_flyitaly_key)
        tvRadarAlertsSummary = pageView(R.id.tv_radar_alerts_summary)
        tvRadarSourcesSummary = pageView(R.id.tv_radar_sources_summary)
        btnDeleteRecentSim = pageView(R.id.btn_delete_recent_sim)

        btnRwyOperability = pageView(R.id.btn_rwy_operability)
        tvRwyOperabilityDesc = pageView(R.id.tv_rwy_operability_desc)
        rwyLevelViews = listOf(
            pageView(R.id.tv_rwy_lvl_1),
            pageView(R.id.tv_rwy_lvl_2),
            pageView(R.id.tv_rwy_lvl_3),
            pageView(R.id.tv_rwy_lvl_4),
            pageView(R.id.tv_rwy_lvl_5)
        )
        tvRwyFlightCat = pageView(R.id.tv_rwy_flight_cat)
        tvRwyObsTime = pageView(R.id.tv_rwy_obs_time)
        tvRwySurface = pageView(R.id.tv_rwy_surface)
        tvRwyRecommended = pageView(R.id.tv_rwy_recommended)
        tvRwyDetails = pageView(R.id.tv_rwy_details)
        tvRwyRawMetar = pageView(R.id.tv_rwy_raw_metar)
        chipGroupRwyState = pageView(R.id.chip_group_rwy_state)
        chipGroupRwycc = pageView(R.id.chip_group_rwycc)
        etRunwayNote = pageView(R.id.et_runway_note)

        etSearchQuery = pageView(R.id.et_search_query)
        chipGroupType = pageView(R.id.chip_group_type)
        btnFromDate = pageView(R.id.btn_from_date)
        btnFromTime = pageView(R.id.btn_from_time)
        btnToDate = pageView(R.id.btn_to_date)
        btnToTime = pageView(R.id.btn_to_time)
        btnSyncMissed = pageView(R.id.btn_sync_missed)
        tvOpenSkyAuthSummary = pageView(R.id.tv_opensky_auth_summary)
        tvOpenSkyAuthChevron = pageView(R.id.tv_opensky_auth_chevron)
        bodyOpenSkyAuthAccordion = pageView(R.id.body_opensky_auth_accordion)
        etOpenSkyClientId = pageView(R.id.et_opensky_client_id)
        etOpenSkyClientSecret = pageView(R.id.et_opensky_client_secret)
        btnSaveOpenSkyAuth = pageView(R.id.btn_save_opensky_auth)
        btnDeleteHistorySim = pageView(R.id.btn_delete_history_sim)
        btnDeleteAllSim = pageView(R.id.btn_delete_all_sim)

        tvReportDate = pageView(R.id.tv_report_date)
        chipGroupSupervisor = pageView(R.id.chip_group_supervisor)
        tilOtherSupervisor = pageView(R.id.til_other_supervisor)
        etOtherSupervisor = pageView(R.id.et_other_supervisor)
        tvReportEmptyFlights = pageView(R.id.tv_report_empty_flights)
        reportFlightsContainer = pageView(R.id.report_flights_container)
        chkShooting = pageView(R.id.chk_shooting)
        chkSecurityCommittee = pageView(R.id.chk_security_committee)
        chkPnsaExemptions = pageView(R.id.chk_pnsa_exemptions)
        etProhProjectiles = pageView(R.id.et_proh_projectiles)
        etProhStunning = pageView(R.id.et_proh_stunning)
        etProhSharp = pageView(R.id.et_proh_sharp)
        etProhTools = pageView(R.id.et_proh_tools)
        etProhBlunt = pageView(R.id.et_proh_blunt)
        etProhExplosives = pageView(R.id.et_proh_explosives)
        tvProhTotal = pageView(R.id.tv_proh_total)
        etReportOtherNotes = pageView(R.id.et_report_other_notes)
        tvReportHistoryEmpty = pageView(R.id.tv_report_history_empty)
        savedReportsContainer = pageView(R.id.saved_reports_container)

        tvDroneAlertBanner = pageView(R.id.tv_drone_alert_banner)
        tvDroneSectorPill = pageView(R.id.tv_drone_sector_pill)
        tvDroneApiSummary = pageView(R.id.tv_drone_api_summary)
        swDroneDobrogea = pageView(R.id.switch_drone_dobrogea)
        chipGroupDroneRadius = pageView(R.id.chip_group_drone_radius)
        tvDroneStatus = pageView(R.id.tv_drone_status)
        webViewDimap = pageView(R.id.webview_dimap)
        tvDroneTargetsList = pageView(R.id.tv_drone_targets_list)
        tvRomatsaStatus = pageView(R.id.tv_romatsa_status)
        chipGroupRomatsaFilter = pageView(R.id.chip_group_romatsa_filter)
        tvRomatsaEmpty = pageView(R.id.tv_romatsa_empty)
        romatsaNoticesContainer = pageView(R.id.romatsa_notices_container)
        btnRomatsaRefresh = pageView(R.id.btn_romatsa_refresh)
        tvDroneAlertsHistory = pageView(R.id.tv_drone_alerts_history)
        etDimapApiUrl = pageView(R.id.et_dimap_api_url)
        etDimapApiKey = pageView(R.id.et_dimap_api_key)
        etUaApiKey = pageView(R.id.et_ua_api_key)

        val openFlight: (FlightEvent) -> Unit = { evt ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(evt.adsbExchangeUrl)))
        }

        val rvRecent: RecyclerView = pageView(R.id.rv_recent_events)
        recentAdapter = EventsAdapter(openFlight) { selectedCount ->
            btnDeleteRecentSim.visibility = if (selectedCount > 0) View.VISIBLE else View.GONE
            if (selectedCount > 0) {
                btnDeleteRecentSim.text = "Șterge simulate selectate ($selectedCount)"
            }
        }
        rvRecent.layoutManager = LinearLayoutManager(this)
        rvRecent.adapter = recentAdapter

        val rvHistory: RecyclerView = pageView(R.id.rv_history_events)
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
        viewPager.adapter = SectionPagerAdapter(sectionPages)
        viewPager.offscreenPageLimit = sectionPages.size - 1
        viewPager.isUserInputEnabled = true

        val tabSpecs = listOf(
            R.string.tab_radar to R.drawable.ic_tab_radar,
            R.string.tab_runway to R.drawable.ic_tab_runway,
            R.string.tab_history to R.drawable.ic_tab_history,
            R.string.tab_report to R.drawable.ic_tab_report,
            R.string.tab_drone to R.drawable.ic_tab_drone,
            R.string.tab_about to R.drawable.ic_tab_about
        )

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            val (titleRes, iconRes) = tabSpecs[position]
            tab.setText(titleRes)
            tab.setIcon(iconRes)
        }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onSectionEntered(position)
            }
        })

        tvHeaderLivePill.setOnClickListener {
            if (FlightRepository.isMonitoring.value) {
                stopMonitorService()
            } else {
                ensureNotificationPermissionAndStart()
            }
        }

        pageView<Button>(R.id.btn_open_history).setOnClickListener {
            viewPager.setCurrentItem(2, true)
        }

        tvRadarDroneBanner.setOnClickListener {
            viewPager.setCurrentItem(TAB_DRONE, true)
        }
    }

    private fun onSectionEntered(position: Int) {
        if (position == 1 && !runwayLoadedOnce) {
            refreshRunwayConditions()
        }
        if (position == 3) {
            refreshDailyReportFlights(forceRender = false)
        }
        if (position == TAB_DRONE) {
            if (!dimapWebLoadedOnce) {
                dimapWebLoadedOnce = true
                webViewDimap.loadUrl(currentDimapUrl)
                if (FlightRepository.settings.value.hasAnyDroneSourceConfigured) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        FlightMonitorService.pollDronesAndNotify(this@MainActivity)
                    }
                }
            }
            if (!romatsaLoadedOnce) {
                refreshRomatsaDobrogeaNotices()
            }
        }
    }

    private fun setupAccordions() {
        bindAccordion(
            R.id.header_radar_alerts_accordion,
            R.id.body_radar_alerts_accordion,
            R.id.tv_radar_alerts_chevron
        )
        bindAccordion(
            R.id.header_radar_sim_accordion,
            R.id.body_radar_sim_accordion,
            R.id.tv_radar_sim_chevron
        )
        bindAccordion(
            R.id.header_radar_sources_accordion,
            R.id.body_radar_sources_accordion,
            R.id.tv_radar_sources_chevron
        )
        bindAccordion(
            R.id.header_rwy_specs_accordion,
            R.id.body_rwy_specs_accordion,
            R.id.tv_rwy_specs_chevron
        )
        bindAccordion(
            R.id.header_history_range_accordion,
            R.id.body_history_range_accordion,
            R.id.tv_history_range_chevron
        )
        bindAccordion(
            R.id.header_opensky_auth_accordion,
            R.id.body_opensky_auth_accordion,
            R.id.tv_opensky_auth_chevron
        )
        bindAccordion(
            R.id.header_drone_api_accordion,
            R.id.body_drone_api_accordion,
            R.id.tv_drone_api_chevron
        )
    }

    private fun bindAccordion(headerId: Int, bodyId: Int, chevronId: Int) {
        val header = pageViewOrNull<View>(headerId) ?: return
        val body = pageViewOrNull<View>(bodyId) ?: return
        val chevron = pageViewOrNull<TextView>(chevronId)
        header.setOnClickListener {
            val opening = body.visibility != View.VISIBLE
            body.visibility = if (opening) View.VISIBLE else View.GONE
            chevron?.text = if (opening) "▴" else "▾"
        }
    }

    private fun roundedBackground(colorInt: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(colorInt)
        }
    }

    private class SectionPagerAdapter(
        private val pages: List<View>
    ) : RecyclerView.Adapter<SectionPagerAdapter.PageVH>() {

        class PageVH(val container: FrameLayout) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val frame = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return PageVH(frame)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val page = pages[position]
            (page.parent as? ViewGroup)?.removeView(page)
            holder.container.removeAllViews()
            holder.container.addView(
                page,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        override fun getItemCount(): Int = pages.size

        override fun getItemViewType(position: Int): Int = position
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
            ?: AdsbClient.resolveKnownAircraftModel(ac.aircraftType)
            ?: ac.aircraftType.ifBlank { ac.hex.uppercase(Locale.US) }
        val reg = details?.registration?.takeIf { it.isNotBlank() }
            ?: ac.registration.takeIf { it.isNotBlank() }
        val regPart = if (reg != null) " ($reg)" else ""
        val sqStr = if (ac.squawk.isNotBlank()) " • SQ ${ac.squawk}" else ""
        val emInfo = AirportConfig.emergencyInfo(ac.squawk, ac.emergencyStatus)
        val emTag = if (emInfo != null) " • [${emInfo.shortBadge}]" else ""
        val milTag = if (ac.military) " • [MILITAR]" else ""
        val gaTag = if (ac.generalAviation && !ac.military) {
            when (ac.gaCategory) {
                "ȘCOALĂ" -> " • [GA / ȘCOALĂ]"
                "ELICOPTER" -> " • [ELICOPTER]"
                "PLANOR" -> " • [PLANOR]"
                "ULM" -> " • [ULM]"
                else -> " • [AVIAȚIE GENERALĂ]"
            }
        } else {
            ""
        }
        val srcTag = when {
            ac.sourceType.contains("ogn", ignoreCase = true) && ac.sourceType.contains("+", ignoreCase = true) ->
                " • ADS-B+OGN"
            ac.sourceType.contains("ogn", ignoreCase = true) -> " • OGN/FLARM"
            ac.sourceType.contains("mlat", ignoreCase = true) -> " • MLAT"
            ac.sourceType.equals("opensky", ignoreCase = true) -> " • OpenSky"
            else -> ""
        }

        val airlineText = details?.airline?.takeIf { it.isNotBlank() }
            ?: ac.airlineHint?.takeIf { it.isNotBlank() }
            ?: AdsbClient.resolveKnownAirline(ac.callsign)
            ?: AdsbClient.resolveKnownSchoolOrGaOperator(ac.callsign, reg.orEmpty())
            ?: if (loading) {
                "Se caută…"
            } else if (ac.military) {
                "Aeronavă militară / de stat"
            } else if (ac.generalAviation) {
                "Aviație generală / Școală (privat)"
            } else {
                "Indisponibilă în baza ADS-B"
            }

        val distTuzlaKm = (kotlin.math.round(
            ro.constanta.flightnotifier.core.FlightDetector.haversineKm(
                ac.lat, ac.lon, AirportConfig.TUZLA_LAT, AirportConfig.TUZLA_LON
            ) * 10.0
        ) / 10.0)

        val noRouteText = when {
            ac.military -> "Zbor militar (rută nepublică)"
            ac.generalAviation && distTuzlaKm <= 25.0 -> "Zbor VFR / Școală — zonă Aerodrom Tuzla (LRTZ)"
            ac.generalAviation -> "Zbor VFR / Aviație generală (fără plan comercial)"
            else -> "Indisponibilă (fără plan de zbor public)"
        }
        val originText = details?.origin?.takeIf { it.isNotBlank() }
            ?: if (loading) "Se caută…" else noRouteText

        val destText = details?.destination?.takeIf { it.isNotBlank() }
            ?: if (loading) "Se caută…" else noRouteText

        val tuzlaSuffix = if (ac.generalAviation && distTuzlaKm <= 35.0) {
            " • $distTuzlaKm km de Tuzla (LRTZ)"
        } else {
            ""
        }

        tvSelectedAircraft.text = buildString {
            append("${ac.callsign} • $model$regPart$sqStr$milTag$gaTag$emTag$srcTag\n")
            append("Operator: $airlineText\n")
            append("Origine: $originText\n")
            append("Destinație: $destText\n")
            append("Telemetrie: $alt • ${ac.gsKt ?: "—"} kt • V/S $vs • ${ac.distKm} km de LRCK$tuzlaSuffix")
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

        chipGroupRwyState.setOnCheckedStateChangeListener { _, _ ->
            updateRunwayOperabilityButton()
        }
        chipGroupRwycc.setOnCheckedStateChangeListener { _, _ ->
            updateRunwayOperabilityButton()
        }

        btnRwyOperability.setOnClickListener {
            showOperabilityDetailsDialog()
        }

        pageView<Button>(R.id.btn_refresh_runway).setOnClickListener {
            refreshRunwayConditions()
        }

        pageView<Button>(R.id.btn_save_runway_note).setOnClickListener {
            val opState = currentLocalOperationalState()
            val rwycc = currentLocalRwyccCode()
            val noteText = etRunwayNote.text?.toString()?.trim().orEmpty()
            FlightRepository.saveLocalRunwayNote(LocalRunwayNote(opState, rwycc, noteText))
            updateRunwayOperabilityButton()
            Toast.makeText(this, "Starea locală a pistei a fost salvată ($opState, RWYCC $rwycc).", Toast.LENGTH_SHORT).show()
        }

        updateRunwayOperabilityButton()
    }

    private fun currentLocalOperationalState(): String {
        return when (chipGroupRwyState.checkedChipId) {
            R.id.chip_rwy_caution -> "Atenție meteo"
            R.id.chip_rwy_restricted -> "Restricționată"
            R.id.chip_rwy_closed -> "Închisă"
            else -> "Operațională"
        }
    }

    private fun currentLocalRwyccCode(): String {
        return when (chipGroupRwycc.checkedChipId) {
            R.id.chip_rwycc_5 -> "5 — Umed / Bun"
            R.id.chip_rwycc_4 -> "4 — Mediu-Bun"
            R.id.chip_rwycc_3 -> "3 — Mediu"
            R.id.chip_rwycc_2 -> "2 — Mediu-Slab"
            R.id.chip_rwycc_1 -> "1 — Slab"
            R.id.chip_rwycc_0 -> "0 — Gheață / Critic"
            else -> "6 — Uscat"
        }
    }

    private fun updateRunwayOperabilityButton() {
        val op = RunwayStatusClient.evaluateOperability(
            conditions = lastRunwayConditions,
            localOperationalState = currentLocalOperationalState(),
            localRwyccCode = currentLocalRwyccCode()
        )
        lastRunwayOperability = op
        btnRwyOperability.text = op.shortTitle
        btnRwyOperability.backgroundTintList = ColorStateList.valueOf(Color.parseColor(op.colorHex))
        tvRwyOperabilityDesc.text = op.suggestion

        rwyLevelViews.forEachIndexed { idx, tv ->
            val lvl = idx + 1
            val isActive = lvl == op.level
            tv.alpha = if (isActive) 1.0f else 0.28f
            tv.text = if (isActive) "● $lvl" else "$lvl"
        }
    }

    private fun showOperabilityDetailsDialog() {
        val op = lastRunwayOperability ?: RunwayStatusClient.evaluateOperability(
            conditions = lastRunwayConditions,
            localOperationalState = currentLocalOperationalState(),
            localRwyccCode = currentLocalRwyccCode()
        )
        val body = buildString {
            append("${op.suggestion}\n\n")
            append("Factori evaluați (meteo + stare pistă):\n")
            for (f in op.factors) {
                append("• $f\n")
            }
            append("\nScală culori (1 → 5):\n")
            append("1 (Verde) — Operațională, condiții optime\n")
            append("2 (Verde-galben) — Operațională cu atenție\n")
            append("3 (Galben) — Restricții moderate (IFR / frânare)\n")
            append("4 (Portocaliu) — Operabilitate dificilă / risc ridicat\n")
            append("5 (Roșu) — Neoperațională / critică\n\n")
            append("Notă: Evaluare orientativă; decizia operațională aparține echipajului și ATC.")
        }
        AlertDialog.Builder(this)
            .setTitle(op.shortTitle)
            .setMessage(body)
            .setPositiveButton("Actualizează METAR") { _, _ -> refreshRunwayConditions() }
            .setNegativeButton("Închide", null)
            .show()
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
        lastRunwayConditions = c
        updateRunwayOperabilityButton()

        if (!c.ok) {
            tvRwyFlightCat.text = "N/A"
            tvRwyFlightCat.background = roundedBackground(Color.parseColor("#64748B"), 999f)
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
        tvRwyFlightCat.background = roundedBackground(catColor, 999f)
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
        swGoAround.isChecked = s.notifyGoAround
        swEmg7700.isChecked = s.notifyEmergency7700
        swEmgPan.isChecked = s.notifyEmergencyPan
        swEmg7600.isChecked = s.notifyEmergency7600
        swEmg7500.isChecked = s.notifyEmergency7500
        etFlyItalyKey.setText(s.flyItalyApiKey)
        updateRadarSummaries()

        val saveToggle = {
            FlightRepository.updateSettings(
                FlightRepository.settings.value.copy(
                    notifyLanding = swLanding.isChecked,
                    notifyTakeoff = swTakeoff.isChecked,
                    notifyApproach = swApproach.isChecked,
                    notifyGoAround = swGoAround.isChecked,
                    notifyEmergency7700 = swEmg7700.isChecked,
                    notifyEmergencyPan = swEmgPan.isChecked,
                    notifyEmergency7600 = swEmg7600.isChecked,
                    notifyEmergency7500 = swEmg7500.isChecked
                )
            )
            updateRadarSummaries()
        }
        swLanding.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swTakeoff.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swApproach.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swGoAround.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swEmg7700.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swEmgPan.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swEmg7600.setOnCheckedChangeListener { _, _ -> saveToggle() }
        swEmg7500.setOnCheckedChangeListener { _, _ -> saveToggle() }

        pageView<Button>(R.id.btn_save_flyitaly_key).setOnClickListener {
            val key = etFlyItalyKey.text?.toString()?.trim().orEmpty()
            FlightRepository.updateSettings(
                FlightRepository.settings.value.copy(flyItalyApiKey = key)
            )
            updateRadarSummaries()
            val msg = if (key.isNotEmpty()) {
                "Cheia FlyItalyADSB a fost salvată și va fi inclusă automat în fuziunea radar."
            } else {
                "Cheia FlyItalyADSB a fost ștearsă (rămân active cele 7 surse gratuite ADS-B + OGN + OpenSky)."
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            if (FlightRepository.isMonitoring.value) {
                startService(
                    Intent(this, FlightMonitorService::class.java).apply {
                        action = FlightMonitorService.ACTION_POLL_NOW
                    }
                )
            }
        }

        btnToggleMonitor.setOnClickListener {
            if (FlightRepository.isMonitoring.value) {
                stopMonitorService()
            } else {
                ensureNotificationPermissionAndStart()
            }
        }

        pageView<Button>(R.id.btn_sim_landing).setOnClickListener {
            ensureNotificationPermissionAndAction(
                Intent(this, FlightMonitorService::class.java).apply {
                    action = FlightMonitorService.ACTION_SIMULATE
                    putExtra(FlightMonitorService.EXTRA_SIM_KIND, "landing")
                    putExtra(FlightMonitorService.EXTRA_SIM_RUNWAY, "18")
                }
            )
        }

        pageView<Button>(R.id.btn_sim_takeoff).setOnClickListener {
            ensureNotificationPermissionAndAction(
                Intent(this, FlightMonitorService::class.java).apply {
                    action = FlightMonitorService.ACTION_SIMULATE
                    putExtra(FlightMonitorService.EXTRA_SIM_KIND, "takeoff")
                    putExtra(FlightMonitorService.EXTRA_SIM_RUNWAY, "36")
                }
            )
        }

        pageView<Button>(R.id.btn_sim_go_around).setOnClickListener {
            chipGroupZoom.check(R.id.chip_zoom_25)
            ensureNotificationPermissionAndAction(
                Intent(this, FlightMonitorService::class.java).apply {
                    action = FlightMonitorService.ACTION_SIMULATE
                    putExtra(FlightMonitorService.EXTRA_SIM_KIND, "go_around")
                    putExtra(FlightMonitorService.EXTRA_SIM_RUNWAY, "18")
                }
            )
            Toast.makeText(
                this,
                "Simulare Go-Around pornită pe RWY 18 (va corela automat cu METAR LRCK)…",
                Toast.LENGTH_SHORT
            ).show()
        }

        pageView<Button>(R.id.btn_sim_emergency).setOnClickListener {
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
                R.id.chip_type_go_around -> FlightEventType.GO_AROUND
                R.id.chip_type_emergency -> FlightEventType.EMERGENCY
                else -> null
            }
            currentFilter = currentFilter.copy(type = selectedType)
            applyHistoryFilter()
        }

        pageView<Button>(R.id.btn_preset_today).setOnClickListener {
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

        pageView<Button>(R.id.btn_preset_24h).setOnClickListener {
            val end = Calendar.getInstance()
            val start = (end.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, -24) }
            setDateRange(start, end)
        }

        pageView<Button>(R.id.btn_preset_7d).setOnClickListener {
            val end = Calendar.getInstance()
            val start = (end.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
            setDateRange(start, end)
        }

        pageView<Button>(R.id.btn_preset_all).setOnClickListener {
            setDateRange(null, null)
        }

        btnFromDate.setOnClickListener { pickDate(isFrom = true) }
        btnFromTime.setOnClickListener { pickTime(isFrom = true) }
        btnToDate.setOnClickListener { pickDate(isFrom = false) }
        btnToTime.setOnClickListener { pickTime(isFrom = false) }

        pageView<Button>(R.id.btn_reset_filters).setOnClickListener {
            etSearchQuery.setText("")
            chipGroupType.check(R.id.chip_type_all)
            fromCal = null
            toCal = null
            currentFilter = EventSearchFilter()
            updateIntervalButtons()
            applyHistoryFilter()
        }

        pageView<Button>(R.id.btn_export_pdf).setOnClickListener {
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

        val initSettings = FlightRepository.settings.value
        etOpenSkyClientId.setText(initSettings.openSkyClientId)
        etOpenSkyClientSecret.setText(initSettings.openSkyClientSecret)
        updateOpenSkyAuthSummary()

        pageView<Button>(R.id.btn_open_opensky_portal).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AdsbClient.OPENSKY_ACCOUNT_URL)))
            }
        }

        btnSaveOpenSkyAuth.setOnClickListener {
            val clientId = etOpenSkyClientId.text?.toString()?.trim().orEmpty()
            val clientSecret = etOpenSkyClientSecret.text?.toString()?.trim().orEmpty()

            if (clientId.isEmpty() && clientSecret.isEmpty()) {
                AdsbClient.clearOpenSkyTokenCache()
                FlightRepository.updateSettings(
                    FlightRepository.settings.value.copy(
                        openSkyClientId = "",
                        openSkyClientSecret = ""
                    )
                )
                updateOpenSkyAuthSummary()
                updateRadarSummaries()
                Toast.makeText(
                    this,
                    "Credențialele OpenSky au fost șterse.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (clientId.isEmpty() || clientSecret.isEmpty()) {
                Toast.makeText(
                    this,
                    "Completează atât Client ID, cât și Client Secret din contul OpenSky.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            FlightRepository.updateSettings(
                FlightRepository.settings.value.copy(
                    openSkyClientId = clientId,
                    openSkyClientSecret = clientSecret
                )
            )
            updateOpenSkyAuthSummary()
            updateRadarSummaries()

            btnSaveOpenSkyAuth.isEnabled = false
            btnSaveOpenSkyAuth.text = "Se testează token-ul OAuth2…"
            lifecycleScope.launch {
                val testRes = withContext(Dispatchers.IO) {
                    AdsbClient.testOpenSkyCredentials(clientId, clientSecret)
                }
                btnSaveOpenSkyAuth.isEnabled = true
                btnSaveOpenSkyAuth.text = getString(R.string.btn_save_opensky_auth)
                if (testRes.isSuccess) {
                    Toast.makeText(
                        this@MainActivity,
                        "Autentificare OpenSky reușită! Sincronizarea offline și 4.000 credite/zi sunt active.",
                        Toast.LENGTH_LONG
                    ).show()
                    bodyOpenSkyAuthAccordion.visibility = View.GONE
                    tvOpenSkyAuthChevron.text = "▾"
                    if (FlightRepository.isMonitoring.value) {
                        startService(
                            Intent(this@MainActivity, FlightMonitorService::class.java).apply {
                                action = FlightMonitorService.ACTION_POLL_NOW
                            }
                        )
                    }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Credențiale salvate, dar testul OpenSky a eșuat: ${testRes.exceptionOrNull()?.message ?: "verifică datele"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
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

        pageView<Button>(R.id.btn_clear_events).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Golești istoricul?")
                .setMessage("Toate aterizările, decolările și alertele salvate pe telefon vor fi șterse.")
                .setPositiveButton("Șterge") { _, _ -> FlightRepository.clearEvents() }
                .setNegativeButton("Anulează", null)
                .show()
        }

        updateIntervalButtons()
    }

    private fun updateOpenSkyAuthSummary() {
        val s = FlightRepository.settings.value
        if (s.hasOpenSkyAuth) {
            tvOpenSkyAuthSummary.text = "Conectat OAuth2 (ultimele 12h fără dubluri + 4.000 credite/zi) ✓"
            tvOpenSkyAuthSummary.setTextColor(ContextCompat.getColor(this, R.color.secondary))
        } else {
            tvOpenSkyAuthSummary.text = getString(R.string.opensky_auth_status_missing)
            tvOpenSkyAuthSummary.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun showOpenSkyAuthRequiredDialog(reason: String?) {
        val msg = buildString {
            if (!reason.isNullOrBlank()) {
                append("$reason\n\n")
            }
            append("De ce a apărut eroarea HTTP 403?\n")
            append("OpenSky Network nu mai permite preluarea anonimă a zborurilor istorice (/flights/arrival & /flights/departure). Autentificarea este 100% GRATUITĂ prin OAuth2:\n\n")
            append("1. Deschide contul gratuit pe opensky-network.org/my-opensky/account\n")
            append("2. Creează un API Client nou și copiază Client ID + Client Secret\n")
            append("3. Introdu-le în secțiunea „Cont OpenSky” de sub butonul de sincronizare.\n\n")
            append("Bonus: deblochează automat recuperarea din ultimele 12 ore fără dubluri (replay radar 60 min + arhiva OpenSky 12h) și 4.000 credite/zi pe radarul live.")
        }
        AlertDialog.Builder(this)
            .setTitle("Autentificare gratuită OpenSky necesară")
            .setMessage(msg)
            .setPositiveButton("Configurează acum") { _, _ ->
                bodyOpenSkyAuthAccordion.visibility = View.VISIBLE
                tvOpenSkyAuthChevron.text = "▴"
                etOpenSkyClientId.requestFocus()
            }
            .setNeutralButton("Deschide OpenSky") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AdsbClient.OPENSKY_ACCOUNT_URL)))
                }
            }
            .setNegativeButton("Închide", null)
            .show()
    }

    private fun syncMissedFlightsManually() {
        val s = FlightRepository.settings.value
        if (!s.hasOpenSkyAuth) {
            showOpenSkyAuthRequiredDialog(
                "OpenSky necesită acum credențiale gratuite OAuth2 (Client ID & Client Secret) pentru recuperarea zborurilor offline."
            )
            return
        }

        btnSyncMissed.isEnabled = false
        btnSyncMissed.text = "Se sincronizează ultimele 12h (OpenSky)…"
        lifecycleScope.launch {
            val nowMs = System.currentTimeMillis()
            val nowSec = nowMs / 1000L
            val lookbackMs = 12L * 3600_000L
            val beginMs = nowMs - lookbackMs

            val result = withContext(Dispatchers.IO) {
                AdsbClient.fetchMissedFlightsFromOpenSky(
                    beginSec = beginMs / 1000L,
                    endSec = nowSec,
                    clientId = s.openSkyClientId,
                    clientSecret = s.openSkyClientSecret,
                    includeRecentReplay = true,
                    includeBatchApi = true
                )
            }
            btnSyncMissed.isEnabled = true
            btnSyncMissed.text = getString(R.string.btn_sync_missed)

            if (result.ok) {
                val windowEvents = result.events.filter { it.timestampMs in beginMs..(nowMs + 5 * 60_000L) }
                val added = FlightRepository.mergeRecoveredEvents(windowEvents)
                FlightRepository.setLastOnlineSyncMs(nowMs)
                val skipped = (windowEvents.size - added).coerceAtLeast(0)
                val msg = when {
                    added > 0 && skipped > 0 ->
                        "Recuperate $added zboruri noi din ultimele 12h ($skipped deja existente — 0 dubluri)."
                    added > 0 ->
                        "Recuperate $added zboruri noi din ultimele 12h (OpenSky, 0 dubluri)."
                    windowEvents.isNotEmpty() ->
                        "Sincronizare 12h completă: toate cele ${windowEvents.size} zboruri erau deja în istoric (0 dubluri)."
                    else -> {
                        val note = result.summaryNote ?: "Verificat ultimele 12h (replay 60 min + arhiva OpenSky)."
                        "Sincronizare 12h completă: 0 zboruri noi de recuperat. $note"
                    }
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            } else if (result.needsAuth) {
                showOpenSkyAuthRequiredDialog(result.error)
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

        pageView<Button>(R.id.btn_report_pick_date).setOnClickListener {
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

        pageView<Button>(R.id.btn_add_manual_flight).setOnClickListener {
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

        pageView<Button>(R.id.btn_daily_generate_pdf).setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(reportDateCal.time)
            createDailyPdfLauncher.launch("SAT_CND_Raport_Zilnic_$stamp.pdf")
        }

        pageView<Button>(R.id.btn_daily_share_pdf).setOnClickListener {
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
                    FlightRepository.archiveDailyReportPdf(this@MainActivity, report)
                }
            }
            if (result.isSuccess) {
                Toast.makeText(
                    this@MainActivity,
                    "Raport zilnic PDF salvat și adăugat în Istoric rapoarte (${report.dateLabel}).",
                    Toast.LENGTH_LONG
                ).show()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Raport zilnic PDF generat")
                    .setMessage("Raportul pentru ${report.dateLabel} (Supervizor: ${report.supervisor}) a fost salvat și arhivat în secțiunea Istoric rapoarte. Vrei să îl deschizi acum?")
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
        lifecycleScope.launch {
            val fileResult = withContext(Dispatchers.IO) {
                runCatching {
                    FlightRepository.archiveDailyReportPdf(this@MainActivity, report)
                }
            }
            val archived = fileResult.getOrNull()
            if (archived == null) {
                Toast.makeText(
                    this@MainActivity,
                    "Eroare pregătire PDF: ${fileResult.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            shareSavedReportPdf(archived.first)
        }
    }

    private fun renderSavedReportsHistory(list: List<SavedDailyReport>) {
        savedReportsContainer.removeAllViews()
        tvReportHistoryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) return

        val dtFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ro", "RO"))
        val inflater = LayoutInflater.from(this)
        for (rec in list) {
            val card = inflater.inflate(R.layout.item_saved_report, savedReportsContainer, false)
            card.findViewById<TextView>(R.id.tv_sr_title).text =
                "Raport ${rec.dateLabel}  •  Supervizor: ${rec.supervisor}"
            val details = buildString {
                append("Generat la ${dtFmt.format(Date(rec.createdAtMs))}")
                append(" • ${rec.flightCount} curse • ${rec.totalPassengers} pax • ${rec.totalHoldBaggage} bag. • ${rec.totalPmr} PMR • ${rec.totalProhibited} art. interzise")
                if (rec.otherNotesPreview.isNotBlank()) {
                    append("\nAlte date: ${rec.otherNotesPreview}")
                }
            }
            card.findViewById<TextView>(R.id.tv_sr_details).text = details

            card.findViewById<Button>(R.id.btn_sr_open).setOnClickListener {
                openSavedReportPdf(rec)
            }
            card.findViewById<Button>(R.id.btn_sr_share).setOnClickListener {
                shareSavedReportPdf(rec)
            }
            card.findViewById<Button>(R.id.btn_sr_delete).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Ștergi raportul din istoric?")
                    .setMessage("Raportul din ${rec.dateLabel} (Supervizor: ${rec.supervisor}) va fi șters din arhiva locală.")
                    .setPositiveButton("Șterge") { _, _ ->
                        FlightRepository.deleteSavedReport(this, rec.id)
                    }
                    .setNegativeButton("Anulează", null)
                    .show()
            }
            savedReportsContainer.addView(card)
        }
    }

    private fun openSavedReportPdf(record: SavedDailyReport) {
        val pdfFile = FlightRepository.getSavedReportFile(this, record)
        if (!pdfFile.exists()) {
            Toast.makeText(this, "Fișierul PDF nu mai există în arhiva locală.", Toast.LENGTH_SHORT).show()
            return
        }
        val contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", pdfFile)
        openPdfUri(contentUri)
    }

    private fun shareSavedReportPdf(record: SavedDailyReport) {
        val pdfFile = FlightRepository.getSavedReportFile(this, record)
        if (!pdfFile.exists()) {
            Toast.makeText(this, "Fișierul PDF nu mai există în arhiva locală.", Toast.LENGTH_SHORT).show()
            return
        }
        val contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", pdfFile)
        val summaryText = buildString {
            append("Raport zilnic SAT - Monitorizare trafic CND (${record.dateLabel})\n")
            append("Supervizor: ${record.supervisor}\n")
            append("Curse Turkish/Wizz: ${record.flightCount} | Pasageri: ${record.totalPassengers}\n")
            append("Articole interzise: ${record.totalProhibited}")
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Raport zilnic CND — ${record.dateLabel} (${record.supervisor})")
            putExtra(Intent.EXTRA_TEXT, summaryText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Trimite raportul pe WhatsApp / Email"))
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
        val goArounds = matches.count { it.type == FlightEventType.GO_AROUND }
        val emergencies = matches.count { it.type == FlightEventType.EMERGENCY }
        val gaPart = if (goArounds > 0) ", $goArounds ratări" else ""
        tvHistorySummary.text = "Rezultate: ${matches.size} (${landings} ateriz., ${takeoffs} decol.$gaPart, ${emergencies} urgențe)"
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
                        if (running) {
                            tvHeaderLivePill.text = "● LIVE"
                            tvHeaderLivePill.background = roundedBackground(
                                ContextCompat.getColor(this@MainActivity, R.color.status_live_bg),
                                999f
                            )
                            tvHeaderLivePill.setTextColor(
                                ContextCompat.getColor(this@MainActivity, R.color.status_live_fg)
                            )
                        } else {
                            tvHeaderLivePill.text = "● OPRIT"
                            tvHeaderLivePill.background = roundedBackground(
                                ContextCompat.getColor(this@MainActivity, R.color.status_off_bg),
                                999f
                            )
                            tvHeaderLivePill.setTextColor(
                                ContextCompat.getColor(this@MainActivity, R.color.status_off_fg)
                            )
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
                        val milCount = snap.activeAircraft.count { it.military }
                        val milPart = if (milCount > 0) " ($milCount MIL)" else ""
                        tvStatus.text = when {
                            snap.providerOk -> "Live (${snap.provider}) • ${snap.activeAircraft.size} pe radar$milPart • $updated"
                            snap.providerError != null -> "Sursă ADS-B indisponibilă temporar • $updated"
                            FlightRepository.isMonitoring.value -> "Se conectează la fluxul ADS-B…"
                            else -> "Monitorizare oprită. Apasă „Pornește monitorizarea 24/7”."
                        }

                        val activeEmergencies = snap.activeAircraft.filter {
                            it.phase == "emergency" ||
                                it.phase == "emergency_pan" ||
                                AirportConfig.emergencyInfo(it.squawk, it.emergencyStatus) != null
                        }
                        val activeGoArounds = snap.activeAircraft.filter { it.phase == "go_around" }
                        if (activeEmergencies.isNotEmpty()) {
                            val hasCritical = activeEmergencies.any { ac ->
                                val info = AirportConfig.emergencyInfo(ac.squawk, ac.emergencyStatus)
                                info != null && info.code != "PAN"
                            }
                            tvEmergencyBanner.background = roundedBackground(
                                Color.parseColor(if (hasCritical) "#DC2626" else "#EA580C"),
                                12f
                            )
                            tvEmergencyBanner.text = activeEmergencies.joinToString("\n") { ac ->
                                val info = AirportConfig.emergencyInfo(ac.squawk, ac.emergencyStatus)
                                val prefix = if (info?.code == "PAN") "ALERTĂ PAN-PAN" else "ALERTĂ URGENȚĂ"
                                val desc = info?.description ?: "COD URGENȚĂ ${ac.squawk}"
                                "$prefix: ${ac.callsign} • $desc • ${ac.distKm} km de LRCK (${ac.altAglFt ?: "—"} ft)"
                            }
                            tvEmergencyBanner.visibility = View.VISIBLE
                            updateTabAlertBadge(0, true)
                        } else if (activeGoArounds.isNotEmpty()) {
                            tvEmergencyBanner.background = roundedBackground(Color.parseColor("#E11D48"), 12f)
                            tvEmergencyBanner.text = activeGoArounds.joinToString("\n") { ac ->
                                "RATARE ATERIZARE (GO-AROUND): ${ac.callsign} • RWY ${ac.runway} • urcare ${ac.altAglFt ?: "—"} ft (${ac.gsKt ?: "—"} kt)"
                            }
                            tvEmergencyBanner.visibility = View.VISIBLE
                            updateTabAlertBadge(0, true)
                        } else {
                            tvEmergencyBanner.visibility = View.GONE
                            updateTabAlertBadge(0, false)
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
                        val goArounds = list.count { it.type == FlightEventType.GO_AROUND }
                        val emergencies = list.count { it.type == FlightEventType.EMERGENCY }
                        val gaPart = if (goArounds > 0) " • Ratări: $goArounds" else ""
                        tvStats.text = "Total: ${list.size} • Aterizări: $landings • Decolări: $takeoffs$gaPart • Urgențe: $emergencies"
                        applyHistoryFilter()
                        refreshDailyReportFlights(forceRender = false)
                    }
                }
                launch {
                    FlightRepository.savedReports.collect { savedList ->
                        renderSavedReportsHistory(savedList)
                    }
                }
                launch {
                    FlightRepository.droneSnapshot.collect { droneSnap ->
                        renderDroneSnapshot(droneSnap, clockFmt)
                    }
                }
                launch {
                    FlightRepository.droneAlerts.collect { alerts ->
                        renderDroneAlertsHistory(alerts)
                    }
                }
                launch {
                    FlightRepository.romatsaSnapshot.collect { romatsaSnap ->
                        renderRomatsaSnapshot(romatsaSnap, clockFmt)
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupDroneDobrogeaControls() {
        val current = FlightRepository.settings.value
        swDroneDobrogea.isChecked = current.notifyDroneDobrogea
        etDimapApiUrl.setText(current.dimapApiUrl)
        etDimapApiKey.setText(current.dimapApiKey)
        etUaApiKey.setText(current.ukraineAlarmApiKey)

        val initialChipId = when (current.droneAlertRadiusKm) {
            5 -> R.id.chip_drone_radius_5
            20 -> R.id.chip_drone_radius_20
            30 -> R.id.chip_drone_radius_30
            else -> R.id.chip_drone_radius_10
        }
        chipGroupDroneRadius.check(initialChipId)

        swDroneDobrogea.setOnCheckedChangeListener { _, checked ->
            val s = FlightRepository.settings.value
            FlightRepository.updateSettings(s.copy(notifyDroneDobrogea = checked))
        }

        chipGroupDroneRadius.setOnCheckedStateChangeListener { _, checkedIds ->
            val radiusKm = when (checkedIds.firstOrNull()) {
                R.id.chip_drone_radius_5 -> 5
                R.id.chip_drone_radius_20 -> 20
                R.id.chip_drone_radius_30 -> 30
                else -> 10
            }
            val s = FlightRepository.settings.value
            FlightRepository.updateSettings(s.copy(droneAlertRadiusKm = radiusKm))
            val snap = FlightRepository.droneSnapshot.value
            val reFiltered = snap.nearbyTargets.filter {
                it.insideDobrogea || it.distToDobrogeaKm <= radiusKm.toDouble()
            }
            FlightRepository.updateDroneSnapshot(snap.copy(alertTargets = reFiltered))
        }

        // Configurare sigură WebView pentru harta live DIMAP.LIVE
        webViewDimap.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webViewDimap.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val host = uri.host?.lowercase(Locale.ROOT) ?: ""
                if (uri.scheme == "https" && (
                        host == "dimap.live" || host.endsWith(".dimap.live") ||
                        host == "flightplan.romatsa.ro" || host.endsWith(".romatsa.ro")
                    )
                ) {
                    return false
                }
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }
        }
        // Permite pan/zoom fluid pe harta DIMAP / ROMATSA fără conflict cu NestedScrollView
        webViewDimap.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        pageView<Button>(R.id.btn_dimap_lite).setOnClickListener {
            currentDimapUrl = DobrogeaDroneMonitor.DIMAP_LITE_URL
            dimapWebLoadedOnce = true
            webViewDimap.loadUrl(currentDimapUrl)
        }
        pageView<Button>(R.id.btn_dimap_full).setOnClickListener {
            currentDimapUrl = DobrogeaDroneMonitor.DIMAP_FULL_URL
            dimapWebLoadedOnce = true
            webViewDimap.loadUrl(currentDimapUrl)
        }
        pageView<Button>(R.id.btn_romatsa_map_embed).setOnClickListener {
            currentDimapUrl = RomatsaAirspaceClient.ROMATSA_DRONES_MAP_URL
            dimapWebLoadedOnce = true
            webViewDimap.loadUrl(currentDimapUrl)
        }
        pageView<Button>(R.id.btn_dimap_reload).setOnClickListener {
            dimapWebLoadedOnce = true
            webViewDimap.loadUrl(currentDimapUrl)
        }
        pageView<Button>(R.id.btn_dimap_browser).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentDimapUrl)))
            }
        }

        btnRomatsaRefresh.setOnClickListener {
            refreshRomatsaDobrogeaNotices()
        }
        chipGroupRomatsaFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            currentRomatsaFilter = checkedIds.firstOrNull() ?: R.id.chip_romatsa_all
            renderRomatsaSnapshot(
                FlightRepository.romatsaSnapshot.value,
                SimpleDateFormat("HH:mm:ss", Locale("ro", "RO"))
            )
        }
        pageView<Button>(R.id.btn_open_romatsa_map).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RomatsaAirspaceClient.ROMATSA_DRONES_MAP_URL)))
            }
        }
        pageView<Button>(R.id.btn_open_mapn_aerofoto).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RomatsaAirspaceClient.MAPN_AEROFOTO_URL)))
            }
        }
        pageView<Button>(R.id.btn_romatsa_contacts).setOnClickListener {
            showRomatsaContactsDialog()
        }

        pageView<Button>(R.id.btn_drone_poll_now).setOnClickListener {
            val s = FlightRepository.settings.value
            if (!s.hasAnyDroneSourceConfigured) {
                Toast.makeText(
                    this,
                    "Adaugă mai întâi URL-ul și cheia DIMAP-API (sau cheia UkraineAlarm) mai jos.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Se verifică sursele Dobrogea…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                FlightMonitorService.pollDronesAndNotify(this@MainActivity)
            }
        }

        pageView<Button>(R.id.btn_drone_simulate).setOnClickListener {
            showSimulateDobrogeaDroneDialog()
        }

        pageView<Button>(R.id.btn_clear_sim_drone_alerts).setOnClickListener {
            val removed = FlightRepository.clearSimulatedDroneAlerts()
            val snap = FlightRepository.droneSnapshot.value
            val cleanNearby = snap.nearbyTargets.filterNot { it.simulated }
            val cleanAlerts = snap.alertTargets.filterNot { it.simulated }
            FlightRepository.updateDroneSnapshot(
                snap.copy(nearbyTargets = cleanNearby, alertTargets = cleanAlerts)
            )
            val msg = if (removed > 0) "Șterse $removed alerte simulate." else "Nu există alerte simulate."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        pageView<Button>(R.id.btn_clear_all_drone_alerts).setOnClickListener {
            FlightRepository.clearAllDroneAlerts()
            Toast.makeText(this, "Istoricul alertelor drone a fost golit.", Toast.LENGTH_SHORT).show()
        }

        pageView<Button>(R.id.btn_request_dimap_key).setOnClickListener {
            showRequestDimapKeyDialog()
        }

        pageView<Button>(R.id.btn_request_ua_key).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DobrogeaDroneMonitor.UKRAINE_ALARM_PORTAL_URL)))
            }
        }

        pageView<Button>(R.id.btn_save_drone_config).setOnClickListener {
            val urlVal = etDimapApiUrl.text?.toString()?.trim() ?: ""
            val keyVal = etDimapApiKey.text?.toString()?.trim() ?: ""
            val uaVal = etUaApiKey.text?.toString()?.trim() ?: ""

            if (urlVal.isNotEmpty() && !urlVal.startsWith("https://") && !urlVal.startsWith("http://api.dimap.live/")) {
                Toast.makeText(
                    this,
                    "URL-ul DIMAP-API trebuie să înceapă cu https:// (sau http://api.dimap.live/)",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val s = FlightRepository.settings.value
            FlightRepository.updateSettings(
                s.copy(
                    dimapApiUrl = urlVal,
                    dimapApiKey = keyVal,
                    ukraineAlarmApiKey = uaVal
                )
            )
            updateDroneApiSummary()
            Toast.makeText(this, "Configurare API Dobrogea salvată.", Toast.LENGTH_SHORT).show()
            if (urlVal.isNotBlank() || uaVal.isNotBlank()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    FlightMonitorService.pollDronesAndNotify(this@MainActivity)
                }
            }
        }
        updateDroneApiSummary()
    }

    private fun updateRadarSummaries() {
        val s = FlightRepository.settings.value
        val activeFlags = listOf(
            s.notifyLanding,
            s.notifyTakeoff,
            s.notifyApproach,
            s.notifyGoAround,
            s.notifyEmergency7700,
            s.notifyEmergencyPan,
            s.notifyEmergency7600,
            s.notifyEmergency7500
        ).count { it }
        val activeLabels = mutableListOf<String>()
        if (s.notifyLanding) activeLabels.add("Aterizări")
        if (s.notifyTakeoff) activeLabels.add("Decolări")
        if (s.notifyGoAround) activeLabels.add("Go-Around")
        if (s.anyEmergencyEnabled) activeLabels.add("Urgențe")
        val tail = if (activeLabels.isEmpty()) "Toate oprite" else activeLabels.joinToString(", ")
        tvRadarAlertsSummary.text = "$activeFlags/8 active • $tail"

        val openSkyTag = if (s.hasOpenSkyAuth) "OpenSky OAuth2 ✓" else "OpenSky"
        tvRadarSourcesSummary.text = if (s.flyItalyApiKey.isNotBlank()) {
            "7 surse gratuite (ADS-B + OGN + $openSkyTag) + FlyItalyADSB ✓"
        } else {
            "7 surse gratuite active (ADS-B + OGN + $openSkyTag)"
        }
    }

    private fun updateDroneApiSummary() {
        val s = FlightRepository.settings.value
        tvDroneApiSummary.text = when {
            s.dimapApiUrl.isNotBlank() -> {
                val shortHost = s.dimapApiUrl.removePrefix("https://").removePrefix("http://").take(34)
                "Preconfigurat: $shortHost ✓"
            }
            s.ukraineAlarmApiKey.isNotBlank() -> "Activ: UkraineAlarm API (Izmail/Odesa) ✓"
            else -> "Neconfigurat — apasă pentru a adăuga URL/cheie"
        }
    }

    private fun updateTabAlertBadge(tabIndex: Int, active: Boolean) {
        val tab = tabLayout.getTabAt(tabIndex) ?: return
        if (active) {
            tab.orCreateBadge.apply {
                backgroundColor = Color.parseColor("#DC2626")
                isVisible = true
            }
        } else {
            tab.removeBadge()
        }
    }

    private fun showSimulateDobrogeaDroneDialog() {
        val options = arrayOf(
            "Dronă zona Izmail (~4.6 km de Plauru / Tulcea)",
            "Dronă Marea Neagră (~3.9 km de Cap Midia 12 NM)",
            "Dronă ÎN spațiul aerian Dobrogea (Delta Dunării)"
        )
        val scenarios = arrayOf("izmail", "sea", "inside")
        AlertDialog.Builder(this)
            .setTitle("Simulare alertă dronă — Dobrogea")
            .setItems(options) { _, which ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val scenario = scenarios[which]
                lifecycleScope.launch(Dispatchers.IO) {
                    FlightMonitorService.triggerSimulatedDobrogeaDrone(this@MainActivity, scenario)
                }
                Toast.makeText(
                    this,
                    "Alertă simulată declanșată pentru sectorul Dobrogea.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun showRequestDimapKeyDialog() {
        val template = "Hello! I saw on @di_ukraine that DIMAP-API is available for free with an API key.\n" +
            "I am building a personal, non-commercial Android app for airspace awareness in Dobrogea, Romania (Tulcea / Constanța area).\n" +
            "Could you please provide an API key, the endpoint URL, and the API documentation (or a sample JSON response)?\n" +
            "Thank you very much for your work!"

        AlertDialog.Builder(this)
            .setTitle("Solicitare cheie gratuită DIMAP-API")
            .setMessage("Poți copia mesajul de mai jos și să îl trimiți pe Telegram la @di_helper:\n\n$template")
            .setPositiveButton("Copiază și deschide @di_helper") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Cerere DIMAP-API", template))
                Toast.makeText(this, "Mesaj copiat în clipboard.", Toast.LENGTH_SHORT).show()
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DobrogeaDroneMonitor.DIMAP_HELPER_TELEGRAM_URL)))
                }
            }
            .setNegativeButton("Închide", null)
            .show()
    }

    private fun renderDroneSnapshot(snap: DroneMonitorSnapshot, clockFmt: SimpleDateFormat) {
        val thresholdKm = FlightRepository.settings.value.droneAlertRadiusKm
        val updated = snap.updatedAtMs?.let { clockFmt.format(Date(it)) } ?: "—"

        val statusLines = buildString {
            append("Sector: Dobrogea (prag ≤ $thresholdKm km) • Actualizat: $updated\n")
            append("DIMAP-API: ${snap.dimapStatus}\n")
            append("Graniță Izmail/Odesa: ${snap.ukraineAlarmStatus}")
        }
        tvDroneStatus.text = statusLines

        // Banner roșu atât în tab-ul Drone, cât și în tab-ul Radar (doar pentru drone <= prag sau alerte critice Izmail/ROȘU)
        val bannerLines = mutableListOf<String>()
        for (t in snap.alertTargets.take(3)) {
            val simTag = if (t.simulated) " [TEST]" else ""
            val loc = if (t.insideDobrogea) {
                "ÎN DOBROGEA (${t.nearestLandmark})"
            } else {
                "${t.distToDobrogeaKm} km de ${t.nearestLandmark}"
            }
            bannerLines.add("ALERTĂ DRONĂ DOBROGEA$simTag: ${t.type} • $loc • ${t.distToCndKm} km ${t.bearingFromCnd} de CND")
        }
        for (alarm in snap.activeBorderAlarms.filter {
            it.regionName.contains("Izmail", ignoreCase = true) ||
                !it.alertType.contains("GALBEN", ignoreCase = true)
        }.take(2)) {
            bannerLines.add("ALERTĂ GRANIȚĂ DOBROGEA: ${alarm.regionName} (${alarm.alertType})")
        }

        if (bannerLines.isNotEmpty()) {
            val bannerText = bannerLines.joinToString("\n")
            tvDroneAlertBanner.text = bannerText
            tvDroneAlertBanner.visibility = View.VISIBLE
            tvRadarDroneBanner.text = "$bannerText\n(Apasă pentru a deschide tab-ul Drone & harta DIMAP)"
            tvRadarDroneBanner.visibility = View.VISIBLE
            tvDroneSectorPill.text = "● ALERTĂ ACTIVĂ"
            tvDroneSectorPill.background = roundedBackground(Color.parseColor("#DC2626"), 999f)
            tvDroneSectorPill.setTextColor(Color.WHITE)
            updateTabAlertBadge(TAB_DRONE, true)
        } else {
            tvDroneAlertBanner.visibility = View.GONE
            tvRadarDroneBanner.visibility = View.GONE
            tvDroneSectorPill.text = "● FĂRĂ ALERTE"
            tvDroneSectorPill.background = roundedBackground(
                ContextCompat.getColor(this, R.color.status_live_bg),
                999f
            )
            tvDroneSectorPill.setTextColor(ContextCompat.getColor(this, R.color.status_live_fg))
            updateTabAlertBadge(TAB_DRONE, false)
        }

        // Lista țintelor apropiate (< 50 km de Dobrogea)
        if (snap.nearbyTargets.isEmpty() && snap.activeBorderAlarms.isEmpty()) {
            tvDroneTargetsList.text = getString(R.string.drone_targets_empty)
        } else {
            val sb = StringBuilder()
            for (t in snap.nearbyTargets) {
                val badge = when {
                    t.insideDobrogea -> "[ÎN DOBROGEA]"
                    t.distToDobrogeaKm <= thresholdKm -> "[ALERTĂ ≤ ${thresholdKm} km]"
                    else -> "[APROPIERE]"
                }
                val simTag = if (t.simulated) " [TEST]" else ""
                val distText = if (t.insideDobrogea) {
                    "în spațiul aerian (${t.nearestLandmark})"
                } else {
                    "${t.distToDobrogeaKm} km de ${t.nearestLandmark}"
                }
                sb.append("$badge$simTag ${t.type}\n")
                sb.append("  • Distanță Dobrogea: $distText\n")
                sb.append("  • Față de CND: ${t.distToCndKm} km (${t.bearingFromCnd})")
                if (t.courseDeg != null) sb.append(" • Curs: ${t.courseDeg}°")
                if (t.speedKmh != null) sb.append(" • ~${t.speedKmh} km/h")
                if (t.label.isNotBlank()) sb.append("\n  • ${t.label}")
                sb.append("\n\n")
            }
            for (alarm in snap.activeBorderAlarms) {
                sb.append("[ALERTĂ RAION GRANIȚĂ] ${alarm.regionName}\n")
                sb.append("  • Tip: ${alarm.alertType} • Sursă: ${alarm.source}\n\n")
            }
            tvDroneTargetsList.text = sb.toString().trim()
        }
    }

    private fun renderDroneAlertsHistory(alerts: List<DroneAlertRecord>) {
        if (alerts.isEmpty()) {
            tvDroneAlertsHistory.text = getString(R.string.drone_alerts_history_empty)
            return
        }
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale("ro", "RO"))
        val text = alerts.take(20).joinToString("\n\n") { a ->
            val stamp = fmt.format(Date(a.timestampMs))
            "• [$stamp] ${a.title}\n  ${a.details} (${a.source})"
        }
        tvDroneAlertsHistory.text = text
    }

    private fun refreshRomatsaDobrogeaNotices() {
        romatsaLoadedOnce = true
        btnRomatsaRefresh.isEnabled = false
        btnRomatsaRefresh.text = "Se încarcă…"
        tvRomatsaStatus.text = getString(R.string.romatsa_loading)

        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) {
                RomatsaAirspaceClient.fetchDobrogeaAuthorizations()
            }
            FlightRepository.updateRomatsaSnapshot(snap)
            btnRomatsaRefresh.isEnabled = true
            btnRomatsaRefresh.text = getString(R.string.btn_romatsa_refresh)
        }
    }

    private fun renderRomatsaSnapshot(snap: RomatsaAirspaceSnapshot, clockFmt: SimpleDateFormat) {
        val updatedSuffix = snap.updatedAtMs?.let { " • Actualizat: ${clockFmt.format(Date(it))}" }.orEmpty()
        tvRomatsaStatus.text = "${snap.statusMessage}$updatedSuffix"

        val allNotices = snap.notices
        if (snap.updatedAtMs != null) {
            val uasCount = allNotices.count { it.category == RomatsaNoticeCategory.UAS_DRONE }
            val restrCount = allNotices.count { it.category == RomatsaNoticeCategory.RESTRICTED_UAS }
            val mapnCount = allNotices.count {
                it.category == RomatsaNoticeCategory.DANGER_AREA || it.category == RomatsaNoticeCategory.MAPN_MILITARY
            }
            chipGroupRomatsaFilter.findViewById<TextView>(R.id.chip_romatsa_all)?.text =
                "${getString(R.string.romatsa_filter_all)} (${allNotices.size})"
            chipGroupRomatsaFilter.findViewById<TextView>(R.id.chip_romatsa_uas)?.text =
                "${getString(R.string.romatsa_filter_uas)} ($uasCount)"
            chipGroupRomatsaFilter.findViewById<TextView>(R.id.chip_romatsa_restricted)?.text =
                "${getString(R.string.romatsa_filter_restricted)} ($restrCount)"
            chipGroupRomatsaFilter.findViewById<TextView>(R.id.chip_romatsa_mapn)?.text =
                "${getString(R.string.romatsa_filter_mapn)} ($mapnCount)"
        }

        val filtered = when (currentRomatsaFilter) {
            R.id.chip_romatsa_uas -> allNotices.filter { it.category == RomatsaNoticeCategory.UAS_DRONE }
            R.id.chip_romatsa_restricted -> allNotices.filter { it.category == RomatsaNoticeCategory.RESTRICTED_UAS }
            R.id.chip_romatsa_mapn -> allNotices.filter {
                it.category == RomatsaNoticeCategory.DANGER_AREA || it.category == RomatsaNoticeCategory.MAPN_MILITARY
            }
            else -> allNotices
        }

        romatsaNoticesContainer.removeAllViews()
        if (filtered.isEmpty()) {
            tvRomatsaEmpty.text = if (!snap.ok && snap.updatedAtMs != null) {
                "Nu s-au putut prelua live autorizațiile ROMATSA. Verifică conexiunea la internet și apasă „Actualizează”."
            } else {
                getString(R.string.romatsa_empty)
            }
            tvRomatsaEmpty.visibility = View.VISIBLE
            return
        }

        tvRomatsaEmpty.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        val visible = filtered.take(25)
        for (notice in visible) {
            val card = inflater.inflate(R.layout.item_romatsa_notice, romatsaNoticesContainer, false)
            val tvBadge = card.findViewById<TextView>(R.id.tv_rn_badge)
            val tvId = card.findViewById<TextView>(R.id.tv_rn_id)
            val tvState = card.findViewById<TextView>(R.id.tv_rn_state)
            val tvTitle = card.findViewById<TextView>(R.id.tv_rn_title)
            val tvMeta = card.findViewById<TextView>(R.id.tv_rn_meta)
            val tvSummary = card.findViewById<TextView>(R.id.tv_rn_summary)

            val (badgeLabel, badgeColorHex) = when (notice.category) {
                RomatsaNoticeCategory.UAS_DRONE -> "DRONĂ / UAS" to "#2563EB"
                RomatsaNoticeCategory.RESTRICTED_UAS -> "RESTRICȚIE UAS" to "#D97706"
                RomatsaNoticeCategory.DANGER_AREA -> "POLIGON MApN" to "#DC2626"
                RomatsaNoticeCategory.MAPN_MILITARY -> "ZBOR MIL MApN" to "#7C3AED"
            }
            tvBadge.text = badgeLabel
            tvBadge.background = roundedBackground(Color.parseColor(badgeColorHex), 999f)

            val qPart = if (notice.qCode.isNotBlank()) " (${notice.qCode})" else ""
            tvId.text = "${notice.notamId} • ${notice.location}$qPart"

            if (notice.activeNow) {
                tvState.text = "ACTIV"
                tvState.background = roundedBackground(Color.parseColor("#DCFCE7"), 999f)
                tvState.setTextColor(Color.parseColor("#15803D"))
            } else {
                tvState.text = "PROGRAMAT"
                tvState.background = roundedBackground(Color.parseColor("#E0F2FE"), 999f)
                tvState.setTextColor(Color.parseColor("#0369A1"))
            }

            tvTitle.text = notice.title
            tvMeta.text = buildString {
                append("${notice.authority} • ${notice.validFromUtc} → ${notice.validToUtc}")
                if (notice.verticalLimits.isNotBlank()) {
                    append("\nAltitudine: ${notice.verticalLimits}")
                }
                if (notice.schedule.isNotBlank()) {
                    val shortSched = if (notice.schedule.length > 72) {
                        notice.schedule.take(69) + "…"
                    } else {
                        notice.schedule
                    }
                    append(if (notice.verticalLimits.isNotBlank()) " • Orar: $shortSched" else "\nOrar: $shortSched")
                }
            }
            tvSummary.text = notice.summaryText
            card.setOnClickListener { showRomatsaNoticeDetailsDialog(notice) }
            romatsaNoticesContainer.addView(card)
        }

        if (filtered.size > visible.size) {
            val tvMore = TextView(this).apply {
                text = "+ încă ${filtered.size - visible.size} rezervări/NOTAM-uri active în Dobrogea. Folosește filtrele de mai sus sau harta ROMATSA."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 11.5f
                setPadding(4, 10, 4, 2)
            }
            romatsaNoticesContainer.addView(tvMore)
        }
    }

    private fun showRomatsaNoticeDetailsDialog(notice: RomatsaNotice) {
        val details = buildString {
            append("NOTAM: ${notice.notamId} (${notice.location})\n")
            if (notice.qCode.isNotBlank()) append("Cod Q: ${notice.qCode}\n")
            append("Autoritate: ${notice.authority}\n")
            append("Stare: ${if (notice.activeNow) "ACTIV ACUM" else "PROGRAMAT"}\n")
            if (notice.landmark.isNotBlank()) append("Zonă Dobrogea: ${notice.landmark}\n")
            append("Valabilitate: ${notice.validFromUtc} → ${notice.validToUtc}\n")
            if (notice.schedule.isNotBlank()) append("Orar activare (D): ${notice.schedule}\n")
            if (notice.verticalLimits.isNotBlank()) append("Limite verticale (F/G): ${notice.verticalLimits}\n")
            if (notice.centerLat != null && notice.centerLon != null) {
                val radPart = notice.radiusNm?.let { " • rază $it NM" }.orEmpty()
                append(
                    String.format(
                        Locale.US,
                        "Coordonate reper: %.4f°N, %.4f°E%s\n",
                        notice.centerLat,
                        notice.centerLon,
                        radPart
                    )
                )
            }
            append("\nText oficial ROMATSA:\n")
            append(notice.rawNotam)
        }

        AlertDialog.Builder(this)
            .setTitle(notice.title)
            .setMessage(details)
            .setPositiveButton("Harta ROMATSA UAS") { _, _ ->
                currentDimapUrl = RomatsaAirspaceClient.ROMATSA_DRONES_MAP_URL
                dimapWebLoadedOnce = true
                webViewDimap.loadUrl(currentDimapUrl)
                Toast.makeText(
                    this,
                    "Harta oficială ROMATSA UAS a fost încărcată în panoul de hartă de mai sus.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("Copiază NOTAM") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("NOTAM ${notice.notamId}", notice.rawNotam))
                Toast.makeText(this, "NOTAM ${notice.notamId} copiat în clipboard.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Închide", null)
            .show()
    }

    private fun showRomatsaContactsDialog() {
        val msg = buildString {
            append("Contacte oficiale autorizare zboruri UAS / drone în Dobrogea:\n\n")
            append("1. ROMATSA Constanța — LRCK (zonele RZ1013 / RZ1014, rază 16 NM):\n")
            append("   • Tel: +40 241 258 872 / +40 737 552 250\n")
            append("   • Email: drone.lrck@romatsa.ro\n\n")
            append("2. ROMATSA Tulcea — LRTC:\n")
            append("   • Tel: +40 240 511 581 / +40 737 552 253\n")
            append("   • Email: twr.tulcea@romatsa.ro\n\n")
            append("3. MApN — Aviz filmare/fotografiere aeriană (Aerofoto):\n")
            append("   • Portal: aerofoto.mapn.ro\n")
            append("   • Email: aerofoto@mapn.ro\n")
            append("   • Tel: +40 21 410 01 43 (L–V, 08:00–15:00)\n")
            append("   • Acces zone militare (RoAF): fdex@roaf.ro\n\n")
            append("Notă: NOTAM-urile ROMATSA afișează public toate rezervările de spațiu aerian, zborurile UAS publicate prin NOTAM, zonele restricționate (Delta/frontieră) și poligoanele/coridoarele MApN. Avizele nominale individuale emise în portalul securizat aerofoto.mapn.ro sunt confidențiale și vizibile doar solicitantului și autorităților.")
        }

        AlertDialog.Builder(this)
            .setTitle("Contacte ROMATSA / MApN — Dobrogea")
            .setMessage(msg)
            .setPositiveButton("Portal MApN Aerofoto") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RomatsaAirspaceClient.MAPN_AEROFOTO_URL)))
                }
            }
            .setNeutralButton("Harta ROMATSA UAS") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RomatsaAirspaceClient.ROMATSA_DRONES_MAP_URL)))
                }
            }
            .setNegativeButton("Închide", null)
            .show()
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

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_DRONE = 4
    }
}
