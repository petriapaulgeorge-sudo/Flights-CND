package ro.constanta.flightnotifier.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ro.constanta.flightnotifier.R
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventsAdapter(
    private val onClick: (FlightEvent) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<EventsAdapter.VH>() {

    private val items = mutableListOf<FlightEvent>()
    private val selectedSimulatedIds = LinkedHashSet<String>()
    private val timeFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale("ro", "RO"))

    fun submitList(next: List<FlightEvent>) {
        items.clear()
        items.addAll(next)
        val validIds = next.filter { it.simulated }.map { it.id }.toHashSet()
        selectedSimulatedIds.retainAll(validIds)
        notifyDataSetChanged()
        onSelectionChanged(selectedSimulatedIds.size)
    }

    fun getSelectedSimulatedIds(): Set<String> = selectedSimulatedIds.toSet()

    fun clearSelection() {
        if (selectedSimulatedIds.isEmpty()) return
        selectedSimulatedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_flight_event, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val evt = items[position]
        val emCode = evt.emergencyCode ?: evt.squawk ?: ""
        val (badgeText, badgeColor) = when (evt.type) {
            FlightEventType.LANDING -> "ATERIZARE" to Color.parseColor("#0F766E")
            FlightEventType.TAKEOFF -> "DECOLARE" to Color.parseColor("#0369A1")
            FlightEventType.APPROACH -> "APROPIERE" to Color.parseColor("#B45309")
            FlightEventType.EMERGENCY -> {
                if (emCode == "PAN") "PAN-PAN" to Color.parseColor("#EA580C")
                else "URGENȚĂ $emCode".trim() to Color.parseColor("#DC2626")
            }
        }

        holder.tvBadge.text = if (evt.simulated) "$badgeText • TEST" else badgeText
        holder.tvBadge.setBackgroundColor(badgeColor)
        holder.tvCallsign.text = evt.callsign
        holder.tvTime.text = timeFormat.format(Date(evt.timestampMs))

        val details = listOfNotNull(
            evt.emergencyLabel,
            if (evt.detectionReason == "opensky_backfill") "Recuperat offline (OpenSky)" else null,
            if (evt.type == FlightEventType.EMERGENCY) "${evt.distKm} km de LRCK" else "RWY ${evt.runway}",
            evt.airline,
            evt.aircraftType,
            evt.registration,
            evt.route,
            "${evt.altAglFt ?: "—"} ft • ${evt.gsKt ?: "—"} kt"
        ).joinToString("  •  ")
        holder.tvDetails.text = details

        // Doar zborurile simulate pot fi bifate și șterse selectiv
        holder.cbSelect.setOnCheckedChangeListener(null)
        if (evt.simulated) {
            holder.cbSelect.visibility = View.VISIBLE
            holder.cbSelect.isChecked = selectedSimulatedIds.contains(evt.id)
            holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedSimulatedIds.add(evt.id) else selectedSimulatedIds.remove(evt.id)
                onSelectionChanged(selectedSimulatedIds.size)
            }
        } else {
            holder.cbSelect.visibility = View.GONE
            holder.cbSelect.isChecked = false
        }

        holder.itemView.setOnClickListener {
            if (evt.simulated) {
                holder.cbSelect.isChecked = !holder.cbSelect.isChecked
            } else {
                onClick(evt)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cbSelect: CheckBox = itemView.findViewById(R.id.cb_event_select)
        val tvBadge: TextView = itemView.findViewById(R.id.tv_event_badge)
        val tvCallsign: TextView = itemView.findViewById(R.id.tv_event_callsign)
        val tvTime: TextView = itemView.findViewById(R.id.tv_event_time)
        val tvDetails: TextView = itemView.findViewById(R.id.tv_event_details)
    }
}
