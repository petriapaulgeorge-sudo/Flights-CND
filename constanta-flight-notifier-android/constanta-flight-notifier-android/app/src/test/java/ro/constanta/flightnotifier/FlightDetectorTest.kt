package ro.constanta.flightnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
