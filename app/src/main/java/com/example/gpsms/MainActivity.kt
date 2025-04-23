package com.example.gpsms

import android.Manifest
import android.bluetooth.BluetoothSocket
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import android.util.Log

class MainActivity : AppCompatActivity() {

    // --- UI Elements ---
    private lateinit var messageTextView: TextView
    private lateinit var spinnerVehicle: Spinner
    private lateinit var spinnerDevices: Spinner
    private lateinit var toggleTrackingButton: Button
    private lateinit var btnConnectOBD: Button
    private lateinit var tvRPM: TextView

    // --- State ---
    private var isTracking = false
    private var obdJob: Job? = null
    private var obdSocket: BluetoothSocket? = null

    // --- Bluetooth and OBD Managers ---
    private lateinit var btHelper: BluetoothHelper
    private lateinit var obdManager: OBDManager

    // --- Permissions for Bluetooth ---
    @RequiresApi(Build.VERSION_CODES.S)
    private val bluetoothPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    private val bluetoothPermissionRequestCode = 1001

    @RequiresApi(Build.VERSION_CODES.S)
    private fun hasBluetoothPermissions(): Boolean {
        return bluetoothPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestBluetoothPermissions() {
        ActivityCompat.requestPermissions(this, bluetoothPermissions, bluetoothPermissionRequestCode)
    }


    // --- Broadcast receiver for location updates ---
    private val locationUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.LOCATION_UPDATE_ACTION) {
                intent.getStringExtra(LocationService.EXTRA_LOCATION_MESSAGE)?.let {
                    messageTextView.text = it
                }
                intent.getStringExtra(LocationService.EXTRA_TOAST_MESSAGE)?.let {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        // --- Bind UI ---
        messageTextView = findViewById(R.id.messageTextView)
        spinnerVehicle = findViewById(R.id.spinner_vehicle)
        spinnerDevices = findViewById(R.id.spinnerDevices)
        toggleTrackingButton = findViewById(R.id.startTrackingButton)
        btnConnectOBD = findViewById(R.id.btnConnectOBD)
        tvRPM = findViewById(R.id.tvRPM)

        // --- Vehicle Spinner setup ---
        ArrayAdapter.createFromResource(
            this,
            R.array.vehicle_ids,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerVehicle.adapter = adapter
        }

        val prefs = getSharedPreferences("gps_prefs", MODE_PRIVATE)
        spinnerVehicle.setSelection(prefs.getInt("vehicle_id", 1) - 1)
        spinnerVehicle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedId = parent.getItemAtPosition(position).toString().toInt()
                prefs.edit().putInt("vehicle_id", selectedId).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // --- Permissions ---
        requestPermissions()

        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        // --- Init OBD ---
        btHelper = BluetoothHelper(this)
        obdManager = OBDManager()

        // --- Populate Bluetooth Devices ---
        val pairedDevices = btHelper.getPairedDevices().toList()
        val deviceNames = pairedDevices.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDevices.adapter = adapter

        // --- GPS Toggle Button ---
        toggleTrackingButton.text = getString(R.string.iniciar_rastreo)
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

        // --- Connect OBD Button ---
        btnConnectOBD.setOnClickListener {
            val idx = spinnerDevices.selectedItemPosition

            if (idx == -1 || pairedDevices.isEmpty()) {
                Toast.makeText(this, "No Bluetooth devices available", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Show connecting status
            btnConnectOBD.isEnabled = false
            btnConnectOBD.text = "Connecting..."
            tvRPM.text = "Connecting to OBD..."

            val device = pairedDevices[idx]
            Log.d("MainActivity", "Attempting to connect to device: ${device.name} (${device.address})")

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Step 1: Connect to Bluetooth device
                    withContext(Dispatchers.Main) {
                        tvRPM.text = "Establishing Bluetooth connection..."
                    }

                    val socket = btHelper.connect(device)
                    if (socket == null) {
                        withContext(Dispatchers.Main) {
                            btnConnectOBD.text = "Connect OBD"
                            btnConnectOBD.isEnabled = true
                            tvRPM.text = "Bluetooth connection failed"
                            Toast.makeText(this@MainActivity, "Failed to connect via Bluetooth", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    // Step 2: Initialize the OBD adapter
                    withContext(Dispatchers.Main) {
                        tvRPM.text = "Initializing OBD adapter..."
                    }

                    obdSocket = socket
                    val initialized = obdManager.setupELM(socket.inputStream, socket.outputStream)

                    if (initialized) {
                        // Step 3: Start monitoring
                        withContext(Dispatchers.Main) {
                            tvRPM.text = "OBD connected! Waiting for data..."
                            btnConnectOBD.text = "Connected"
                            btnConnectOBD.isEnabled = true
                            Toast.makeText(this@MainActivity, "OBD Connected Successfully", Toast.LENGTH_SHORT).show()
                        }

                        // Cancel existing job if any
                        obdJob?.cancel()

                        // Start new polling job with error handling
                        obdJob = launch {
                            try {
                                // Test read to verify connection works
                                val initialRpm = obdManager.readPID(0x0C)
                                withContext(Dispatchers.Main) {
                                    if (initialRpm > 0) {
                                        tvRPM.text = "RPM: $initialRpm"
                                    } else {
                                        tvRPM.text = "RPM: Waiting for engine data..."
                                    }
                                }

                                // Begin regular polling
                                pollRPM()
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error in initial RPM read: ${e.message}")
                                withContext(Dispatchers.Main) {
                                    tvRPM.text = "Error reading RPM data"
                                }
                            }
                        }
                    } else {
                        btHelper.disconnect(socket)
                        withContext(Dispatchers.Main) {
                            btnConnectOBD.text = "Connect OBD"
                            btnConnectOBD.isEnabled = true
                            tvRPM.text = "OBD initialization failed"
                            Toast.makeText(this@MainActivity, "Failed to initialize OBD adapter. Try again or use a different adapter.", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Exception during OBD connection: ${e.message}")
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        btnConnectOBD.text = "Connect OBD"
                        btnConnectOBD.isEnabled = true
                        tvRPM.text = "Connection error: ${e.message}"
                        Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // --- Register location receiver ---
        registerReceiver(
            locationUpdateReceiver,
            IntentFilter(LocationService.LOCATION_UPDATE_ACTION),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        obdJob?.cancel()
        btHelper.disconnect(obdSocket) // Close socket if connected
        try {
            unregisterReceiver(locationUpdateReceiver)
        } catch (_: Exception) {}
    }

    /**
     * Polls RPM (PID 0x0C) continuously while coroutine is active
     */
    // Updated pollRPM function with more resilient error handling
    private suspend fun CoroutineScope.pollRPM() {
        var consecutiveErrors = 0

        while (isActive) {
            try {
                val rpm = obdManager.readPID(0x0C)

                withContext(Dispatchers.Main) {
                    if (rpm > 0) {
                        consecutiveErrors = 0 // Reset error counter on success
                        tvRPM.text = "RPM: $rpm"

                        // Send RPM to LocationService if tracking is active
                        if (isTracking) {
                            val intent = Intent(this@MainActivity, LocationService::class.java).apply {
                                action = LocationService.ACTION_UPDATE_RPM
                                putExtra(LocationService.EXTRA_RPM_VALUE, rpm)
                            }
                            startService(intent)
                        }
                    } else {
                        // Don't count "-1" as an error if the engine might be off
                        tvRPM.text = "RPM: Waiting for data..."
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error polling RPM: ${e.message}")
                consecutiveErrors++

                withContext(Dispatchers.Main) {
                    if (consecutiveErrors > 5) {
                        tvRPM.text = "Connection problems. Try reconnecting."
                    } else {
                        tvRPM.text = "RPM: Reading error (${consecutiveErrors})"
                    }
                }

                // If too many consecutive errors, consider reconnecting
                if (consecutiveErrors > 10) {
                    Log.w("MainActivity", "Too many consecutive errors. Stopping RPM polling.")
                    break
                }
            }

            delay(1000L)
        }

        withContext(Dispatchers.Main) {
            tvRPM.text = "RPM monitoring stopped"
        }
    }



    /**
     * Permission and service utilities
     */
    private fun checkPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else PackageManager.PERMISSION_GRANTED

        return fine == PackageManager.PERMISSION_GRANTED &&
                coarse == PackageManager.PERMISSION_GRANTED &&
                background == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
    }

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

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun startLocationService() {
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_START_LOCATION_SERVICE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        Toast.makeText(this, getString(R.string.rastreo_iniciado), Toast.LENGTH_SHORT).show()
    }

    private fun stopLocationService() {
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP_LOCATION_SERVICE
        }
        startService(intent)
        Toast.makeText(this, getString(R.string.rastreo_finalizado), Toast.LENGTH_SHORT).show()
    }
}
