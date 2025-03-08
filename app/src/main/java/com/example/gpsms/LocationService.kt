package com.example.gpsms

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*

@Suppress("DEPRECATION")
class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationBuilder: NotificationCompat.Builder

    // Fixed UDP port value for communication
    private val udpPort = 777

    // Array of fixed destination DNS/IP addresses
    private lateinit var ipAddressList: Array<String>

    companion object {
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "location_channel"
        const val ACTION_START_LOCATION_SERVICE = "START_LOCATION_SERVICE"
        const val ACTION_STOP_LOCATION_SERVICE = "STOP_LOCATION_SERVICE"
        const val LOCATION_UPDATE_ACTION = "com.example.gpsms.LOCATION_UPDATE"
        const val EXTRA_LOCATION_MESSAGE = "location_message"
        const val EXTRA_TOAST_MESSAGE = "toast_message"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        ipAddressList = resources.getStringArray(R.array.ip_addresses)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Define location callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    sendMessageWithLocation(location)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_LOCATION_SERVICE) {
            startLocationUpdates()
        } else if (intent?.action == ACTION_STOP_LOCATION_SERVICE) {
            stopLocationUpdates()
            stopForeground(true)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startLocationUpdates() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Rastreo GPS activo")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        val notification = notificationBuilder.build()

        startForeground(NOTIFICATION_ID, notification)

        val locationRequest = LocationRequest.Builder(10000L).apply {
            setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e("LocationService", "Error starting location updates: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    // Update notification with new text
    private fun updateNotification(text: String) {
        notificationBuilder.setContentText(text)
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    // Send broadcast to activity to show toast and update TextView
    private fun sendBroadcastToActivity(locationMessage: String, toastMessage: String) {
        val intent = Intent(LOCATION_UPDATE_ACTION)
        intent.putExtra(EXTRA_LOCATION_MESSAGE, locationMessage)
        intent.putExtra(EXTRA_TOAST_MESSAGE, toastMessage)
        sendBroadcast(intent)
    }

    // Build the message with location data and send it via UDP to all fixed IPs
    private fun sendMessageWithLocation(location: Location) {
        val formattedTime = SimpleDateFormat("HH:mm:ss - dd-MM-yyyy", Locale.getDefault()).format(Date(location.time))

        // Construct the message string
        val message = """
            |Latitud: ${location.latitude}
            |Longitud: ${location.longitude}
            |Altitud: ${location.altitude}
            |Tiempo: $formattedTime
        """.trimMargin()

        Log.d("MSG_DEBUG", "Enviando mensaje: $message")

        // Update notification with the latest coordinates
        val notificationText = "Lat: ${location.latitude}, Lon: ${location.longitude}"
        updateNotification(notificationText)
        sendBroadcastToActivity(message, getString(R.string.mensaje_udp_enviado))

        // Loop through all fixed IP addresses and send the UDP message to each
        for (ip in ipAddressList) {
            sendUDPMessage(message, ip, udpPort)
        }
    }

    // Send a UDP message on a separate thread
    private fun sendUDPMessage(message: String, ip: String, port: Int) {
        Thread {
            try {
                val datagramSocket = DatagramSocket()
                val buffer = message.toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(ip)
                val packet = DatagramPacket(buffer, buffer.size, address, port)
                datagramSocket.send(packet)
                datagramSocket.close()
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("UDP_ERROR", "Error sending UDP: ${e.message}")
                // Send broadcast to activity with error message
                sendBroadcastToActivity("", "Error en UDP: ${e.message}")
            }
        }.start()
    }
}