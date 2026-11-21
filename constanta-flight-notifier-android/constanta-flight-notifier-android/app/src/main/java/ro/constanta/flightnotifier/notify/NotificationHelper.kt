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
import ro.constanta.flightnotifier.core.DobrogeaDroneMonitor
import ro.constanta.flightnotifier.model.BorderRegionAlarm
import ro.constanta.flightnotifier.model.DroneTarget
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
    const val CHANNEL_GO_AROUND = "lrck_go_around"
    const val CHANNEL_EMERGENCY = "lrck_emergency"
    const val CHANNEL_DRONE_DOBROGEA = "dobrogea_drone_alerts"
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

        val goAroundChannel = NotificationChannel(
            CHANNEL_GO_AROUND,
            "Ratări aterizare / Go-Around (CND)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerte când o aeronavă ratează aterizarea la LRCK, cu analiză live pistă și meteo"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300)
        }

        val emergencyChannel = NotificationChannel(
            CHANNEL_EMERGENCY,
            "Coduri de urgență (7700 / PAN / 7600 / 7500)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerte critice pentru coduri transponder de urgență (zboruri CND și rază 100 km)"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 180, 400, 180, 600)
            enableLights(true)
            lightColor = Color.RED
        }

        val droneDobrogeaChannel = NotificationChannel(
            CHANNEL_DRONE_DOBROGEA,
            "Alerte drone — Sector Dobrogea (< 10 km)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerte informative OSINT (DIMAP / graniță Izmail) când o dronă se apropie de spațiul aerian al Dobrogei"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 150, 500, 150, 700)
            enableLights(true)
            lightColor = Color.RED
        }

        nm.createNotificationChannels(
            listOf(
                monitorChannel,
                landingChannel,
                takeoffChannel,
                approachChannel,
                goAroundChannel,
                emergencyChannel,
                droneDobrogeaChannel
            )
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

        val emCode = event.emergencyCode ?: event.squawk ?: ""
        val (channelId, titlePrefix) = when (event.type) {
            FlightEventType.LANDING -> CHANNEL_LANDING to "Aterizare la Constanța (CND)"
            FlightEventType.TAKEOFF -> CHANNEL_TAKEOFF to "Decolare de la Constanța (CND)"
            FlightEventType.APPROACH -> CHANNEL_APPROACH to "Apropiere finală spre Constanța"
            FlightEventType.GO_AROUND -> CHANNEL_GO_AROUND to "RATARE ATERIZARE (Go-Around) — CND"
            FlightEventType.EMERGENCY -> CHANNEL_EMERGENCY to (
                if (emCode == "PAN") "ALERTĂ URGENȚĂ PAN-PAN"
                else "ALERTĂ URGENȚĂ COD $emCode"
            )
        }

        val simTag = if (event.simulated) " [TEST]" else ""
        val title = "$titlePrefix$simTag"
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale("ro", "RO")).format(Date(event.timestampMs))

        val summaryLine = when (event.type) {
            FlightEventType.EMERGENCY -> listOfNotNull(
                event.callsign,
                event.emergencyLabel ?: "Urgență $emCode",
                "${event.distKm} km de LRCK"
            ).joinToString(" • ")
            FlightEventType.GO_AROUND -> listOfNotNull(
                event.callsign,
                "RWY ${event.runway}",
                event.goAroundSummary?.take(90) ?: "Ratare de aterizare detectată"
            ).joinToString(" • ")
            else -> listOfNotNull(
                event.callsign,
                event.airline,
                event.aircraftType,
                "RWY ${event.runway}"
            ).joinToString(" • ")
        }

        val bigText = buildString {
            if (event.type == FlightEventType.EMERGENCY && !event.emergencyLabel.isNullOrBlank()) {
                append("URGENT: ${event.emergencyLabel}\n")
            }
            if (event.type == FlightEventType.GO_AROUND && !event.goAroundSummary.isNullOrBlank()) {
                append("${event.goAroundSummary}\n\n")
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
            builder.setColor(
                if (emCode == "PAN") Color.parseColor("#EA580C")
                else Color.parseColor("#DC2626")
            )
        } else if (event.type == FlightEventType.GO_AROUND) {
            builder.setColor(Color.parseColor("#E11D48"))
        }

        alertIdCounter += 2
        NotificationManagerCompat.from(context).notify(alertIdCounter, builder.build())
    }

    fun notifyBackfillRecovered(context: Context, count: Int) {
        if (count <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val openIntent = PendingIntent.getActivity(
            context,
            9001,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_APPROACH)
            .setSmallIcon(R.drawable.ic_plane_notif)
            .setContentTitle("SAT CND — Zboruri recuperate după reconectare")
            .setContentText("Au fost adăugate $count zboruri din perioada offline (OpenSky) în Istoric și Raport.")
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        NotificationManagerCompat.from(context).notify(9001, notif)
    }

    fun notifyDobrogeaDroneAlert(context: Context, target: DroneTarget, thresholdKm: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val simTag = if (target.simulated) " [TEST]" else ""
        val title = if (target.insideDobrogea) {
            "ALERTĂ DRONĂ ÎN DOBROGEA$simTag"
        } else {
            "ALERTĂ DRONĂ < $thresholdKm KM DE DOBROGEA$simTag"
        }

        val distPart = if (target.insideDobrogea) {
            "ÎN SPAȚIUL AERIAN DOBROGEA (zona ${target.nearestLandmark})"
        } else {
            "${target.distToDobrogeaKm} km de ${target.nearestLandmark}"
        }
        val summaryLine = "${target.type} • $distPart"
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale("ro", "RO")).format(Date(target.timestampMs))

        val bigText = buildString {
            append("$distPart\n")
            append("Tip țintă: ${target.type}\n")
            append("Distanță față de Aeroport CND: ${target.distToCndKm} km (${target.bearingFromCnd})")
            if (target.courseDeg != null || target.speedKmh != null) {
                val c = target.courseDeg?.let { "${it}°" } ?: "—"
                val s = target.speedKmh?.let { "$it km/h" } ?: "—"
                append("\nCurs: $c • Viteză estimată: $s")
            }
            if (target.label.isNotBlank()) {
                append("\nDetalii sursă: ${target.label}")
            }
            append("\nSursă: ${target.source} • Ora: $timeFmt")
            append("\n(Alertă OSINT informativă — verificați RO-Alert / MApN)")
        }

        val openDroneTabIntent = PendingIntent.getActivity(
            context,
            alertIdCounter,
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_DRONE)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openDimapIntent = PendingIntent.getActivity(
            context,
            alertIdCounter + 1,
            Intent(Intent.ACTION_VIEW, Uri.parse(DobrogeaDroneMonitor.DIMAP_LITE_URL)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_DRONE_DOBROGEA)
            .setSmallIcon(R.drawable.ic_plane_notif)
            .setContentTitle(title)
            .setContentText(summaryLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(Color.parseColor("#DC2626"))
            .setAutoCancel(true)
            .setContentIntent(openDroneTabIntent)
            .addAction(0, "Deschide dimap.live", openDimapIntent)
            .build()

        alertIdCounter += 2
        NotificationManagerCompat.from(context).notify(alertIdCounter, notif)
    }

    fun notifyDobrogeaBorderAlarm(context: Context, alarm: BorderRegionAlarm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val openDroneTabIntent = PendingIntent.getActivity(
            context,
            alertIdCounter,
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_DRONE)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openDimapIntent = PendingIntent.getActivity(
            context,
            alertIdCounter + 1,
            Intent(Intent.ACTION_VIEW, Uri.parse(DobrogeaDroneMonitor.DIMAP_LITE_URL)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "ALERTĂ AERIANĂ LA GRANIȚA DOBROGEI"
        val summary = "${alarm.regionName} (${alarm.alertType})"
        val bigText = buildString {
            append("Alertă activă: ${alarm.regionName}\n")
            append("Tip alertă: ${alarm.alertType}\n")
            append("Sursă: ${alarm.source} (api.ukrainealarm.com)\n")
            append("Deschide harta live dimap.live în tab-ul Drone pentru monitorizare vizuală.\n")
            append("(Alertă informativă — urmăriți RO-Alert / MApN)")
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_DRONE_DOBROGEA)
            .setSmallIcon(R.drawable.ic_plane_notif)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(Color.parseColor("#DC2626"))
            .setAutoCancel(true)
            .setContentIntent(openDroneTabIntent)
            .addAction(0, "Deschide dimap.live", openDimapIntent)
            .build()

        alertIdCounter += 2
        NotificationManagerCompat.from(context).notify(alertIdCounter, notif)
    }
}
