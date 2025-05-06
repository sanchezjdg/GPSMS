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
import android.util.Log
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // --- UI Elements ---
    private lateinit var messageTextView: TextView
    private lateinit var spinnerVehicle: Spinner
    private lateinit var spinnerDevices: Spinner
    private lateinit var toggleTrackingButton: Button
    private lateinit var btnConnectOBD: Button
    private lateinit var tvRPM: TextView
    private lateinit var switchSimulation: Switch

    // --- State ---
    private var isTracking = false
    private var obdJob: Job? = null
    private var obdSocket: BluetoothSocket? = null
    private var isSimulationMode = false
    private var simulationJob: Job? = null

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
    private fun hasBluetoothPermissions(): Boolean =
        bluetoothPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
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
        switchSimulation = findViewById(R.id.switchSimulation)

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

        // --- Simulation Switch setup ---
        switchSimulation.setOnCheckedChangeListener { _, isChecked ->
            isSimulationMode = isChecked
            if (isSimulationMode) {
                obdJob?.cancel()
                btHelper.disconnect(obdSocket)
                obdSocket = null
                btnConnectOBD.isEnabled = false
                spinnerDevices.isEnabled = false
                tvRPM.text = "RPM: Simulation mode"
                simulationJob = CoroutineScope(Dispatchers.Default).launch { simulateRPM() }
                Toast.makeText(this, "RPM simulation started", Toast.LENGTH_SHORT).show()
            } else {
                simulationJob?.cancel()
                btnConnectOBD.isEnabled = true
                spinnerDevices.isEnabled = true
                tvRPM.text = "RPM: Not connected"
                Toast.makeText(this, "RPM simulation stopped", Toast.LENGTH_SHORT).show()
            }
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
        val pairedDevices = btHelper.getPairedDevices()
        spinnerDevices.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            pairedDevices.map { it.name ?: it.address }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        // --- GPS Toggle Button ---
        toggleTrackingButton.text = getString(R.string.iniciar_rastreo)
        toggleTrackingButton.setOnClickListener { toggleTracking() }

        // --- Connect OBD Button ---
        btnConnectOBD.setOnClickListener { connectToObd(pairedDevices) }

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
        simulationJob?.cancel()
        btHelper.disconnect(obdSocket)
        try { unregisterReceiver(locationUpdateReceiver) } catch (_: Exception) {}
    }

    private fun toggleTracking() {
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

    @RequiresApi(Build.VERSION_CODES.S)
    private fun connectToObd(pairedDevices: List<android.bluetooth.BluetoothDevice>) {
        // --- Permission check ---
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Request Bluetooth permissions and abort until user responds
            requestBluetoothPermissions()
            return
        }

        val idx = spinnerDevices.selectedItemPosition
        if (idx < 0 || pairedDevices.isEmpty()) {
            Toast.makeText(this, "No Bluetooth devices available", Toast.LENGTH_SHORT).show()
            return
        }
        btnConnectOBD.isEnabled = false
        btnConnectOBD.text = "Connecting..."
        tvRPM.text = "RPM: Connecting..."

        val device = pairedDevices[idx]
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Safe to call fetchUuidsWithSdp() now that we have BLUETOOTH_CONNECT
                if (device.uuids.isNullOrEmpty()) {
                    device.fetchUuidsWithSdp()
                    delay(500)
                }
                val uuid = device.uuids?.firstOrNull()?.uuid
                    ?: BluetoothHelper.SPP_UUID

                val socket = btHelper.connect(device, uuid)
                if (socket != null) {
                    obdSocket = socket
                    val initialized = obdManager.setupELM(socket.inputStream, socket.outputStream)
                    if (initialized) {
                        obdJob?.cancel()
                        obdJob = launch { pollRPM() }
                        withContext(Dispatchers.Main) {
                            btnConnectOBD.text = "Connected to OBD"
                            btnConnectOBD.isEnabled = true
                            Toast.makeText(
                                this@MainActivity,
                                "OBD Connected Successfully",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        btHelper.disconnect(socket)
                        showObdInitFailed()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showObdConnectFailed()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showObdError(e)
                }
            }
        }
    }

    private suspend fun CoroutineScope.pollRPM() {
        while (isActive) {
            try {
                val rpm = obdManager.readPID(0x0C)
                withContext(Dispatchers.Main) {
                    tvRPM.text = if (rpm > 0) "RPM: $rpm" else "RPM: Waiting for data..."
                    if (isTracking && rpm > 0) {
                        Intent(this@MainActivity, LocationService::class.java).apply {
                            action = LocationService.ACTION_UPDATE_RPM
                            putExtra(LocationService.EXTRA_RPM_VALUE, rpm)
                        }.also { startService(it) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvRPM.text = "RPM: Error - ${e.message}"
                }
            }
            delay(1000L)
        }
    }

    private suspend fun CoroutineScope.simulateRPM() {
        val random = java.util.Random()
        var trend = 0
        var currentSimRPM = 800
        while (isActive) {
            if (random.nextInt(10) == 0) trend = random.nextInt(3) - 1
            currentSimRPM = when (trend) {
                -1 -> (currentSimRPM - (random.nextInt(100) + 50)).coerceAtLeast(700)
                1  -> (currentSimRPM + (random.nextInt(150) + 50)).coerceAtMost(6000)
                else -> (currentSimRPM + (random.nextInt(60) - 30)).coerceIn(700, 6000)
            }
            withContext(Dispatchers.Main) {
                tvRPM.text = "RPM: $currentSimRPM (simulated)"
                if (isTracking) {
                    Intent(this@MainActivity, LocationService::class.java).apply {
                        action = LocationService.ACTION_UPDATE_RPM
                        putExtra(LocationService.EXTRA_RPM_VALUE, currentSimRPM)
                    }.also { startService(it) }
                }
            }
            delay(1000L)
        }
    }

    private fun showObdInitFailed() = runOnUiThread {
        btnConnectOBD.text = "Connect OBD"
        btnConnectOBD.isEnabled = true
        tvRPM.text = "RPM: Not connected"
        Toast.makeText(this, "Failed to initialize OBD adapter", Toast.LENGTH_SHORT).show()
    }

    private fun showObdConnectFailed() = runOnUiThread {
        btnConnectOBD.text = "Connect OBD"
        btnConnectOBD.isEnabled = true
        tvRPM.text = "RPM: Not connected"
        Toast.makeText(this, "OBD Connection Failed", Toast.LENGTH_SHORT).show()
    }

    private fun showObdError(e: Exception) = runOnUiThread {
        btnConnectOBD.text = "Connect OBD"
        btnConnectOBD.isEnabled = true
        tvRPM.text = "RPM: Error"
        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
    }

    // --- Permissions & Location Helpers (unchanged) ---
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
        Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_START_LOCATION_SERVICE
        }.also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it)
            else startService(it)
        }
        Toast.makeText(this, getString(R.string.rastreo_iniciado), Toast.LENGTH_SHORT).show()
    }

    private fun stopLocationService() {
        Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP_LOCATION_SERVICE
        }.also { startService(it) }
        Toast.makeText(this, getString(R.string.rastreo_finalizado), Toast.LENGTH_SHORT).show()
    }
}
