package ro.constanta.flightnotifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.constanta.flightnotifier.core.AirportConfig
import ro.constanta.flightnotifier.core.DobrogeaDroneAlertTracker
import ro.constanta.flightnotifier.core.DobrogeaDroneMonitor
import ro.constanta.flightnotifier.model.BorderRegionAlarm

class DobrogeaDroneMonitorTest {

    @Test
    fun `detects interior Dobrogea landmarks as inside airspace with 0 km distance`() {
        val cnd = DobrogeaDroneMonitor.evaluateProximityToDobrogea(AirportConfig.LAT, AirportConfig.LON)
        assertTrue("CND trebuie să fie în interiorul Dobrogei", cnd.insideDobrogea)
        assertEquals(0.0, cnd.distanceKm, 0.01)

        val tulcea = DobrogeaDroneMonitor.evaluateProximityToDobrogea(45.1780, 28.8050)
        assertTrue("Tulcea trebuie să fie în interiorul Dobrogei", tulcea.insideDobrogea)
        assertEquals(0.0, tulcea.distanceKm, 0.01)

        val sulina = DobrogeaDroneMonitor.evaluateProximityToDobrogea(45.1560, 29.6560)
        assertTrue("Sulina trebuie să fie în interiorul Dobrogei", sulina.insideDobrogea)
        assertEquals(0.0, sulina.distanceKm, 0.01)
    }

    @Test
    fun `detects drones across Danube and Black Sea within 10 km of Dobrogea`() {
        // Izmail (vizavi Plauru / Ceatalchioi)
        val izmail = DobrogeaDroneMonitor.evaluateProximityToDobrogea(45.3510, 28.8360)
        assertFalse("Izmail este în afara Dobrogei", izmail.insideDobrogea)
        assertTrue("Izmail trebuie să fie sub 10 km (actual: ${izmail.distanceKm})", izmail.distanceKm in 1.0..9.9)

        // Reni (vizavi Cotul Pisicii)
        val reni = DobrogeaDroneMonitor.evaluateProximityToDobrogea(45.4580, 28.2880)
        assertFalse("Reni este în afara Dobrogei", reni.insideDobrogea)
        assertTrue("Reni trebuie să fie sub 10 km (actual: ${reni.distanceKm})", reni.distanceKm in 1.0..9.9)

        // Chilia Nouă (vizavi Chilia Veche)
        val chilia = DobrogeaDroneMonitor.evaluateProximityToDobrogea(45.4560, 29.2640)
        assertFalse("Chilia Nouă este în afara Dobrogei", chilia.insideDobrogea)
        assertTrue("Chilia Nouă trebuie să fie sub 10 km (actual: ${chilia.distanceKm})", chilia.distanceKm in 1.0..9.9)

        // Marea Neagră — 6 km est de limita apelor teritoriale 12 NM Cap Midia
        val sea = DobrogeaDroneMonitor.evaluateProximityToDobrogea(44.3600, 29.0550)
        assertFalse("Punctul maritim la 15 NM este în afara 12 NM", sea.insideDobrogea)
        assertTrue("Punctul maritim trebuie să fie sub 10 km de limita 12 NM (actual: ${sea.distanceKm})", sea.distanceKm in 1.0..9.9)
    }

    @Test
    fun `ignores distant regions outside Dobrogea such as Chernivtsi Odesa city and Kyiv`() {
        // Cernăuți / nordul României — nu trebuie să declanșeze alertă de Dobrogea
        val chernivtsi = DobrogeaDroneMonitor.evaluateProximityToDobrogea(48.2920, 25.9350)
        assertFalse(chernivtsi.insideDobrogea)
        assertTrue("Cernăuți trebuie să fie > 300 km de Dobrogea", chernivtsi.distanceKm > 300.0)

        // Orașul Odesa
        val odesa = DobrogeaDroneMonitor.evaluateProximityToDobrogea(46.4825, 30.7233)
        assertFalse(odesa.insideDobrogea)
        assertTrue("Orașul Odesa trebuie să fie > 100 km de Dobrogea", odesa.distanceKm > 100.0)

        // Kiev
        val kyiv = DobrogeaDroneMonitor.evaluateProximityToDobrogea(50.4501, 30.5234)
        assertFalse(kyiv.insideDobrogea)
        assertTrue("Kiev trebuie să fie > 500 km de Dobrogea", kyiv.distanceKm > 500.0)
    }

    @Test
    fun `parses DIMAP REST JSON and GeoJSON payloads and normalizes Ukrainian threat types`() {
        val restJson = """
            {
              "targets": [
                {
                  "id": "t1",
                  "type": "шахед",
                  "lat": 45.365,
                  "lon": 28.830,
                  "course": 210,
                  "speed": 185,
                  "region": "Ізмаїл"
                },
                {
                  "id": "t2",
                  "type": "БПЛА",
                  "lat": 50.450,
                  "lon": 30.520,
                  "course": 270
                }
              ]
            }
        """.trimIndent()

        val parsed = DobrogeaDroneMonitor.parseDimapJson(restJson, 1_700_000_000_000L)
        assertEquals(2, parsed.size)
        assertEquals("Dronă Shahed", parsed[0].type)
        assertTrue("Ținta de la Izmail trebuie să fie < 10 km de Dobrogea", parsed[0].distToDobrogeaKm <= 10.0)
        assertEquals(210, parsed[0].courseDeg)
        assertEquals("Dronă (UAV)", parsed[1].type)
        assertTrue("Ținta de la Kiev trebuie să fie > 500 km de Dobrogea", parsed[1].distToDobrogeaKm > 500.0)

        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": { "type": "Point", "coordinates": [29.055, 44.360] },
                  "properties": { "id": "g1", "kind": "shahed", "heading": 265, "velocity": 180 }
                }
              ]
            }
        """.trimIndent()
        val geoParsed = DobrogeaDroneMonitor.parseDimapJson(geoJson, 1_700_000_000_000L)
        assertEquals(1, geoParsed.size)
        assertEquals("g1", geoParsed[0].id)
        assertTrue(geoParsed[0].distToDobrogeaKm <= 10.0)
    }

    @Test
    fun `parses exact official DIMAP polling_update payload with nested targets and Dobrogea alerts`() {
        val realDimapPayload = """
            {
              "type": "polling_update",
              "data": {
                "targets": {
                  "timestamp": "2026-11-21T09:46:59.124Z",
                  "totalActiveTargets": 5,
                  "targets": [
                    { "id": "61561", "type": "reactive_shahed", "lat": 47.429, "lng": 32.782, "bearing": 260, "speed": 420, "code": 13, "name": "Герань-4" },
                    { "id": "61666", "type": "shahed", "lat": 46.685, "lng": 29.903, "bearing": 230, "speed": 120, "code": 10, "name": "Шахед" },
                    { "id": "61740", "type": "geran3", "lat": 51.324, "lng": 33.385, "bearing": 210, "speed": 380, "code": 14, "name": "Реакт. Герань-3" },
                    { "id": "61793", "type": "unknown", "lat": 46.545, "lng": 30.052, "bearing": 0, "speed": 220, "code": 22, "name": "БпЛА" },
                    { "id": "61803", "type": "avia", "lat": 45.702, "lng": 33.456, "bearing": 0, "speed": 0, "code": 80, "name": "Авіація" }
                  ]
                },
                "alerts": {
                  "updatedAt": 1795254408500,
                  "alerts": [
                    { "id": "alert_1", "region": "Алчевський район", "alertType": "air", "color": "red", "typeName": "Повітряна тривога" },
                    { "id": "alert_2", "region": "Білгород-Дністровський район", "alertType": "air", "color": "yellow", "typeName": "Повітряна тривога" },
                    { "id": "alert_3", "region": "Ізмаїльський район", "alertType": "air", "color": "red", "typeName": "Повітряна тривога" }
                  ]
                }
              }
            }
        """.trimIndent()

        val targets = DobrogeaDroneMonitor.parseDimapJson(realDimapPayload, 1_700_000_000_000L)
        assertEquals(5, targets.size)
        assertEquals("Dronă reactivă (Geran-4)", targets[0].type)
        assertEquals(260, targets[0].courseDeg)
        assertEquals(420, targets[0].speedKmh)
        assertEquals("Dronă Shahed", targets[1].type)
        assertEquals("Dronă reactivă (Geran-3)", targets[2].type)
        assertEquals("Dronă (UAV)", targets[3].type)
        assertEquals("Aviație (OSINT)", targets[4].type)

        val alerts = DobrogeaDroneMonitor.parseDimapAlertsForDobrogea(realDimapPayload)
        assertEquals(2, alerts.size)
        assertTrue(alerts.any { it.regionName.contains("Bilhorod") && it.regionName.contains("GALBEN") })
        assertTrue(alerts.any { it.regionName.contains("Izmail") && it.regionName.contains("ROȘU") })
        assertFalse(alerts.any { it.regionName.contains("Алчевськ") })
    }

    @Test
    fun `filters UkraineAlarm API strictly to Dobrogea adjacent regions Izmail and Odesa`() {
        val raw = """
            [
              {
                "regionId": "31",
                "regionName": "м. Київ",
                "regionEngName": "Kyiv",
                "activeAlerts": [{"type": "AIR", "lastUpdate": "2026-11-21T08:00:00Z"}]
              },
              {
                "regionId": "26",
                "regionName": "Чернівецька область",
                "regionEngName": "Chernivtsi oblast",
                "activeAlerts": [{"type": "AIR", "lastUpdate": "2026-11-21T08:00:00Z"}]
              },
              {
                "regionId": "123",
                "regionName": "Ізмаїльський район",
                "regionEngName": "Izmail district",
                "activeAlerts": [{"type": "AIR", "lastUpdate": "2026-11-21T08:01:00Z"}]
              }
            ]
        """.trimIndent()

        val alarms = DobrogeaDroneMonitor.parseUkraineAlarmJsonForDobrogea(raw)
        assertEquals(1, alarms.size)
        assertEquals("123", alarms[0].regionId)
        assertTrue(alarms[0].regionName.contains("Izmail"))
    }

    @Test
    fun `alert tracker deduplicates within cooldown and escalates on Dobrogea entry`() {
        val tracker = DobrogeaDroneAlertTracker(cooldownMs = 300_000L)
        val t0 = 1_700_000_000_000L
        val approaching = DobrogeaDroneMonitor.createSimulatedDobrogeaDrone("izmail", t0)
            .copy(id = "real_1", simulated = false)

        assertEquals(1, tracker.filterNewDroneAlerts(listOf(approaching), t0).size)
        // Aceeași dronă la 30s mai târziu -> nu repetă spam
        assertEquals(0, tracker.filterNewDroneAlerts(listOf(approaching), t0 + 30_000L).size)
        // Intră în spațiul aerian al Dobrogei -> escaladează imediat!
        val entered = approaching.copy(insideDobrogea = true, distToDobrogeaKm = 0.0)
        assertEquals(1, tracker.filterNewDroneAlerts(listOf(entered), t0 + 45_000L).size)

        // Test deduplicare și resetare alarme raion graniță
        val izmailAlarm = BorderRegionAlarm("123", "Raionul Izmail", "AIR", true)
        assertEquals(1, tracker.filterNewBorderAlarms(listOf(izmailAlarm)).size)
        assertEquals(0, tracker.filterNewBorderAlarms(listOf(izmailAlarm)).size)
        // Alarma se ridică și apoi pornește alta nouă
        assertEquals(0, tracker.filterNewBorderAlarms(emptyList()).size)
        assertEquals(1, tracker.filterNewBorderAlarms(listOf(izmailAlarm)).size)
    }

    @Test
    fun `simulated Dobrogea drone scenarios match expected proximity thresholds`() {
        val izmail = DobrogeaDroneMonitor.createSimulatedDobrogeaDrone("izmail")
        assertFalse(izmail.insideDobrogea)
        assertTrue("Izmail sim trebuie să fie <= 10 km (actual: ${izmail.distToDobrogeaKm})", izmail.distToDobrogeaKm <= 10.0)

        val sea = DobrogeaDroneMonitor.createSimulatedDobrogeaDrone("sea")
        assertFalse(sea.insideDobrogea)
        assertTrue("Sea sim trebuie să fie <= 10 km (actual: ${sea.distToDobrogeaKm})", sea.distToDobrogeaKm <= 10.0)

        val inside = DobrogeaDroneMonitor.createSimulatedDobrogeaDrone("inside")
        assertTrue("Inside sim trebuie să fie în spațiul aerian Dobrogea", inside.insideDobrogea)
        assertEquals(0.0, inside.distToDobrogeaKm, 0.01)
    }
}
