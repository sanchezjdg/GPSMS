package com.example.gpsms

import java.io.InputStream               // For reading from ELM327
import java.io.OutputStream              // For writing to ELM327
import java.util.Locale                  // ← Required for String.format(Locale.US,…)

class OBDManager {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream

    /**
     * Initialize the ELM327 adapter with standard AT commands.
     */
    fun setupELM(input: InputStream, output: OutputStream) {
        this.input = input
        this.output = output
        sendCommand("ATZ")    // Reset device
        sendCommand("ATE0")   // Echo off
        sendCommand("ATL0")   // Linefeeds off
        sendCommand("ATS0")   // Spaces off
        sendCommand("ATH0")   // Headers off
        sendCommand("ATSP0")  // Automatic protocol selection
    }

    /**
     * Send a single AT or OBD command and consume the 'OK' echo.
     */
    private fun sendCommand(cmd: String) {
        val full = (cmd + "\r").toByteArray()
        output.write(full)
        output.flush()
        readResponse()  // Discard the echo/OK
    }

    /**
     * Read raw response from ELM327.
     */
    private fun readResponse(): String {
        val buffer = ByteArray(1024)
        val len = input.read(buffer)
        return String(buffer, 0, len).trim()
    }

    /**
     * Send a PID request (e.g. 0x0C for RPM), parse and convert to integer.
     * Supports Engine RPM as (A*256 + B)/4.
     */
    fun readPID(pidHex: Int): Int {
        // Format PID as two‑digit hex
        val pid = String.format(Locale.US, "%02X", pidHex)
        // Write request: '01 <PID>'
        output.write(("01$pid\r").toByteArray())
        output.flush()

        // Example response: "41 0C 1A F8"
        val resp = readResponse()
        val parts = resp.split(" ").filter { it.isNotEmpty() }

        // Check header (0x41) and matching PID
        if (parts.size >= 4 && parts[0] == "41" && parts[1] == pid) {
            val A = parts[2].toInt(16)
            val B = parts[3].toInt(16)
            return when (pidHex) {
                0x0C -> (A * 256 + B) / 4  // Engine RPM formula
                else -> A                  // Default: return A for single‑byte sensors
            }
        }
        // Fallback on parse error
        return -1
    }
}
