package com.example.gpsms

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Enhanced BluetoothHelper with dynamic UUID support for RFCOMM services.
 */
class BluetoothHelper(private val context: Context) {
    private val TAG = "BluetoothHelper"

    // Default Bluetooth adapter
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    // Expose standard SPP UUID publicly for fallback or UI use
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }

    // Currently active socket
    private var currentSocket: BluetoothSocket? = null

    /**
     * Returns all currently paired (bonded) Bluetooth devices,
     * sorted so OBD adapters appear first.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getPairedDevices(): List<BluetoothDevice> {
        val devices = adapter?.bondedDevices?.toList() ?: emptyList()
        return devices.sortedBy { device ->
            val name = device.name?.lowercase() ?: ""
            when {
                name.contains("obd") ||
                        name.contains("elm") ||
                        name.contains("scanner") ||
                        name.contains("car") -> 0
                else -> 1
            }
        }
    }

    /**
     * Common substrings for identifying OBD adapters by name.
     */
    fun getCommonOBDDeviceNames(): List<String> {
        return listOf("OBDII", "ELM327", "OBD2", "V-Link", "Vgate", "Scanner", "Car")
    }

    /**
     * Heuristic check if a device is likely an OBD adapter.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isProbablyOBDAdapter(device: BluetoothDevice): Boolean {
        val name = device.name?.lowercase() ?: return false
        return getCommonOBDDeviceNames().any { name.contains(it.lowercase()) }
    }

    /**
     * Connects using the default SPP UUID.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connect(device: BluetoothDevice): BluetoothSocket? =
        connect(device, SPP_UUID)

    /**
     * Attempts to connect to the given device over RFCOMM with a dynamic UUID and retry logic.
     * @param device Bluetooth device to connect.
     * @param serviceUuid UUID of the target RFCOMM service.
     * @param maxRetries Number of retry attempts.
     * @return A connected BluetoothSocket or null on failure.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connect(
        device: BluetoothDevice,
        serviceUuid: UUID,
        maxRetries: Int = 3
    ): BluetoothSocket? = withContext(Dispatchers.IO) {
        // Clean up any previous connection
        disconnect()
        Log.d(TAG, "Connecting to ${'$'}{device.name} with UUID ${'$'}serviceUuid")

        var socket: BluetoothSocket? = null
        repeat(maxRetries) { attempt ->
            try {
                socket = device.createRfcommSocketToServiceRecord(serviceUuid)
                Log.d(TAG, "Connection attempt ${'$'}{attempt + 1}/$maxRetries")
                socket.connect()
                currentSocket = socket
                Log.d(TAG, "Successfully connected on attempt ${'$'}{attempt + 1}")
                return@withContext socket
            } catch (e: IOException) {
                Log.e(TAG, "Attempt ${'$'}{attempt + 1} failed: ${'$'}{e.message}")
                try { socket?.close() } catch (_: Exception) {}
                if (attempt == maxRetries - 1) return@withContext null
                delay(1000)
            }
        }
        null
    }

    /**
     * Disconnects the current socket, if any.
     */
    fun disconnect() {
        currentSocket?.let { socket ->
            try {
                Log.d(TAG, "Disconnecting Bluetooth socket")
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error disconnecting: ${'$'}{e.message}")
            } finally {
                currentSocket = null
            }
        }
    }

    /**
     * Closes the provided socket (if not the current one).
     */
    fun disconnect(socket: BluetoothSocket?) {
        if (socket != null && socket == currentSocket) {
            disconnect()
        } else if (socket != null) {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing socket: ${'$'}{e.message}")
            }
        }
    }

    /**
     * Check if Bluetooth is enabled.
     */
    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /**
     * Check if there is an active connection.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun isConnected(): Boolean {
        val socket = currentSocket ?: return false
        return try {
            socket.remoteDevice.name
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the name of the connected device.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getConnectedDeviceName(): String? = currentSocket?.remoteDevice?.name
}
