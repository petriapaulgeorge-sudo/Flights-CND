package ro.constanta.flightnotifier.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ro.constanta.flightnotifier.R
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventsAdapter(
    private val onClick: (FlightEvent) -> Unit
) : RecyclerView.Adapter<EventsAdapter.VH>() {

    private val items = mutableListOf<FlightEvent>()
    private val timeFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale("ro", "RO"))

    fun submitList(next: List<FlightEvent>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_flight_event, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val evt = items[position]
        val (badgeText, badgeColor) = when (evt.type) {
            FlightEventType.LANDING -> "ATERIZARE" to Color.parseColor("#0F766E")
            FlightEventType.TAKEOFF -> "DECOLARE" to Color.parseColor("#0369A1")
            FlightEventType.APPROACH -> "APROPIERE" to Color.parseColor("#B45309")
        }

        holder.tvBadge.text = if (evt.simulated) "$badgeText • TEST" else badgeText
        holder.tvBadge.setBackgroundColor(badgeColor)
        holder.tvCallsign.text = evt.callsign
        holder.tvTime.text = timeFormat.format(Date(evt.timestampMs))

        val details = listOfNotNull(
            "RWY ${evt.runway}",
            evt.airline,
            evt.aircraftType,
            evt.registration,
            evt.route,
            "${evt.altAglFt ?: "—"} ft • ${evt.gsKt ?: "—"} kt"
        ).joinToString("  •  ")
        holder.tvDetails.text = details

        holder.itemView.setOnClickListener { onClick(evt) }
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvBadge: TextView = itemView.findViewById(R.id.tv_event_badge)
        val tvCallsign: TextView = itemView.findViewById(R.id.tv_event_callsign)
        val tvTime: TextView = itemView.findViewById(R.id.tv_event_time)
        val tvDetails: TextView = itemView.findViewById(R.id.tv_event_details)
    }
}
