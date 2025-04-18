package com.example.gpsms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView

class MainActivity : AppCompatActivity() {

    // TextView to display the sent message
    private lateinit var messageTextView: TextView

    // Toggle state for tracking
    private var isTracking = false

    // BroadcastReceiver for location updates
    private val locationUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.LOCATION_UPDATE_ACTION) {
                // Update TextView with location message
                val locationMessage = intent.getStringExtra(LocationService.EXTRA_LOCATION_MESSAGE)
                if (!locationMessage.isNullOrEmpty()) {
                    messageTextView.text = locationMessage
                }

                // Show toast if there's a toast message
                val toastMessage = intent.getStringExtra(LocationService.EXTRA_TOAST_MESSAGE)
                if (!toastMessage.isNullOrEmpty()) {
                    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        messageTextView = findViewById(R.id.messageTextView)
        val toggleTrackingButton: Button = findViewById(R.id.startTrackingButton)

        // Spinner for vehicle id
        val spinner: Spinner = findViewById(R.id.spinner_vehicle)
        ArrayAdapter.createFromResource(
            this,
            R.array.vehicle_ids,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
        }

        // Restore last selection or default to "1"
        val prefs = getSharedPreferences("gps_prefs", MODE_PRIVATE)
        spinner.setSelection(prefs.getInt("vehicle_id", 1) - 1)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                prefs.edit().putInt("vehicle_id", parent.getItemAtPosition(pos).toString().toInt()).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) { /* no-op */ }
        }

        // Request necessary location permissions
        requestPermissions()

        // Set initial button text from resources
        toggleTrackingButton.text = getString(R.string.iniciar_rastreo)

        // Toggle tracking when the button is pressed
        toggleTrackingButton.setOnClickListener {
            if (isTracking) {
                stopLocationService()
                toggleTrackingButton.text = getString(R.string.iniciar_rastreo)
            } else {
                if (checkPermissions()) {
                    if (isLocationEnabled()) {
                        startLocationService()
                        toggleTrackingButton.text = getString(R.string.detener_rastreo)
                    } else {
                        Toast.makeText(this, getString(R.string.ubicacion_desactivada), Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                } else {
                    requestPermissions()
                }
            }
            isTracking = !isTracking
        }

        // Register receiver for location updates
        registerReceiver(locationUpdateReceiver, IntentFilter(LocationService.LOCATION_UPDATE_ACTION),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Context.RECEIVER_EXPORTED
            } else {
                0
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the receiver to prevent memory leaks
        try {
            unregisterReceiver(locationUpdateReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    // Check that the necessary location permissions are granted
    private fun checkPermissions(): Boolean {
        val backgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            PackageManager.PERMISSION_GRANTED
        }

        val fineLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        return fineLocationPermission == PackageManager.PERMISSION_GRANTED &&
                coarseLocationPermission == PackageManager.PERMISSION_GRANTED &&
                backgroundLocationPermission == PackageManager.PERMISSION_GRANTED
    }

    // Request necessary location permissions
    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // For Android 10+ we need to request background location separately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            101
        )
    }

    // Handle the result of the permission request
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

    // Check if location services are enabled
    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // Start the foreground location service
    private fun startLocationService() {
        val intent = Intent(this, LocationService::class.java)
        intent.action = LocationService.ACTION_START_LOCATION_SERVICE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, getString(R.string.rastreo_iniciado), Toast.LENGTH_SHORT).show()
    }

    // Stop the foreground location service
    private fun stopLocationService() {
        val intent = Intent(this, LocationService::class.java)
        intent.action = LocationService.ACTION_STOP_LOCATION_SERVICE
        startService(intent)

        Toast.makeText(this, getString(R.string.rastreo_finalizado), Toast.LENGTH_SHORT).show()
    }
}