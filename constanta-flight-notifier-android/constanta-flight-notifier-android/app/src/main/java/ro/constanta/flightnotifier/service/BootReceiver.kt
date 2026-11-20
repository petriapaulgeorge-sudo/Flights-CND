package ro.constanta.flightnotifier.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ro.constanta.flightnotifier.data.FlightRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        FlightRepository.init(context)
        if (FlightRepository.settings.value.autoStartOnBoot) {
            val serviceIntent = Intent(context, FlightMonitorService::class.java).apply {
                action = FlightMonitorService.ACTION_START
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
