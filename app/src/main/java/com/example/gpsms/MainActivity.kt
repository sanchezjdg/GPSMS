package com.example.gpsms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    // Location client and callback for real-time updates.
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var handler: Handler

    // Array of fixed destination DNS/IP addresses stored in strings.xml.
    private lateinit var ipAddressList: Array<String>

    // TextView to display the sent message.
    private lateinit var messageTextView: TextView

    // Fixed UDP port value for communication.
    private val udpPort = 777

    // Toggle state for tracking.
    private var isTracking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get the fixed DNS/IP addresses from resources.
        ipAddressList = resources.getStringArray(R.array.ip_addresses)
        messageTextView = findViewById(R.id.messageTextView)
        val toggleTrackingButton: Button = findViewById(R.id.startTrackingButton)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        handler = Handler(Looper.getMainLooper())

        // Request necessary location permissions.
        requestPermissions()

        // Set initial button text from resources.
        toggleTrackingButton.text = getString(R.string.iniciar_rastreo)

        // Toggle tracking when the button is pressed.
        toggleTrackingButton.setOnClickListener {
            if (isTracking) {
                stopLocationUpdates()
                toggleTrackingButton.text = getString(R.string.iniciar_rastreo)
            } else {
                if (checkPermissions()) {
                    startLocationUpdates()
                    toggleTrackingButton.text = getString(R.string.detener_rastreo)
                } else {
                    requestPermissions()
                }
            }
            isTracking = !isTracking
        }
    }

    // Check that the necessary location permissions are granted.
    private fun checkPermissions(): Boolean {
        val fineLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fineLocationPermission == PackageManager.PERMISSION_GRANTED &&
                coarseLocationPermission == PackageManager.PERMISSION_GRANTED
    }

    // Request necessary location permissions.
    private fun requestPermissions() {
        if (!checkPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                101
            )
        }
    }

    // Handle the result of the permission request.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, getString(R.string.permisos_concedidos), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.permisos_denegados), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Check if location services are enabled.
    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // Start receiving location updates with a 10-second interval.
    private fun startLocationUpdates() {
        if (!isLocationEnabled()) {
            Toast.makeText(this, getString(R.string.ubicacion_desactivada), Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        // Create a LocationRequest with a 10-second interval.
        val locationRequest = LocationRequest.Builder(10000L).apply {
            setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
        }.build()

        // Define the location callback to process real-time updates.
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // Use the most recent location update.
                locationResult.lastLocation?.let { location ->
                    sendMessageWithLocation(location)
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // Start location updates.
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        Toast.makeText(this, getString(R.string.rastreo_iniciado), Toast.LENGTH_SHORT).show()
    }

    // Build the message with location data and send it via UDP to all fixed IPs.
    private fun sendMessageWithLocation(location: Location) {
        val formattedTime = SimpleDateFormat("HH:mm:ss - dd-MM-yyyy", Locale.getDefault()).format(Date(location.time))

        // Construct the message string.
        val message = """
            |=== UBICACION ===
            |Latitud: ${location.latitude}
            |Longitud: ${location.longitude}
            |Altitud: ${location.altitude}
            |Tiempo: $formattedTime
            |======
        """.trimMargin()

        Log.d("MSG_DEBUG", "Enviando mensaje: $message")

        // Loop through all fixed IP addresses and send the UDP message to each.
        ipAddressList.forEach { ip ->
            sendUDPMessage(message, ip, udpPort)
        }

        // Update the TextView on the UI thread with the sent message.
        runOnUiThread {
            messageTextView.text = message
        }
    }

    // Stop receiving location updates.
    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Toast.makeText(this, getString(R.string.rastreo_finalizado), Toast.LENGTH_SHORT).show()
    }

    // Send a UDP message on a separate thread.
    private fun sendUDPMessage(message: String, ip: String, port: Int) {
        Thread {
            try {
                val datagramSocket = DatagramSocket()
                val buffer = message.toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(ip)
                val packet = DatagramPacket(buffer, buffer.size, address, port)
                datagramSocket.send(packet)
                // Closing the socket frees up system resources and releases the port.
                datagramSocket.close()
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.mensaje_udp_enviado, ip), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Error en UDP: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
