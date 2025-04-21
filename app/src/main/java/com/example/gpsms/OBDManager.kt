package com.example.gpsms

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

class OBDManager {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream

    fun setupELM(input: InputStream, output: OutputStream) {
        this.input = input
        this.output = output

        sendCommand("ATZ")   // Reset
        sendCommand("ATE0")  // Echo off
        sendCommand("ATL0")  // Linefeeds off
        sendCommand("ATS0")  // Spaces off
        sendCommand("ATH0")  // Headers off
        sendCommand("ATSP0") // Auto protocol
    }

    private fun sendCommand(cmd: String): String {
        val full = (cmd + "\r").toByteArray()
        output.write(full)
        output.flush()
        clearBuffer()
        val response = readResponse()
        Log.d("OBD", "Sent: $cmd | Response: $response")
        return response
    }

    private fun clearBuffer() {
        while (input.available() > 0) {
            input.read()
        }
    }

    private fun readResponse(): String {
        val buffer = ByteArray(1024)
        val len = input.read(buffer)
        return String(buffer, 0, len).trim()
    }

    fun readPID(pidHex: Int): Int {
        val pid = String.format(Locale.US, "%02X", pidHex)
        val command = "01$pid"
        val response = sendCommand(command)

        if (response.contains("NO DATA") || response.isEmpty()) {
            Log.e("OBD", "No data received for PID $pid")
            return -1
        }

        val parts = response.split(" ").filter { it.isNotEmpty() }

        if (parts.size < 4 || parts[0] != "41" || parts[1] != pid) {
            Log.e("OBD", "Invalid response for PID $pid: $response")
            return -1
        }

        val A = parts[2].toInt(16)
        val B = parts[3].toInt(16)

        return when (pidHex) {
            0x0C -> (A * 256 + B) / 4  // RPM
            0x0D -> A                 // Speed (km/h)
            else -> A
        }
    }
}

