package com.example.gpsms

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

class OBDManager {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val TAG = "OBDManager"

    // Initialize flag to track if ELM has been set up
    private var isInitialized = false

    /**
     * Sets up the ELM327 adapter with standard AT commands,
     * verifies the protocol (ATDP), and forces CAN if necessary.
     */
    suspend fun setupELM(input: InputStream, output: OutputStream): Boolean {
        this.input = input
        this.output = output

        try {
            // Allow adapter startup
            delay(1500)

            // Reset adapter
            val resetResp = sendCommand("ATZ")
            Log.d(TAG, "Reset response: $resetResp")
            if (!resetResp.contains("ELM", ignoreCase = true)) {
                Log.e(TAG, "Failed to reset ELM327: $resetResp")
                return false
            }
            delay(1500)

            // Base configuration
            val baseCommands = listOf(
                "ATD",     // defaults
                "ATE0",    // echo off
                "ATL0",    // linefeeds off
                "ATS0",    // spaces off
                "ATH0"     // headers off
            )
            for (cmd in baseCommands) {
                val resp = sendCommand(cmd)
                Log.d(TAG, "Config cmd $cmd -> $resp")
                delay(500)
            }

            // Auto protocol detection
            sendCommand("ATSP0")
            delay(500)
            val proto = sendCommand("ATDP")
            Log.d(TAG, "Detected protocol: $proto")
            // Force CAN 11/500 if detection fails or wrong
            if (!proto.contains("15765", ignoreCase = true)) {
                val forced = sendCommand("ATSP6")
                Log.w(TAG, "Forcing CAN protocol: $forced")
                delay(500)
            }

            // Check ECU connectivity
            val ecuResp = sendCommand("0100")
            Log.d(TAG, "ECU check -> $ecuResp")
            if (!ecuResp.matchRegex("41 00")) {
                Log.e(TAG, "Failed ECU handshake: $ecuResp")
                return false
            }

            // Timing and protocol tweaks
            listOf("ATAT1", "ATST64").forEach { cmd ->
                val r = sendCommand(cmd)
                Log.d(TAG, "Timing cmd $cmd -> $r")
                delay(300)
            }

            isInitialized = true
            return true

        } catch (e: Exception) {
            Log.e(TAG, "setupELM error: ${e.message}")
            return false
        }
    }

    /**
     * Sends an AT or OBD command, waits for '>' prompt, returns raw response.
     */
    private fun sendCommand(cmd: String): String {
        val full = (cmd + "\r").toByteArray()
        try {
            clearBuffer()
            output.write(full); output.flush()
            val resp = readResponseWithTimeout(7000)
            Log.d(TAG, "Sent '$cmd' | Raw resp: ${resp.visualize()} ")
            return resp
        } catch (e: IOException) {
            Log.e(TAG, "sendCommand IOException: ${e.message}")
            return ""
        }
    }

    /**
     * Clears any residual bytes in the input buffer.
     */
    private fun clearBuffer() {
        try {
            if (!::input.isInitialized) return
            while (input.available() > 0) { input.read() }
        } catch (e: IOException) {
            Log.e(TAG, "clearBuffer error: ${e.message}")
        }
    }

    /**
     * Reads until '>' prompt or timeout.
     */
    private fun readResponseWithTimeout(timeoutMs: Long): String {
        val start = System.currentTimeMillis()
        val buf = StringBuilder()
        try {
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (input.available() > 0) {
                    val data = ByteArray(256)
                    val len = input.read(data)
                    buf.append(String(data, 0, len))
                    if (buf.contains('>')) break
                }
                Thread.sleep(50)
            }
        } catch (e: Exception) {
            Log.e(TAG, "readResponse error: ${e.message}")
        }
        return buf.toString().replace('>', ' ').trim()
    }

    /**
     * Reads a PID and returns its integer value (e.g., RPM or speed).
     */
    suspend fun readPID(pidHex: Int): Int = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.e(TAG, "readPID called before init")
            return@withContext -1
        }

        val pid = String.format(Locale.US, "%02X", pidHex)
        val cmd = "01$pid"
        val resp = sendCommand(cmd)
        if (resp.isBlank() || resp.contains("NO DATA", ignoreCase = true)) {
            return@withContext -1
        }

        // Normalize response
        val normalized = resp.uppercase(Locale.US).replace(Regex("\\s+"), " ")
        // Regex for mode 01 responses
        val regex = Regex("41 $pid ([0-9A-F]{2})(?: ([0-9A-F]{2}))?")
        val match = regex.find(normalized)
        if (match != null) {
            val bytes = match.groupValues.drop(1).filter { it.isNotEmpty() }
            try {
                return@withContext when (pidHex) {
                    0x0C -> { // RPM requires two bytes
                        val A = bytes[0].toInt(16)
                        val B = bytes[1].toInt(16)
                        (A * 256 + B) / 4
                    }
                    0x0D -> { // Speed one byte
                        bytes[0].toInt(16)
                    }
                    else -> bytes[0].toInt(16)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Parsing error for PID $pid: ${e.message}")
                return@withContext -1
            }
        }

        Log.e(TAG, "No valid data for PID $pid in: $normalized")
        -1
    }

    // Extension for easy visualization of control chars
    private fun String.visualize() = this
        .replace("\r", "\\r")
        .replace("\n", "\\n")

    // Helper to quickly test basic match before regex
    private fun String.matchRegex(pattern: String) =
        Regex(pattern.replace(" ", "\\s*")).containsMatchIn(this)
}
