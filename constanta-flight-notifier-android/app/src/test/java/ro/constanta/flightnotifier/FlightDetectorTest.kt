package ro.constanta.flightnotifier

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.constanta.flightnotifier.core.AirportConfig
import ro.constanta.flightnotifier.core.EventSearch
import ro.constanta.flightnotifier.core.FlightDetector
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
    fun filtersEventsByTimeRangeTypeAndQuery() {
        val t1 = 1_700_000_000_000L
        val t2 = t1 + 3_600_000L // +1h
        val t3 = t1 + 86_400_000L // +24h

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
}
