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
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    // Location client and callback for receiving updates.
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var handler: Handler

    // Counter to ensure we send only one message per activation.
    private var counter = 0
    private var permissionRequestedOnce = false

    // Spinner for selecting a destination IP from a predefined list.
    private lateinit var ipSpinner: Spinner
    private lateinit var ipAddressList: Array<String>

    // TextView to display the message that was sent.
    private lateinit var messageTextView: TextView

    // Fixed port values for TCP and UDP communication.
    private val TCP_PORT = 6060
    private val UDP_PORT = 777

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components.
        ipSpinner = findViewById(R.id.ipSpinner)
        messageTextView = findViewById(R.id.messageTextView)
        ipAddressList = resources.getStringArray(R.array.ip_addresses)

        // Set up the spinner adapter with the predefined IP addresses.
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ipAddressList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ipSpinner.adapter = adapter

        val startTrackingButton: Button = findViewById(R.id.startTrackingButton)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        handler = Handler(Looper.getMainLooper())

        // Request necessary permissions.
        requestPermissions()

        startTrackingButton.setOnClickListener {
            if (checkPermissions()) {
                // Start location updates immediately on button press.
                startLocationUpdates()
            } else {
                requestPermissions()
            }
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
            when {
                grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED } -> {
                    Toast.makeText(this, "Permisos concedidos", Toast.LENGTH_SHORT).show()
                }
                !permissionRequestedOnce -> {
                    permissionRequestedOnce = true
                    Toast.makeText(this, "Permisos necesarios para la app. Vuelve a intentarlo.", Toast.LENGTH_LONG).show()
                    requestPermissions()
                }
                else -> {
                    Toast.makeText(this, "Permisos denegados. No se puede continuar.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Check if location services are enabled on the device.
    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // Start receiving location updates immediately.
    private fun startLocationUpdates() {
        if (!isLocationEnabled()) {
            Toast.makeText(this, "La ubicación está desactivada. Actívela para continuar.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        // Create a location request using the new Builder (priority high accuracy).
        val locationRequest = LocationRequest.Builder(0L).apply {
            setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
        }.build()

        // Define the location callback.
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // Use the most recent location update.
                locationResult.lastLocation?.let { location ->
                    sendMessageWithLocation(location)
                    // Stop location updates immediately after sending the message.
                    stopLocationUpdates()
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // Request location updates.
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())

        Toast.makeText(this, "Rastreo iniciado", Toast.LENGTH_SHORT).show()
        // Reset the counter to allow one message to be sent.
        counter = 0
    }

    // Build the message with location data and send it via TCP and UDP.
    private fun sendMessageWithLocation(location: Location) {
        // Ensure that we only send one message per activation.
        if (counter >= 1) return

        // Format the current time using the location's timestamp.
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

        // Send the message via TCP and UDP using fixed port constants.
        sendTCPMessage(message, selectedIp, TCP_PORT)
        sendUDPMessage(message, selectedIp, UDP_PORT)

        // Update the TextView to display the sent message.
        runOnUiThread {
            messageTextView.text = message
        }

        counter++
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
                val datagramSocket = DatagramSocket()
                val buffer = message.toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(ip)
                val packet = DatagramPacket(buffer, buffer.size, address, port)
                datagramSocket.send(packet)
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

    // Send a message via TCP in a separate thread.
    private fun sendTCPMessage(message: String, ip: String, port: Int) {
        Thread {
            try {
                val socket = Socket(ip, port)
                val outputStream = socket.getOutputStream()
                val writer = PrintWriter(outputStream, true)
                writer.println(message)
                writer.flush()  // Ensure all data is sent

                // Initiate a graceful shutdown of the output stream
                socket.shutdownOutput()

                // Optionally, wait a short moment for the remote to acknowledge (if needed)
                Thread.sleep(100)

                writer.close()
                socket.close()

                runOnUiThread {
                    Toast.makeText(this, "Mensaje TCP enviado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Error en TCP: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
