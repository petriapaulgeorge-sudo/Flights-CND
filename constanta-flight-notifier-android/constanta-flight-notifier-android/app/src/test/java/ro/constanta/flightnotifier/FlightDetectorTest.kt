package ro.constanta.flightnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.constanta.flightnotifier.core.AdsbClient
import ro.constanta.flightnotifier.core.AirportConfig
import ro.constanta.flightnotifier.core.DailyReportBuilder
import ro.constanta.flightnotifier.core.EventSearch
import ro.constanta.flightnotifier.core.FlightDetector
import ro.constanta.flightnotifier.core.RomatsaAirspaceClient
import ro.constanta.flightnotifier.core.RunwayStatusClient
import ro.constanta.flightnotifier.data.FlightRepository
import ro.constanta.flightnotifier.model.EventSearchFilter
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.model.RawAircraft
import ro.constanta.flightnotifier.model.RomatsaNoticeCategory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class FlightDetectorTest {

    @Test
    fun detectsClassicTakeoffOnRunway36() {
        val detector = FlightDetector()
        val t0 = 1_700_000_000_000L

        val r1 = detector.processSnapshot(
            listOf(
                RawAircraft(
                    hex = "4a1234",
                    callsign = "WZZ101",
                    lat = AirportConfig.LAT - 0.005,
                    lon = AirportConfig.LON,
                    onGround = true,
                    gsKt = 35,
                    trackDeg = 2,
                    verticalRateFpm = 0
                )
            ),
            t0
        )
        assertEquals(0, r1.events.size)

        val r2 = detector.processSnapshot(
            listOf(
                RawAircraft(
                    hex = "4a1234",
                    callsign = "WZZ101",
                    lat = AirportConfig.LAT + 0.025,
                    lon = AirportConfig.LON,
                    altBaroFt = AirportConfig.ELEVATION_FT + 650,
                    onGround = false,
                    gsKt = 155,
                    trackDeg = 3,
                    verticalRateFpm = 1800
                )
            ),
            t0 + 12_000L
        )
        val takeoffs = r2.events.filter { it.type == FlightEventType.TAKEOFF }
        assertEquals(1, takeoffs.size)
        assertEquals("36", takeoffs.first().runway)
    }

    @Test
    fun detectsLandingOnRunway18() {
        val detector = FlightDetector()
        val t0 = 1_700_000_000_000L

        detector.processSnapshot(
            listOf(
                RawAircraft(
                    hex = "4c5555",
                    callsign = "THY1048",
                    lat = AirportConfig.LAT + 0.06,
                    lon = AirportConfig.LON,
                    altBaroFt = AirportConfig.ELEVATION_FT + 1800,
                    onGround = false,
                    gsKt = 150,
                    trackDeg = 180,
                    verticalRateFpm = -750
                )
            ),
            t0
        )

        val r2 = detector.processSnapshot(
            listOf(
                RawAircraft(
                    hex = "4c5555",
                    callsign = "THY1048",
                    lat = AirportConfig.LAT + 0.004,
                    lon = AirportConfig.LON,
                    onGround = true,
                    gsKt = 92,
                    trackDeg = 180,
                    verticalRateFpm = 0
                )
            ),
            t0 + 25_000L
        )
        val landings = r2.events.filter { it.type == FlightEventType.LANDING }
        assertEquals(1, landings.size)
        assertEquals("18", landings.first().runway)
    }

    @Test
    fun detectsGoAroundWithoutFalseTakeoffAndAnalyzesProbableCause() {
        val detector = FlightDetector()
        val t0 = 1_700_000_000_000L

        // 1. Coborâre pe finală spre Pista 18 (altitudine 1400 ft -> 420 ft)
        detector.processSnapshot(
            listOf(
                RawAircraft(
                    hex = "4c7777",
                    callsign = "WZZ3128",
                    lat = AirportConfig.LAT + 0.055,
                    lon = AirportConfig.LON,
                    altBaroFt = AirportConfig.ELEVATION_FT + 1400,
                    onGround = false,
                    gsKt = 148,
                    trackDeg = 180,
                    verticalRateFpm = -750
                )
            ),
            t0
        )
        detector.processSnapshot(
            listOf(
                RawAircraft(
                    hex = "4c7777",
                    callsign = "WZZ3128",
                    lat = AirportConfig.LAT + 0.015,
                    lon = AirportConfig.LON,
                    altBaroFt = AirportConfig.ELEVATION_FT + 420,
                    onGround = false,
                    gsKt = 138,
                    trackDeg = 180,
                    verticalRateFpm = -680
                )
            ),
            t0 + 18_000L
        )

        // 2. Ratare aterizare (Go-Around): urcă brusc de la 420 ft la 1150 ft cu +1850 fpm
        val rGa = detector.processSnapshot(
            listOf(
                RawAircraft(
                    hex = "4c7777",
                    callsign = "WZZ3128",
                    lat = AirportConfig.LAT - 0.008,
                    lon = AirportConfig.LON,
                    altBaroFt = AirportConfig.ELEVATION_FT + 1150,
                    onGround = false,
                    gsKt = 168,
                    trackDeg = 180,
                    verticalRateFpm = 1850
                )
            ),
            t0 + 36_000L
        )
        val goArounds = rGa.events.filter { it.type == FlightEventType.GO_AROUND }
        val takeoffs = rGa.events.filter { it.type == FlightEventType.TAKEOFF }
        assertEquals(1, goArounds.size)
        assertEquals(0, takeoffs.size)
        assertEquals(420, goArounds.first().minApproachAltFt)
        assertEquals("go_around", rGa.activeAircraft.first().phase)

        // 3. Verificare corelare cu METAR (vânt din spate + rafale + IFR pe Pista 18)
        val badMetar = RunwayStatusClient.parseMetar("LRCK 201200Z 36015G28KT 2000 +RA BKN005 08/07 Q1005 NOSIG")
        val badSummary = RunwayStatusClient.analyzeGoAroundReason(
            runway = "18",
            minApproachAltFt = 420,
            conditions = badMetar,
            localOperationalState = "Operațională",
            localRwyccCode = "3 — Mediu"
        )
        assertTrue(badSummary.contains("420 ft"))
        assertTrue(badSummary.contains("vânt din spate"))
        assertTrue(badSummary.contains("RWYCC 3"))

        // 4. Verificare corelare pe vreme bună (Nivel 1/5)
        val clearMetar = RunwayStatusClient.parseMetar("LRCK 201200Z 18005KT CAVOK 20/10 Q1018 NOSIG")
        val okSummary = RunwayStatusClient.analyzeGoAroundReason(
            runway = "18",
            minApproachAltFt = 420,
            conditions = clearMetar,
            localOperationalState = "Operațională",
            localRwyccCode = "6 — Uscat"
        )
        assertTrue(okSummary.contains("Condiții meteo/pistă bune"))
    }

    @Test
    fun ignoresCruiseOverflights() {
        val detector = FlightDetector()
        val t0 = 1_700_000_000_000L

        val res = detector.processSnapshot(
            listOf(
                RawAircraft(
                    hex = "3c1111",
                    callsign = "DLH1420",
                    lat = AirportConfig.LAT,
                    lon = AirportConfig.LON,
                    altBaroFt = 36_000,
                    onGround = false,
                    gsKt = 460,
                    trackDeg = 180,
                    verticalRateFpm = 0
                )
            ),
            t0
        )
        assertEquals(0, res.events.size)
    }

    @Test
    fun detectsEmergencySquawkWithin100KmAndIgnoresFarNonCnd() {
        val detector = FlightDetector()
        val t0 = 1_700_000_000_000L

        // ~80 km Nord de LRCK cu squawk 7700 -> trebuie detectat!
        val rNear = detector.processSnapshot(
            listOf(
                RawAircraft(
                    hex = "4e7700",
                    callsign = "BAW881",
                    squawk = "7700",
                    lat = AirportConfig.LAT + 0.72, // ~80 km
                    lon = AirportConfig.LON,
                    altBaroFt = 22_000,
                    onGround = false,
                    gsKt = 380,
                    trackDeg = 180,
                    verticalRateFpm = -1500
                ),
                // ~145 km Nord de LRCK (nelegat de CND) cu squawk 7600 -> ignorat
                RawAircraft(
                    hex = "4e7600",
                    callsign = "AFR112",
                    squawk = "7600",
                    lat = AirportConfig.LAT + 1.30, // ~144 km
                    lon = AirportConfig.LON,
                    altBaroFt = 34_000,
                    onGround = false,
                    gsKt = 450,
                    trackDeg = 90,
                    verticalRateFpm = 0
                )
            ),
            t0
        )

        val emergencies = rNear.events.filter { it.type == FlightEventType.EMERGENCY }
        assertEquals(1, emergencies.size)
        assertEquals("BAW881", emergencies.first().callsign)
        assertEquals("7700", emergencies.first().squawk)
        assertEquals("7700", emergencies.first().emergencyCode)
    }

    @Test
    fun detectsPanPanLifeguardMinFuelAnd7600And7500Within100Km() {
        val detector = FlightDetector()
        val t0 = 1_700_000_000_000L

        val res = detector.processSnapshot(
            listOf(
                RawAircraft(
                    hex = "4a0pan",
                    callsign = "SMURD1",
                    squawk = "2341",
                    emergencyStatus = "lifeguard",
                    lat = AirportConfig.LAT + 0.25, // ~28 km
                    lon = AirportConfig.LON,
                    altBaroFt = 8_500,
                    onGround = false,
                    gsKt = 240,
                    trackDeg = 180,
                    verticalRateFpm = -600
                ),
                RawAircraft(
                    hex = "4a7600",
                    callsign = "TAR760",
                    squawk = "7600",
                    lat = AirportConfig.LAT + 0.30, // ~33 km
                    lon = AirportConfig.LON,
                    altBaroFt = 14_000,
                    onGround = false,
                    gsKt = 290,
                    trackDeg = 180,
                    verticalRateFpm = 0
                ),
                RawAircraft(
                    hex = "4a7500",
                    callsign = "TEST75",
                    squawk = "7500",
                    lat = AirportConfig.LAT - 0.35, // ~39 km
                    lon = AirportConfig.LON,
                    altBaroFt = 18_000,
                    onGround = false,
                    gsKt = 360,
                    trackDeg = 10,
                    verticalRateFpm = 0
                )
            ),
            t0
        )

        val emergencies = res.events.filter { it.type == FlightEventType.EMERGENCY }
        assertEquals(3, emergencies.size)
        assertEquals("PAN", emergencies.first { it.callsign == "SMURD1" }.emergencyCode)
        assertEquals("7600", emergencies.first { it.callsign == "TAR760" }.emergencyCode)
        assertEquals("7500", emergencies.first { it.callsign == "TEST75" }.emergencyCode)
        assertEquals("emergency_pan", res.activeAircraft.first { it.callsign == "SMURD1" }.phase)
        assertEquals("PAN", AirportConfig.emergencyInfo("2000", "minfuel")?.code)
    }

    @Test
    fun parsesOpenSkyMissedFlightsJson() {
        val sampleJson = """
            [
              {
                "icao24": "4ba999",
                "firstSeen": 1700001000,
                "estDepartureAirport": "LTFM",
                "lastSeen": 1700004600,
                "estArrivalAirport": "LRCK",
                "callsign": "THY1048 "
              }
            ]
        """.trimIndent()
        val parsed = AdsbClient.parseOpenSkyFlightsJson(sampleJson, FlightEventType.LANDING)
        assertEquals(1, parsed.size)
        val evt = parsed.first()
        assertEquals("THY1048", evt.callsign)
        assertEquals("4ba999", evt.hex)
        assertEquals(1700004600L * 1000L, evt.timestampMs)
        assertEquals("LTFM → LRCK", evt.route)
        assertEquals("opensky_backfill", evt.detectionReason)
    }

    @Test
    fun parsesAdsbdbFlightRouteAndResolvesAirlineAndAirports() {
        val sampleJson = """
            {
              "response": {
                "flightroute": {
                  "callsign": "THY1048",
                  "airline": { "name": "Turkish Airlines" },
                  "origin": {
                    "name": "Istanbul Airport",
                    "iata_code": "IST",
                    "icao_code": "LTFM",
                    "municipality": "Istanbul",
                    "country_name": "Turkey"
                  },
                  "destination": {
                    "name": "Mihail Kogălniceanu International Airport",
                    "iata_code": "CND",
                    "icao_code": "LRCK",
                    "municipality": "Constanța",
                    "country_name": "Romania"
                  }
                }
              }
            }
        """.trimIndent()

        val parsed = AdsbClient.parseAdsbdbFlightRouteJson(sampleJson)
        assertEquals("Turkish Airlines", parsed?.airline)
        assertTrue(parsed?.origin?.contains("Istanbul Airport") == true)
        assertTrue(parsed?.origin?.contains("IST / LTFM") == true)
        assertTrue(parsed?.destination?.contains("CND / LRCK") == true)
        assertTrue(parsed?.destination?.contains("Constanța") == true)
        assertEquals("Wizz Air", AdsbClient.resolveKnownAirline("WZZ3128"))
        assertTrue(AdsbClient.resolveKnownAirport("LTN").contains("Londra Luton"))
    }

    @Test
    fun parsesMlatLastPositionMilitaryAndMergesMultipleNetworks() {
        val feedA = """
            {
              "ac": [
                {
                  "hex": "ae1234",
                  "type": "mlat",
                  "flight": "RCH842 ",
                  "dbFlags": 1,
                  "lastPosition": { "lat": 44.41, "lon": 28.52, "seen_pos": 12.5 },
                  "alt_baro": 18000
                }
              ]
            }
        """.trimIndent()
        val feedB = """
            {
              "ac": [
                {
                  "hex": "ae1234",
                  "type": "adsb_icao",
                  "flight": "RCH842",
                  "t": "C17",
                  "r": "07-7175",
                  "lat": 44.41,
                  "lon": 28.52,
                  "gs": 320.0,
                  "track": 180.0
                },
                {
                  "hex": "4a0101",
                  "flight": "ROF201",
                  "lat": 44.37,
                  "lon": 28.49,
                  "alt_baro": 3500
                }
              ]
            }
        """.trimIndent()

        val listA = AdsbClient.parseReadsbV2(feedA)
        assertEquals(1, listA.size)
        assertTrue(listA.first().military)
        assertEquals("mlat", listA.first().sourceType)

        val listB = AdsbClient.parseReadsbV2(feedB)
        val merged = AdsbClient.mergeAircraftLists(listOf(listA, listB))
        assertEquals(2, merged.size)

        val rch = merged.first { it.hex == "ae1234" }
        assertTrue(rch.military)
        assertEquals("C17", rch.aircraftType)
        assertEquals("07-7175", rch.registration)
        assertEquals(18000, rch.altBaroFt)
        assertEquals(320, rch.gsKt)

        val rof = merged.first { it.hex == "4a0101" }
        assertTrue(rof.military)
    }

    @Test
    fun parsesLrckMetarAndComputesActiveRunwayAndSurface() {
        val sampleMetar = "LRCK 202000Z 01014KT 6000 -RA BKN012 02/01 Q1018 NOSIG"
        val cond = RunwayStatusClient.parseMetar(sampleMetar)
        assertTrue(cond.ok)
        assertEquals("1018 hPa", cond.qnhText)
        assertTrue(cond.recommendedRunway.contains("Pista 36"))
        assertTrue(cond.surfaceEstimate.contains("Umedă"))
        assertTrue(cond.icingRisk)
        assertEquals("MVFR", cond.flightCategory)
    }

    @Test
    fun evaluatesRunwayOperabilityScale1To5() {
        // Nivel 1 (Verde): CAVOK, vânt slab, RWYCC 6, Operațională
        val clearCond = RunwayStatusClient.parseMetar("LRCK 201200Z 36006KT CAVOK 22/10 Q1016 NOSIG")
        val lvl1 = RunwayStatusClient.evaluateOperability(clearCond, "Operațională", "6 — Uscat")
        assertEquals(1, lvl1.level)
        assertEquals("#16A34A", lvl1.colorHex)

        // Nivel 2 (Verde-galben): ploaie ușoară, RWYCC 5
        val rainCond = RunwayStatusClient.parseMetar("LRCK 201200Z 36010KT 8000 -RA BKN020 12/10 Q1012 NOSIG")
        val lvl2 = RunwayStatusClient.evaluateOperability(rainCond, "Operațională", "5 — Umed / Bun")
        assertEquals(2, lvl2.level)
        assertEquals("#65A30D", lvl2.colorHex)

        // Nivel 3 (Galben): IFR / risc polei
        val ifrCond = RunwayStatusClient.parseMetar("LRCK 201200Z 01014KT 3000 -RA BKN008 01/00 Q1010 NOSIG")
        val lvl3 = RunwayStatusClient.evaluateOperability(ifrCond, "Operațională", "4 — Mediu-Bun")
        assertEquals(3, lvl3.level)
        assertEquals("#D97706", lvl3.colorHex)

        // Nivel 4 (Portocaliu): Furtună / LIFR
        val stormCond = RunwayStatusClient.parseMetar("LRCK 201200Z 27028G38KT 1200 +TSRA BKN004CB 16/15 Q1002 NOSIG")
        val lvl4 = RunwayStatusClient.evaluateOperability(stormCond, "Operațională", "3 — Mediu")
        assertEquals(4, lvl4.level)
        assertEquals("#EA580C", lvl4.colorHex)

        // Nivel 5 (Roșu): Pistă închisă sau SNOCLO sau RWYCC 0
        val lvl5Closed = RunwayStatusClient.evaluateOperability(clearCond, "Închisă", "6 — Uscat")
        assertEquals(5, lvl5Closed.level)
        assertEquals("#DC2626", lvl5Closed.colorHex)

        val snocloCond = RunwayStatusClient.parseMetar("LRCK 201200Z 02018KT 0400 +SN VV002 M03/M04 Q1004 R36/SNOCLO")
        val lvl5Snow = RunwayStatusClient.evaluateOperability(snocloCond, "Operațională", "0 — Gheață / Critic")
        assertEquals(5, lvl5Snow.level)
    }

    @Test
    fun filtersEventsByTimeRangeTypeAndQuery() {
        val t1 = 1_700_000_000_000L
        val t2 = t1 + 3_600_000L
        val t3 = t1 + 86_400_000L

        val events = listOf(
            FlightEvent("1", FlightEventType.LANDING, t1, "4a1", "WZZ3128", "HA-LZN", "A21N", "Wizz Air", "LTN → CND", "18", 1.1, 100, 130, "air_to_ground"),
            FlightEvent("2", FlightEventType.TAKEOFF, t2, "4a2", "THY1048", "TC-JSO", "B738", "Turkish Airlines", "CND → IST", "36", 2.0, 600, 160, "ground_to_air"),
            FlightEvent("3", FlightEventType.LANDING, t3, "4a3", "ROT209", "YR-ATJ", "AT76", "TAROM", "OTP → CND", "18", 0.9, 80, 110, "air_to_ground")
        )

        val inWindow = EventSearch.filterEvents(events, EventSearchFilter(fromMs = t1, toMs = t2))
        assertEquals(2, inWindow.size)

        val onlyTakeoff = EventSearch.filterEvents(events, EventSearchFilter(type = FlightEventType.TAKEOFF))
        assertEquals(1, onlyTakeoff.size)
        assertEquals("THY1048", onlyTakeoff.first().callsign)

        val byQuery = EventSearch.filterEvents(events, EventSearchFilter(query = "tarom"))
        assertEquals(1, byQuery.size)
        assertEquals("ROT209", byQuery.first().callsign)
    }

    @Test
    fun pairsSameDayLandingAndTakeoffForTurkishAndWizzOnly() {
        val dayStart = 1_700_000_000_000L
        val dayEnd = dayStart + 86_399_000L

        val events = listOf(
            FlightEvent("w1", FlightEventType.LANDING, dayStart + 3_600_000L, "471f01", "WZZ3128", "HA-LZN", "A21N", "Wizz Air", "LTN → CND", "18", 1.0, 90, 132, "air_to_ground"),
            FlightEvent("w2", FlightEventType.TAKEOFF, dayStart + 7_200_000L, "471f01", "WZZ3129", "HA-LZN", "A21N", "Wizz Air", "CND → LTN", "36", 2.1, 650, 162, "ground_to_air"),
            FlightEvent("t1", FlightEventType.LANDING, dayStart + 10_000_000L, "4ba999", "THY1048", "TC-JSO", "B738", "Turkish Airlines", "IST → CND", "18", 1.2, 100, 136, "air_to_ground"),
            FlightEvent("t2", FlightEventType.TAKEOFF, dayStart + 13_600_000L, "4ba999", "THY1049", "TC-JSO", "B738", "Turkish Airlines", "CND → IST", "18", 1.9, 700, 165, "ground_to_air"),
            FlightEvent("r1", FlightEventType.LANDING, dayStart + 5_000_000L, "4a1111", "ROT209", "YR-ATJ", "AT76", "TAROM", "OTP → CND", "18", 1.0, 80, 110, "air_to_ground")
        )

        val rows = DailyReportBuilder.buildDailyFlightsInRange(events, dayStart, dayEnd)
        assertEquals(2, rows.size)

        val wizz = rows[0]
        assertEquals("Wizz Air", wizz.airline)
        assertEquals("HA-LZN", wizz.registration)
        assertEquals("A21N", wizz.aircraftType)
        assertNotEquals("—", wizz.landingTime)
        assertNotEquals("—", wizz.takeoffTime)

        val thy = rows[1]
        assertEquals("Turkish Airlines", thy.airline)
        assertEquals("TC-JSO", thy.registration)
        assertEquals("B738", thy.aircraftType)
        assertNotEquals("—", thy.landingTime)
        assertNotEquals("—", thy.takeoffTime)
    }

    @Test
    fun parsesOgnLiveXmlAndDdbJsonAndDeduplicatesWithAdsb() {
        val sampleOgnXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <markers>
              <m a="44.041200,28.615400,SP,YR-SCP,762,12:53:14,18,165,74,0.8,8,LRTZ,4a8123,aa112233"/>
              <m a="44.110000,28.550000,_b8,8bde76b8,610,12:53:10,25,103,131,-0.4,3,Constanta,0,8bde76b8"/>
              <m a="44.200000,28.400000,GL,YR-1024,950,12:52:50,40,270,90,1.2,1,Dobrogea,DDA286,fc73a533"/>
              <m a="44.300000,28.500000,OB,MAST,120,12:53:00,5,0,0,0,15,Constanta,0,99999999"/>
              <m a="44.150000,28.500000,OL,YR-OLD,500,12:10:00,600,90,100,0,8,Constanta,DD1111,11223344"/>
            </markers>
        """.trimIndent()

        val ognList = AdsbClient.parseOgnLiveXml(sampleOgnXml)
        // Obstacolul static (type 15) și ținta expirată (age 600s) trebuie filtrate
        assertEquals(3, ognList.size)

        val yrScp = ognList.first { it.callsign == "YRSCP" }
        assertEquals("4a8123", yrScp.hex)
        assertEquals("YR-SCP", yrScp.registration)
        assertTrue(yrScp.generalAviation)
        assertEquals("ȘCOALĂ", yrScp.gaCategory)
        assertEquals("ogn_flarm", yrScp.sourceType)
        assertEquals(40, yrScp.gsKt) // 74 km/h ≈ 40 kt
        assertEquals(2500, yrScp.altBaroFt) // 762 m ≈ 2500 ft
        assertEquals("Regional Air Services — Tuzla (LRTZ)", yrScp.airlineHint)

        val heli = ognList.first { it.gaCategory == "ELICOPTER" }
        assertEquals("ogn_8bde76b8", heli.hex)
        assertEquals(71, heli.gsKt) // 131 km/h ≈ 71 kt

        val glider = ognList.first { it.gaCategory == "PLANOR" }
        assertEquals("dda286", glider.hex)
        assertEquals("YR-1024", glider.registration)

        // Test parsare OGN DDB JSON
        val ddbJson = """
            {
              "devices": [
                {
                  "device_type": "F",
                  "device_id": "DDA286",
                  "aircraft_model": "IS-28B2",
                  "registration": "YR-1024",
                  "cn": "GL",
                  "tracked": "Y",
                  "identified": "Y"
                }
              ]
            }
        """.trimIndent()
        val ddb = AdsbClient.parseOgnDdbJson(ddbJson)
        assertEquals("IS-28B2", ddb?.aircraftModel)
        assertEquals("YR-1024", ddb?.registration)

        // Test deduplicare încrucișată ADS-B (hex ICAO 4a9999) + OGN (hex FLARM dd9999) pe aceeași înmatriculare YR-SCP
        val adsbYrScp = RawAircraft(
            hex = "4a9999",
            callsign = "YRSCP",
            registration = "YR-SCP",
            aircraftType = "DV20",
            lat = 44.0415,
            lon = 28.6150,
            altBaroFt = 2500,
            gsKt = 40,
            sourceType = "mlat",
            generalAviation = true,
            gaCategory = "ȘCOALĂ"
        )
        val ognDiffHex = yrScp.copy(hex = "dd9999")
        val merged = AdsbClient.mergeAircraftLists(listOf(listOf(adsbYrScp), listOf(ognDiffHex)))
        assertEquals(1, merged.size)
        assertEquals("4a9999", merged.first().hex)
        assertEquals("DV20", merged.first().aircraftType)
        assertTrue(merged.first().sourceType.contains("ogn"))
    }

    @Test
    fun classifiesSchoolAircraftWith180sMlatAndDetectsLowSpeedTakeoffAt40Kt() {
        // Test parsare Readsb cu lastPosition (165s) + DV20 + 40 kt
        val readsbJson = """
            {
              "ac": [
                {
                  "hex": "4a8123",
                  "type": "mlat",
                  "flight": "YRSCP ",
                  "r": "YR-SCP",
                  "t": "DV20",
                  "category": "A1",
                  "lastPosition": { "lat": 44.052, "lon": 28.612, "seen_pos": 165.0 },
                  "alt_baro": 2500,
                  "gs": 40.0,
                  "track": 180.0
                }
              ]
            }
        """.trimIndent()
        val parsed = AdsbClient.parseReadsbV2(readsbJson)
        assertEquals(1, parsed.size)
        val ac = parsed.first()
        assertTrue(ac.generalAviation)
        assertEquals("ȘCOALĂ", ac.gaCategory)
        assertEquals("Regional Air Services — Tuzla (LRTZ)", ac.airlineHint)
        assertEquals("Diamond DV20 Katana", AdsbClient.resolveKnownAircraftModel(ac.aircraftType))

        // Test OpenSky fără tip aeronavă (doar callsign YRSCP la 2500 ft, 40 kt) -> detectat automat GA / ȘCOALĂ
        val openSkyJson = """
            {
              "states": [
                ["4a8123", "YRSCP   ", "Romania", 1700000000, 1700000000, 28.612, 44.052, 762.0, false, 20.6, 180.0, 1.0, null, 780.0, "7000", false, 0]
              ]
            }
        """.trimIndent()
        val osList = AdsbClient.parseOpenSky(openSkyJson)
        assertEquals(1, osList.size)
        assertTrue(osList.first().generalAviation)
        assertEquals("ȘCOALĂ", osList.first().gaCategory)

        // Test detecție decolare avion școală lent la 42 kt (sub vechiul prag de 65 kt, peste noul prag GA de 35 kt)
        val detector = FlightDetector()
        val t0 = 1_700_000_000_000L
        detector.processSnapshot(
            listOf(
                RawAircraft(
                    hex = "4a8123",
                    callsign = "YRSCP",
                    registration = "YR-SCP",
                    aircraftType = "DV20",
                    lat = AirportConfig.LAT - 0.004,
                    lon = AirportConfig.LON,
                    onGround = true,
                    gsKt = 15,
                    trackDeg = 180,
                    verticalRateFpm = 0,
                    generalAviation = true,
                    gaCategory = "ȘCOALĂ"
                )
            ),
            t0
        )
        val rTakeoff = detector.processSnapshot(
            listOf(
                RawAircraft(
                    hex = "4a8123",
                    callsign = "YRSCP",
                    registration = "YR-SCP",
                    aircraftType = "DV20",
                    lat = AirportConfig.LAT - 0.022,
                    lon = AirportConfig.LON,
                    altBaroFt = AirportConfig.ELEVATION_FT + 550,
                    onGround = false,
                    gsKt = 42,
                    trackDeg = 180,
                    verticalRateFpm = 550,
                    generalAviation = true,
                    gaCategory = "ȘCOALĂ"
                )
            ),
            t0 + 18_000L
        )
        val takeoffs = rTakeoff.events.filter { it.type == FlightEventType.TAKEOFF }
        assertEquals(1, takeoffs.size)
        assertEquals("YRSCP", takeoffs.first().callsign)
        assertTrue(rTakeoff.activeAircraft.first().generalAviation)
        assertEquals("ȘCOALĂ", rTakeoff.activeAircraft.first().gaCategory)
    }

    @Test
    fun parsesOpenSkyOAuth2TokenAndUltralightCategory12AndRequiresAuthForBackfill() {
        val nowMs = 1_700_000_000_000L
        val tokenJson = """{"access_token":"eyJhbGciOiJSUzI1NiJ9.test","expires_in":1800,"token_type":"Bearer"}"""
        val (token, expiresAtMs) = requireNotNull(AdsbClient.parseOpenSkyTokenJson(tokenJson, nowMs))
        assertEquals("eyJhbGciOiJSUzI1NiJ9.test", token)
        // 1800s - 30s marjă de siguranță = 1770s
        assertEquals(nowMs + 1_770_000L, expiresAtMs)

        // Fără credențiale OAuth2, backfill-ul trebuie să semnaleze needsAuth=true (nu crash / HTTP 403 anonim)
        val noAuthRes = AdsbClient.fetchMissedFlightsFromOpenSky(
            beginSec = 1_700_000_000L,
            endSec = 1_700_003_600L,
            clientId = "",
            clientSecret = ""
        )
        assertTrue(!noAuthRes.ok)
        assertTrue(noAuthRes.needsAuth)
        assertTrue(noAuthRes.error?.contains("OAuth2") == true)

        // Categoria OpenSky 12 = Ultralight (B4 -> ULM) cu extended=1
        val ulmJson = """
            {
              "states": [
                ["4a9012", "YR5120  ", "Romania", 1700000000, 1700000000, 28.58, 44.10, 450.0, false, 30.0, 90.0, 0.5, null, 460.0, "7000", false, 0, 12]
              ]
            }
        """.trimIndent()
        val ulmList = AdsbClient.parseOpenSky(ulmJson)
        assertEquals(1, ulmList.size)
        assertTrue(ulmList.first().generalAviation)
        assertEquals("ULM", ulmList.first().gaCategory)
    }

    @Test
    fun replaysHistoricalOpenSkySnapshotsToRecoverRecentLandingAndTakeoff() {
        val t0 = 1_700_000_000L
        val snapshots = listOf(
            // Snapshot 1 (t0): THY1048 pe finală spre RWY 18 + WZZ3129 pe sol la LRCK
            t0 to listOf(
                RawAircraft(
                    hex = "4ba100",
                    callsign = "THY1048",
                    lat = AirportConfig.LAT + 0.08,
                    lon = AirportConfig.LON,
                    altBaroFt = AirportConfig.ELEVATION_FT + 1700,
                    onGround = false,
                    gsKt = 148,
                    trackDeg = 180,
                    verticalRateFpm = -750,
                    sourceType = "opensky"
                ),
                RawAircraft(
                    hex = "471200",
                    callsign = "WZZ3129",
                    lat = AirportConfig.LAT - 0.005,
                    lon = AirportConfig.LON,
                    onGround = true,
                    gsKt = 25,
                    trackDeg = 0,
                    verticalRateFpm = 0,
                    sourceType = "opensky"
                )
            ),
            // Snapshot 2 (t0 + 90s): THY1048 coboară jos pe finală (~2.2 km), WZZ3129 decolează pe RWY 36
            (t0 + 90L) to listOf(
                RawAircraft(
                    hex = "4ba100",
                    callsign = "THY1048",
                    lat = AirportConfig.LAT + 0.02,
                    lon = AirportConfig.LON,
                    altBaroFt = AirportConfig.ELEVATION_FT + 450,
                    onGround = false,
                    gsKt = 135,
                    trackDeg = 180,
                    verticalRateFpm = -680,
                    sourceType = "opensky"
                ),
                RawAircraft(
                    hex = "471200",
                    callsign = "WZZ3129",
                    lat = AirportConfig.LAT + 0.04,
                    lon = AirportConfig.LON,
                    altBaroFt = AirportConfig.ELEVATION_FT + 1800,
                    onGround = false,
                    gsKt = 175,
                    trackDeg = 2,
                    verticalRateFpm = 1900,
                    sourceType = "opensky"
                )
            ),
            // Snapshot 3 (t0 + 180s): THY1048 a aterizat (semnal dispărut pe pistă), WZZ3129 plecat mai departe
            (t0 + 180L) to listOf(
                RawAircraft(
                    hex = "471200",
                    callsign = "WZZ3129",
                    lat = AirportConfig.LAT + 0.12,
                    lon = AirportConfig.LON,
                    altBaroFt = AirportConfig.ELEVATION_FT + 4800,
                    onGround = false,
                    gsKt = 240,
                    trackDeg = 5,
                    verticalRateFpm = 1600,
                    sourceType = "opensky"
                )
            )
        )

        val recovered = AdsbClient.replayHistoricalSnapshots(snapshots)
        val landings = recovered.filter { it.type == FlightEventType.LANDING }
        val takeoffs = recovered.filter { it.type == FlightEventType.TAKEOFF }
        val approaches = recovered.filter { it.type == FlightEventType.APPROACH }

        assertEquals(1, landings.size)
        assertEquals("THY1048", landings.first().callsign)
        assertTrue(landings.first().detectionReason.startsWith("opensky_replay_"))

        assertEquals(1, takeoffs.size)
        assertEquals("WZZ3129", takeoffs.first().callsign)
        assertTrue(takeoffs.first().detectionReason.startsWith("opensky_replay_"))

        // Replay-ul nu trebuie să umple istoricul cu evenimente APPROACH
        assertEquals(0, approaches.size)
    }

    @Test
    fun deduplicatesRecovered12hFlightsWithoutDuplicatesAcrossSources() {
        val t0 = 1_700_000_000_000L
        val existing = listOf(
            FlightEvent(
                id = "live_1",
                type = FlightEventType.LANDING,
                timestampMs = t0,
                hex = "4ba999",
                callsign = "THY1048",
                registration = "TC-JSO",
                aircraftType = "B738",
                airline = "Turkish Airlines",
                route = "IST → CND",
                runway = "18",
                distanceKm = 0.8,
                altitudeFt = 100,
                groundSpeedKt = 128,
                detectionReason = "air_to_ground"
            ),
            FlightEvent(
                id = "ogn_1",
                type = FlightEventType.TAKEOFF,
                timestampMs = t0 + 10 * 60_000L,
                hex = "ogn_dd1234",
                callsign = "YRSCP",
                registration = "YR-SCP",
                aircraftType = "DV20",
                airline = "Regional Air Services — Tuzla (LRTZ)",
                route = null,
                runway = "36",
                distanceKm = 1.5,
                altitudeFt = 650,
                groundSpeedKt = 55,
                detectionReason = "ground_to_air"
            ),
            FlightEvent(
                id = "sim_1",
                type = FlightEventType.LANDING,
                timestampMs = t0 + 20 * 60_000L,
                hex = "471f01",
                callsign = "WZZ3128",
                registration = "HA-LZN",
                aircraftType = "A21N",
                airline = "Wizz Air",
                route = "LTN → CND",
                runway = "18",
                distanceKm = 0.9,
                altitudeFt = 110,
                groundSpeedKt = 132,
                detectionReason = "simulated",
                simulated = true
            )
        )

        val candidates = listOf(
            // Dublură pe hex în ±30 min -> ignorată
            FlightEvent(
                id = "os_dup_hex",
                type = FlightEventType.LANDING,
                timestampMs = t0 + 12 * 60_000L,
                hex = "4ba999",
                callsign = "THY1048",
                registration = null,
                aircraftType = null,
                airline = "Turkish Airlines",
                route = "LTFM → LRCK",
                runway = null,
                distanceKm = 0.0,
                altitudeFt = null,
                groundSpeedKt = null,
                detectionReason = "opensky_backfill"
            ),
            // Dublură pe callsign fără hex în ±30 min -> ignorată
            FlightEvent(
                id = "os_dup_cs",
                type = FlightEventType.LANDING,
                timestampMs = t0 - 8 * 60_000L,
                hex = "",
                callsign = "THY1048",
                registration = null,
                aircraftType = null,
                airline = "Turkish Airlines",
                route = null,
                runway = null,
                distanceKm = 0.0,
                altitudeFt = null,
                groundSpeedKt = null,
                detectionReason = "opensky_replay_air_to_ground"
            ),
            // Dublură încrucișată callsign ↔ registration (YR-SCP vs YRSCP) -> ignorată
            FlightEvent(
                id = "os_dup_cross",
                type = FlightEventType.TAKEOFF,
                timestampMs = t0 + 14 * 60_000L,
                hex = "4a8123",
                callsign = "YR-SCP",
                registration = null,
                aircraftType = "DV20",
                airline = null,
                route = null,
                runway = "36",
                distanceKm = 1.2,
                altitudeFt = 500,
                groundSpeedKt = 52,
                detectionReason = "opensky_backfill"
            ),
            // Zbor real WZZ3128 (nu trebuie blocat de evenimentul simulat sim_1) -> PĂSTRAT
            FlightEvent(
                id = "os_real_wzz_1",
                type = FlightEventType.LANDING,
                timestampMs = t0 + 20 * 60_000L,
                hex = "471f01",
                callsign = "WZZ3128",
                registration = "HA-LZN",
                aircraftType = "A21N",
                airline = "Wizz Air",
                route = "EGGW → LRCK",
                runway = "18",
                distanceKm = 0.0,
                altitudeFt = null,
                groundSpeedKt = null,
                detectionReason = "opensky_backfill"
            ),
            // Dublură internă pentru WZZ3128 în același lot (replay + batch) -> ignorată
            FlightEvent(
                id = "os_real_wzz_2",
                type = FlightEventType.LANDING,
                timestampMs = t0 + 24 * 60_000L,
                hex = "471f01",
                callsign = "WZZ3128",
                registration = null,
                aircraftType = null,
                airline = "Wizz Air",
                route = null,
                runway = "18",
                distanceKm = 0.5,
                altitudeFt = 80,
                groundSpeedKt = 120,
                detectionReason = "opensky_replay_air_to_ground"
            ),
            // Zbor nou de seară THY1048 după 5 ore (> 30 min) -> PĂSTRAT
            FlightEvent(
                id = "os_real_thy_evening",
                type = FlightEventType.LANDING,
                timestampMs = t0 + 5 * 3600_000L,
                hex = "4ba999",
                callsign = "THY1048",
                registration = "TC-JSO",
                aircraftType = "B738",
                airline = "Turkish Airlines",
                route = "LTFM → LRCK",
                runway = null,
                distanceKm = 0.0,
                altitudeFt = null,
                groundSpeedKt = null,
                detectionReason = "opensky_backfill"
            )
        )

        val uniqueFirstPass = FlightRepository.deduplicateFlightEvents(existing, candidates)
        assertEquals(2, uniqueFirstPass.size)
        assertEquals(setOf("os_real_wzz_1", "os_real_thy_evening"), uniqueFirstPass.map { it.id }.toSet())

        // A doua sincronizare identică imediat după -> 0 dubluri
        val uniqueSecondPass = FlightRepository.deduplicateFlightEvents(existing + uniqueFirstPass, candidates)
        assertEquals(0, uniqueSecondPass.size)
    }

    @Test
    fun parsesRomatsaDobrogeaAuthorizationsAndExcludesOutOfRegionAndAdminNotams() {
        val utcFmt = SimpleDateFormat("yyMMddHHmm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val nowMs = requireNotNull(utcFmt.parse("2611211200")).time

        val sampleRomatsaHtml = """
            <html><body><pre>
            (R9009/26 NOTAMN
            Q) LRBB/QWULW/IV/BO/W/000/010/4410N02745E005
            A) LRBB B) 2611210600 C) 2611211800
            D) 0600-1800
            E) UNMANNED ACFT WILL TAKE PLACE IN AREA:
            441000N 0274500E RADIUS 2KM.
            F) GND G) 1000FT AGL)

            (R5637/26 NOTAMN
            Q) LRBB/QRTCA/IV/BO/W/000/050/4520N02848E025
            A) LRBB B) 2611210000 C) PERM
            E) TEMPORARY RESTRICTED AREA FOR IFR/VFR/UNMANNED ACFT ALONG UKRAINIAN BORDER:
            452000N 0284800E - 452500N 0291700E.
            F) GND G) 5000FT AMSL)

            (R8075/26 NOTAMN
            Q) LRBB/QRDCA/IV/BO/W/000/150/4422N02843E012
            A) LRBB B) 2611210500 C) 2611211700
            E) DANGER AREA LRD06B CAP MIDIA (CONSTANTA) ACTIVATED.
            F) SFC
            G) FL150)

            (M7387/26 NOTAMN
            Q) LRBB/QRMCA/IV/BO/W/000/150/4422N02829E015
            A) LRCK B) 2611220600 C) 2611221400
            E) MIL FLT WILL TAKE PLACE IN LRCK TMA.
            F) GND G) FL150)

            (R5999/26 NOTAMN
            Q) LRBB/QRTCA/IV/BO/W/000/050/4752N02512E010
            A) LRBB B) 2611210000 C) 2612312359
            E) TEMPORARY RESTRICTED AREA FOR UNMANNED ACFT ALONG UKRAINIAN BORDER:
            475200N 0251200E - 475500N 0252000E.
            F) GND G) 4000FT AMSL)

            (R9111/26 NOTAMN
            Q) LRBB/QWULW/IV/BO/W/000/005/4430N02606E005
            A) LRBB B) 2611210600 C) 2611211800
            E) UNMANNED ACFT WILL TAKE PLACE IN BUCHAREST AREA 443000N 0260600E.
            F) GND G) 400FT AGL)

            (R1001/25 NOTAMN
            Q) LRBB/QWULW/IV/BO/W/000/010/4422N02829E005
            A) LRCK B) 2510010600 C) 2510101200
            E) UNMANNED ACFT CONSTANTA EXPIRED.
            F) GND G) 500FT AGL)

            (R9009/26 NOTAMC R9008/26
            Q) LRBB/QWULW/IV/BO/W/000/010/4410N02745E005
            A) LRBB B) 2611210600
            E) NOTAM CANCELLED.)

            (R2222/26 NOTAMN
            Q) LRBB/QRTCA/IV/BO/W/000/999/4422N02829E010
            A) LRCK B) 2611210000 C) 2612312359
            E) TRIGGER NOTAM - PERM AIRAC AIP AMDT 12/26.)
            </pre></body></html>
        """.trimIndent()

        val notices = RomatsaAirspaceClient.parseDobrogeaNotices(sampleRomatsaHtml, nowMs)
        assertEquals(4, notices.size)

        assertEquals(RomatsaNoticeCategory.UAS_DRONE, notices[0].category)
        assertEquals("R9009/26", notices[0].notamId)
        assertEquals("GND → 1000FT AGL", notices[0].verticalLimits)
        assertTrue(notices[0].activeNow)

        assertEquals(RomatsaNoticeCategory.RESTRICTED_UAS, notices[1].category)
        assertEquals("R5637/26", notices[1].notamId)
        assertEquals("PERM", notices[1].validToUtc)
        assertTrue(notices[1].activeNow)

        assertEquals(RomatsaNoticeCategory.DANGER_AREA, notices[2].category)
        assertEquals("R8075/26", notices[2].notamId)
        assertEquals("SFC → FL150", notices[2].verticalLimits)
        assertTrue(notices[2].rawNotam.contains("ACTIVATED"))
        assertTrue(notices[2].activeNow)

        assertEquals(RomatsaNoticeCategory.MAPN_MILITARY, notices[3].category)
        assertEquals("M7387/26", notices[3].notamId)
        assertTrue(!notices[3].activeNow)
    }
}

