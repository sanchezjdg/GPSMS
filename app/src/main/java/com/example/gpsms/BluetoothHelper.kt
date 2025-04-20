package com.example.gpsms

import android.Manifest
import android.bluetooth.BluetoothAdapter      // Android Bluetooth API
import android.bluetooth.BluetoothDevice     // Represents a remote Bluetooth device
import android.bluetooth.BluetoothSocket     // RFCOMM socket for Bluetooth communication
import android.content.Context               // Needed for context-sensitive operations
import androidx.annotation.RequiresPermission
import java.io.IOException                   // Exception for I/O errors
import java.util.UUID                       // For specifying the SPP UUID

/**
 * BluetoothHelper manages discovery, pairing, and RFCOMM connection
 * to an ELM327 OBD-II adapter over Bluetooth.
 */
class BluetoothHelper(private val context: Context) {
    // Get the default Bluetooth adapter on the device
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    // Standard Serial Port Profile (SPP) UUID for ELM327 devices
    private val ELM_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    /**
     * Returns all currently paired (bonded) Bluetooth devices.
     * Typically contains the ELM327 adapter if previously paired.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getPairedDevices(): Set<BluetoothDevice> {
        return adapter?.bondedDevices ?: emptySet()
    }

    /**
     * Attempts to connect to the given BluetoothDevice over RFCOMM.
     * @param device The paired ELM327 device to connect to.
     * @return A connected BluetoothSocket, or null if connection fails.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(device: BluetoothDevice): BluetoothSocket? {
        return try {
            // Create an RFCOMM socket using the SPP UUID
            val socket = device.createRfcommSocketToServiceRecord(ELM_UUID)
            // Initiate connection (blocking call)
            socket.connect()
            socket
        } catch (e: IOException) {
            e.printStackTrace()               // Log the exception for debugging
            null                               // Return null to indicate failure
        }
    }

    /**
     * Closes any open RFCOMM socket to clean up resources.
     * Call this in onDestroy() to avoid resource leaks.
     */
    fun disconnect(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (e: IOException) {
            e.printStackTrace()               // Log any errors during close
        }
    }
}
