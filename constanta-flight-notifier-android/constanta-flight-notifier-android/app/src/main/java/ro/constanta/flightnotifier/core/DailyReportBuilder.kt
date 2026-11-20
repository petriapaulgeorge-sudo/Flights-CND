package ro.constanta.flightnotifier.core

import ro.constanta.flightnotifier.model.DailyFlightEntry
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DailyReportBuilder {

    /**
     * Identifică dacă un eveniment aparține companiilor Turkish Airlines sau Wizz Air.
     * Returnează numele normalizat al companiei sau null dacă nu este una dintre cele două.
     */
    fun matchTargetAirline(event: FlightEvent): String? {
        val cs = event.callsign.trim().uppercase(Locale.US)
        val al = event.airline?.trim()?.lowercase(Locale.US).orEmpty()

        if (al.contains("turkish") || cs.startsWith("THY") || (cs.startsWith("TK") && cs.length >= 4 && cs[2].isDigit())) {
            return "Turkish Airlines"
        }
        if (
            al.contains("wizz") ||
            cs.startsWith("WZZ") ||
            cs.startsWith("WMT") ||
            cs.startsWith("WUK") ||
            cs.startsWith("WAZ") ||
            ((cs.startsWith("W6") || cs.startsWith("W4") || cs.startsWith("W9")) && cs.length >= 4 && cs[2].isDigit())
        ) {
            return "Wizz Air"
        }
        return null
    }

    /**
     * Extrage zborurile Turkish Airlines și Wizz Air din ziua calendaristică [dayStartMs..dayEndMs]
     * și împerechează aterizarea cu decolarea aceleiași aeronave într-o singură intrare de zbor.
     */
    fun buildDailyFlights(
        allEvents: List<FlightEvent>,
        dayCalendar: Calendar,
        existingInputs: Map<String, Triple<Int, Int, Int>> = emptyMap()
    ): List<DailyFlightEntry> {
        val startCal = (dayCalendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = (dayCalendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return buildDailyFlightsInRange(allEvents, startCal.timeInMillis, endCal.timeInMillis, existingInputs)
    }

    fun buildDailyFlightsInRange(
        allEvents: List<FlightEvent>,
        startMs: Long,
        endMs: Long,
        existingInputs: Map<String, Triple<Int, Int, Int>> = emptyMap()
    ): List<DailyFlightEntry> {
        val timeFmt = SimpleDateFormat("HH:mm", Locale("ro", "RO"))

        val relevant = allEvents
            .filter { evt ->
                evt.timestampMs in startMs..endMs &&
                    (evt.type == FlightEventType.LANDING || evt.type == FlightEventType.TAKEOFF) &&
                    matchTargetAirline(evt) != null
            }
            .sortedBy { it.timestampMs }

        // Grupare pe identitatea aeronavei (hex -> înmatriculare -> indicativ)
        val byAircraft = LinkedHashMap<String, MutableList<FlightEvent>>()
        for (evt in relevant) {
            val key = aircraftIdentityKey(evt)
            byAircraft.getOrPut(key) { mutableListOf() }.add(evt)
        }

        val entries = mutableListOf<DailyFlightEntry>()

        for ((aircraftKey, acEvents) in byAircraft) {
            var pendingLanding: FlightEvent? = null
            var turnaroundIdx = 0

            fun emitRow(landing: FlightEvent?, takeoff: FlightEvent?) {
                val primary = landing ?: takeoff ?: return
                turnaroundIdx += 1
                val airline = matchTargetAirline(primary) ?: "—"

                val callsign = when {
                    landing != null && takeoff != null && !landing.callsign.equals(takeoff.callsign, ignoreCase = true) ->
                        "${landing.callsign} / ${takeoff.callsign}"
                    else -> primary.callsign
                }
                val aircraftType = landing?.aircraftType?.takeIf { it.isNotBlank() }
                    ?: takeoff?.aircraftType?.takeIf { it.isNotBlank() }
                    ?: "—"
                val registration = landing?.registration?.takeIf { it.isNotBlank() }
                    ?: takeoff?.registration?.takeIf { it.isNotBlank() }
                    ?: "—"

                val rowId = "auto_${aircraftKey}_$turnaroundIdx"
                val saved = existingInputs[rowId]

                entries.add(
                    DailyFlightEntry(
                        id = rowId,
                        airline = airline,
                        callsign = callsign,
                        aircraftType = aircraftType,
                        registration = registration,
                        landingTime = landing?.let { timeFmt.format(Date(it.timestampMs)) } ?: "—",
                        takeoffTime = takeoff?.let { timeFmt.format(Date(it.timestampMs)) } ?: "—",
                        sortTimeMs = landing?.timestampMs ?: takeoff?.timestampMs ?: 0L,
                        passengers = saved?.first ?: 0,
                        holdBaggage = saved?.second ?: 0,
                        pmr = saved?.third ?: 0,
                        manual = false
                    )
                )
            }

            for (evt in acEvents) {
                when (evt.type) {
                    FlightEventType.LANDING -> {
                        if (pendingLanding != null) {
                            // O altă aterizare fără decolare înregistrată între ele
                            emitRow(pendingLanding, null)
                        }
                        pendingLanding = evt
                    }
                    FlightEventType.TAKEOFF -> {
                        if (pendingLanding != null) {
                            emitRow(pendingLanding, evt)
                            pendingLanding = null
                        } else {
                            // Decolare fără aterizare în aceeași zi (ex. aeronavă parcată peste noapte)
                            emitRow(null, evt)
                        }
                    }
                    else -> Unit
                }
            }
            if (pendingLanding != null) {
                emitRow(pendingLanding, null)
            }
        }

        return entries.sortedBy { it.sortTimeMs }
    }

    private fun aircraftIdentityKey(evt: FlightEvent): String {
        val reg = evt.registration?.trim()?.uppercase(Locale.US)
        if (!reg.isNullOrBlank()) return "reg:$reg"
        val hex = evt.hex.trim().lowercase(Locale.US)
        if (hex.isNotBlank()) return "hex:$hex"
        return "cs:${evt.callsign.trim().uppercase(Locale.US)}"
    }
}
