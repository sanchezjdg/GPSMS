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
import android.widget.*
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

    // Location client and callback for receiving updates.
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Handler for scheduling tasks.
    private lateinit var handler: Handler

    // Spinner for selecting a destination IP from a predefined list.
    private lateinit var ipSpinner: Spinner
    private lateinit var ipAddressList: Array<String>

    // TextView to display the message that was sent.
    private lateinit var messageTextView: TextView

    // Fixed UDP port for communication.
    private val UDP_PORT = 777

    // Toggle state for tracking.
    private var isTracking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components.
        ipSpinner = findViewById(R.id.ipSpinner)
        messageTextView = findViewById(R.id.messageTextView)
        ipAddressList = resources.getStringArray(R.array.ip_addresses)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ipAddressList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ipSpinner.adapter = adapter

        val toggleTrackingButton: Button = findViewById(R.id.startTrackingButton)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        handler = Handler(Looper.getMainLooper())

        // Request necessary location permissions.
        requestPermissions()

        // Set the toggle button text based on the tracking state.
        toggleTrackingButton.text = "Iniciar Rastreo"

        // Toggle tracking when the button is pressed.
        toggleTrackingButton.setOnClickListener {
            if (isTracking) {
                stopLocationUpdates()
                toggleTrackingButton.text = "Iniciar Rastreo"
            } else {
                if (checkPermissions()) {
                    startLocationUpdates()
                    toggleTrackingButton.text = "Detener Rastreo"
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

    // Request the necessary location permissions.
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
                Toast.makeText(this, "Permisos concedidos", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permisos denegados. No se puede continuar.", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "La ubicación está desactivada. Actívela para continuar.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        // Create a LocationRequest with a 10-second interval.
        val locationRequest = LocationRequest.Builder(10000L).apply {
            setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
        }.build()

        // Define the location callback.
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // Use the most recent location update.
                locationResult.lastLocation?.let { location ->
                    sendMessageWithLocation(location)
                }
            }
        }

        // Check for permission again.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // Start location updates. These will trigger every 10 seconds.
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        Toast.makeText(this, "Rastreo iniciado", Toast.LENGTH_SHORT).show()
    }

    // Build the message with location data and send it via UDP.
    private fun sendMessageWithLocation(location: Location) {
        // Format the location timestamp.
        val formattedTime = SimpleDateFormat("HH:mm:ss - dd-MM-yyyy", Locale.getDefault()).format(Date(location.time))

        // Build the message string with location details.
        val message = """
            |=== UBICACION ===
            |Latitud: ${location.latitude}
            |Longitud: ${location.longitude}
            |Altitud: ${location.altitude}
            |Tiempo: $formattedTime
            |======
        """.trimMargin()

        Log.d("MSG_DEBUG", "Enviando mensaje: $message")

        // Get the selected destination IP from the spinner.
        val selectedIp = ipSpinner.selectedItem.toString()

        // Send the message via UDP using the fixed port.
        sendUDPMessage(message, selectedIp, UDP_PORT)

        // Update the TextView on the UI thread to display the sent message.
        runOnUiThread {
            messageTextView.text = message
        }
    }

    // Stop receiving location updates.
    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Toast.makeText(this, "Rastreo finalizado", Toast.LENGTH_SHORT).show()
    }

    // Send a message via UDP in a separate thread.
    private fun sendUDPMessage(message: String, ip: String, port: Int) {
        Thread {
            try {
                // Create a new DatagramSocket.
                val datagramSocket = DatagramSocket()
                // Convert the message to bytes.
                val buffer = message.toByteArray(Charsets.UTF_8)
                // Get the InetAddress object for the destination IP.
                val address = InetAddress.getByName(ip)
                // Create a DatagramPacket with the message, destination address, and port.
                val packet = DatagramPacket(buffer, buffer.size, address, port)
                // Send the UDP packet.
                datagramSocket.send(packet)
                // Close the socket to release resources.
                datagramSocket.close()
                runOnUiThread {
                    Toast.makeText(this, "Mensaje UDP enviado", Toast.LENGTH_SHORT).show()
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
