package ro.constanta.flightnotifier.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ro.constanta.flightnotifier.R
import ro.constanta.flightnotifier.model.FlightEvent
import ro.constanta.flightnotifier.model.FlightEventType
import ro.constanta.flightnotifier.service.FlightMonitorService
import ro.constanta.flightnotifier.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NotificationHelper {

    const val CHANNEL_MONITOR = "lrck_monitor_status"
    const val CHANNEL_LANDING = "lrck_landings"
    const val CHANNEL_TAKEOFF = "lrck_takeoffs"
    const val CHANNEL_APPROACH = "lrck_approach"
    const val CHANNEL_EMERGENCY = "lrck_emergency"
    const val NOTIF_ID_MONITOR = 1001

    private var alertIdCounter = 2000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val monitorChannel = NotificationChannel(
            CHANNEL_MONITOR,
            "Monitorizare în fundal (LRCK)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notificare silențioasă care menține monitorizarea live activă"
            setShowBadge(false)
        }

        val landingChannel = NotificationChannel(
            CHANNEL_LANDING,
            "Aterizări Constanța (CND)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerte sonore la aterizarea aeronavelor pe LRCK"
            enableVibration(true)
        }

        val takeoffChannel = NotificationChannel(
            CHANNEL_TAKEOFF,
            "Decolări Constanța (CND)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerte sonore la decolarea aeronavelor de pe LRCK"
            enableVibration(true)
        }

        val approachChannel = NotificationChannel(
            CHANNEL_APPROACH,
            "Apropiere finală Constanța (CND)",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerte timpurii când o aeronavă intră pe finală spre LRCK"
        }

        val emergencyChannel = NotificationChannel(
            CHANNEL_EMERGENCY,
            "Coduri de urgență (7700 / 7600 / 7500)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerte critice pentru coduri transponder de urgență (zboruri CND și rază 100 km)"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 180, 400, 180, 600)
            enableLights(true)
            lightColor = Color.RED
        }

        nm.createNotificationChannels(
            listOf(monitorChannel, landingChannel, takeoffChannel, approachChannel, emergencyChannel)
        )
    }

    fun buildMonitorNotification(
        context: Context,
        statusText: String
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, FlightMonitorService::class.java).apply {
                action = FlightMonitorService.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_plane_notif)
            .setContentTitle("SAT - Monitorizare trafic CND")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Oprește", stopIntent)
            .build()
    }

    fun notifyFlightEvent(context: Context, event: FlightEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val (channelId, titlePrefix) = when (event.type) {
            FlightEventType.LANDING -> CHANNEL_LANDING to "Aterizare la Constanța (CND)"
            FlightEventType.TAKEOFF -> CHANNEL_TAKEOFF to "Decolare de la Constanța (CND)"
            FlightEventType.APPROACH -> CHANNEL_APPROACH to "Apropiere finală spre Constanța"
            FlightEventType.EMERGENCY -> CHANNEL_EMERGENCY to "ALERTĂ URGENȚĂ SQUAWK ${event.squawk ?: ""}"
        }

        val simTag = if (event.simulated) " [TEST]" else ""
        val title = "$titlePrefix$simTag"
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale("ro", "RO")).format(Date(event.timestampMs))

        val summaryLine = if (event.type == FlightEventType.EMERGENCY) {
            listOfNotNull(
                event.callsign,
                event.emergencyLabel ?: "Squawk ${event.squawk}",
                "${event.distKm} km de LRCK"
            ).joinToString(" • ")
        } else {
            listOfNotNull(
                event.callsign,
                event.airline,
                event.aircraftType,
                "RWY ${event.runway}"
            ).joinToString(" • ")
        }

        val bigText = buildString {
            if (event.type == FlightEventType.EMERGENCY && !event.emergencyLabel.isNullOrBlank()) {
                append("COD URGENȚĂ: ${event.emergencyLabel}\n")
            }
            append("Indicativ: ${event.callsign}")
            if (!event.airline.isNullOrBlank()) append("\nOperator: ${event.airline}")
            val acDetails = listOfNotNull(event.aircraftType, event.registration).joinToString(" • ")
            if (acDetails.isNotBlank()) append("\nAeronavă: $acDetails")
            if (!event.route.isNullOrBlank()) append("\nRută: ${event.route}")
            if (event.type == FlightEventType.EMERGENCY) {
                append("\nDistanță față de LRCK: ${event.distKm} km")
            } else {
                append("\nPistă probabilă: RWY ${event.runway} (LRCK)")
            }
            append("\nAltitudine: ${event.altAglFt ?: "—"} ft AGL • Viteză: ${event.gsKt ?: "—"} kt")
            append("\nOra: $timeFmt")
        }

        val openAppIntent = PendingIntent.getActivity(
            context,
            alertIdCounter,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mapIntent = PendingIntent.getActivity(
            context,
            alertIdCounter + 1,
            Intent(Intent.ACTION_VIEW, Uri.parse(event.adsbExchangeUrl)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_plane_notif)
            .setContentTitle(title)
            .setContentText(summaryLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(
                if (event.type == FlightEventType.APPROACH) NotificationCompat.PRIORITY_DEFAULT
                else NotificationCompat.PRIORITY_HIGH
            )
            .setCategory(
                if (event.type == FlightEventType.EMERGENCY) NotificationCompat.CATEGORY_ALARM
                else NotificationCompat.CATEGORY_EVENT
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Vezi pe hartă", mapIntent)

        if (event.type == FlightEventType.EMERGENCY) {
            builder.setColor(Color.parseColor("#DC2626"))
        }

        alertIdCounter += 2
        NotificationManagerCompat.from(context).notify(alertIdCounter, builder.build())
    }
}
