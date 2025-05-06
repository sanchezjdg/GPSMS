package com.example.gpsms

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Enhanced BluetoothHelper with dynamic UUID support for RFCOMM services,
 * including insecure socket fallback for ELM327 clones.
 */
class BluetoothHelper(private val context: Context) {
    private val TAG = "BluetoothHelper"
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }

    private var currentSocket: BluetoothSocket? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getPairedDevices(): List<BluetoothDevice> {
        val devices = adapter?.bondedDevices?.toList() ?: emptyList()
        return devices.sortedBy { device ->
            device.name?.lowercase()?.let { name ->
                when {
                    name.contains("obd") ||
                            name.contains("elm") ||
                            name.contains("scanner") ||
                            name.contains("car") -> 0
                    else -> 1
                }
            } ?: 1
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connect(device: BluetoothDevice): BluetoothSocket? =
        connect(device, SPP_UUID)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connect(
        device: BluetoothDevice,
        serviceUuid: UUID,
        maxRetries: Int = 3
    ): BluetoothSocket? = withContext(Dispatchers.IO) {
        disconnect()
        Log.d(TAG, "Attempting connection to ${device.name} [${device.address}] with UUID $serviceUuid")

        var socket: BluetoothSocket? = null
        repeat(maxRetries) { attempt ->
            try {
                // Try standard secure RFCOMM
                socket = device.createRfcommSocketToServiceRecord(serviceUuid)
                Log.d(TAG, "Using secure socket, attempt ${attempt + 1}/$maxRetries")
                socket.connect()
                currentSocket = socket
                Log.d(TAG, "Secure connect successful on attempt ${attempt + 1}")
                return@withContext socket
            } catch (e: IOException) {
                Log.w(TAG, "Secure connect failed on attempt ${attempt + 1}: ${e.message}")
                try { socket?.close() } catch (_: Exception) {}
                // Fallback to insecure if last secure attempt
                if (attempt == maxRetries - 1) {
                    try {
                        socket = device.createInsecureRfcommSocketToServiceRecord(serviceUuid)
                        Log.d(TAG, "Using insecure socket fallback")
                        socket.connect()
                        currentSocket = socket
                        Log.d(TAG, "Insecure connect successful")
                        return@withContext socket
                    } catch (ie: IOException) {
                        Log.e(TAG, "Insecure fallback failed: ${ie.message}")
                        try { socket?.close() } catch (_: Exception) {}
                    }
                }
                delay(1000)
            }
        }
        Log.e(TAG, "All connection attempts failed")
        null
    }

    fun disconnect() {
        currentSocket?.let { socket ->
            try {
                Log.d(TAG, "Disconnecting Bluetooth socket")
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error disconnecting: ${e.message}")
            } finally {
                currentSocket = null
            }
        }
    }

    fun disconnect(socket: BluetoothSocket?) {
        if (socket == null) return
        if (socket == currentSocket) disconnect()
        else {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing socket: ${e.message}")
            }
        }
    }
}
