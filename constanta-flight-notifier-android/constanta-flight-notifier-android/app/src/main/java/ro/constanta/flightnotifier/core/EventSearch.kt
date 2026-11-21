package ro.constanta.flightnotifier.core

import ro.constanta.flightnotifier.model.EventSearchFilter
import ro.constanta.flightnotifier.model.FlightEvent

object EventSearch {

    fun filterEvents(
        events: List<FlightEvent>,
        filter: EventSearchFilter
    ): List<FlightEvent> {
        val needle = filter.query.trim().lowercase()
        return events.filter { evt ->
            if (filter.type != null && evt.type != filter.type) return@filter false
            if (filter.fromMs != null && evt.timestampMs < filter.fromMs) return@filter false
            if (filter.toMs != null && evt.timestampMs > filter.toMs) return@filter false
            if (needle.isNotEmpty()) {
                val haystack = listOfNotNull(
                    evt.callsign,
                    evt.hex,
                    evt.registration,
                    evt.aircraftType,
                    evt.airline,
                    evt.route,
                    evt.squawk,
                    evt.emergencyLabel,
                    evt.goAroundSummary,
                    "rwy ${evt.runway}",
                    "pista ${evt.runway}"
                ).joinToString(" ").lowercase()
                if (!haystack.contains(needle)) return@filter false
            }
            true
        }
    }
}
