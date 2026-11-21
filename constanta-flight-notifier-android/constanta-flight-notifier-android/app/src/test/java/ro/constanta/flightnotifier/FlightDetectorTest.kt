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
import ro.constanta.flightnotifier.core.RunwayStatusClient
import ro.constanta.flightnotifier.model.EventSearchFilter
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.model.RawAircraft

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
}
