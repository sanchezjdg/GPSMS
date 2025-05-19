package com.example.gpsms

import android.bluetooth.BluetoothSocket
import com.github.pires.obd.commands.engine.RPMCommand
import com.github.pires.obd.commands.protocol.EchoOffCommand
import com.github.pires.obd.commands.protocol.LineFeedOffCommand
import com.github.pires.obd.commands.protocol.SelectProtocolCommand
import com.github.pires.obd.enums.ObdProtocols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OBDManager using pires/obd-java-api library for robust OBD-II communication.
 */
class OBDManager(private val socket: BluetoothSocket) {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private var initialized = false

    /**
     * Initializes the ELM327 adapter: disables echo/linefeed and sets protocol to auto.
     */
    suspend fun setupELM(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            // Standard initialization commands
            EchoOffCommand().run(input, output)
            LineFeedOffCommand().run(input, output)
            SelectProtocolCommand(ObdProtocols.AUTO).run(input, output)
            initialized = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Reads the current engine RPM (PID 0x0C).
     * @return RPM value or -1 on error.
     */
    suspend fun readRPM(): Int = withContext(Dispatchers.IO) {
        if (!initialized) throw IllegalStateException("Call setupELM() before readRPM()")
        return@withContext try {
            val cmd = RPMCommand()
            cmd.run(input, output)
            cmd.rpm
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }

    /**
     * Closes the Bluetooth socket connection.
     */
    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {}
    }
}
