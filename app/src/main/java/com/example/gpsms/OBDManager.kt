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
            // Allow adapter startup with longer delay
            delay(2000)

            // Reset adapter with longer delay
            val resetResp = sendCommand("ATZ")
            Log.d(TAG, "Reset response: $resetResp")
            delay(2000)  // Extended delay after reset

            // Sometimes a second reset helps
            if (!resetResp.contains("ELM", ignoreCase = true)) {
                Log.w(TAG, "First reset didn't return expected response, trying again")
                val retry = sendCommand("ATZ")
                Log.d(TAG, "Second reset response: $retry")
                delay(2000)

                // If we still don't have ELM in the response, something's wrong
                if (!retry.contains("ELM", ignoreCase = true)) {
                    Log.e(TAG, "Failed to reset ELM327: $retry")
                    return false
                }
            }

            // Try to clear any pending communication
            sendCommand("")
            delay(300)

            // Base configuration - send one at a time with verification
            val baseCommands = listOf(
                "ATD",     // defaults
                "ATE0",    // echo off
                "ATL0",    // linefeeds off
                "ATS0",    // spaces off
                "ATH0",    // headers off
                "ATAT1",   // adaptive timing on
                "ATST64"   // timeout setting
            )

            for (cmd in baseCommands) {
                val resp = sendCommand(cmd)
                Log.d(TAG, "Config cmd $cmd -> $resp")

                // Verify command execution (most commands should return "OK")
                if (!resp.contains("OK", ignoreCase = true) && !resp.isBlank()) {
                    Log.w(TAG, "Command $cmd may have failed: $resp")
                }
                delay(300)
            }

            // Try multiple protocol options if needed
            var connected = false
            val protocols = listOf(
                "ATSP0",  // Auto
                "ATSP6",  // ISO 15765-4 CAN (11 bit, 500 kbaud)
                "ATSP5"   // ISO 15765-4 CAN (11 bit, 250 kbaud)
            )

            for (protocol in protocols) {
                Log.d(TAG, "Trying protocol: $protocol")
                sendCommand(protocol)
                delay(1000)

                // Check ECU connectivity
                val ecuResp = sendCommand("0100")
                Log.d(TAG, "ECU check with $protocol -> $ecuResp")

                if (ecuResp.contains("41 00", ignoreCase = true) ||
                    ecuResp.contains("4100", ignoreCase = true)) {
                    Log.i(TAG, "Successfully connected with protocol: $protocol")
                    connected = true
                    break
                } else {
                    Log.w(TAG, "Protocol $protocol failed, trying next...")
                    delay(500)
                }
            }

            if (!connected) {
                Log.e(TAG, "Failed to connect with any protocol")
                return false
            }

            isInitialized = true
            return true

        } catch (e: Exception) {
            Log.e(TAG, "setupELM error: ${e.message}", e)
            return false
        }
    }

    /**
     * Sends an AT or OBD command, waits for '>' prompt, returns raw response.
     */
    private fun sendCommand(cmd: String, maxRetries: Int = 2): String {
        var retries = 0
        var lastError: Exception? = null

        while (retries <= maxRetries) {
            try {
                clearBuffer()

                // Don't send empty command - just read pending data
                if (cmd.isNotEmpty()) {
                    val full = (cmd + "\r").toByteArray()
                    output.write(full)
                    output.flush()
                    Log.d(TAG, "Sent command: '$cmd'")
                } else {
                    Log.d(TAG, "Empty command - just reading buffer")
                }

                val resp = readResponseWithTimeout(5000)
                Log.d(TAG, "Command '$cmd' | Raw resp: '${resp.visualize()}'")

                // Check for typical errors
                if (resp.contains("?", ignoreCase = true) &&
                    !resp.contains("OK", ignoreCase = true) &&
                    !resp.contains("41", ignoreCase = true)) {
                    Log.w(TAG, "Command '$cmd' returned error: $resp")
                    retries++
                    continue
                }

                return resp
            } catch (e: IOException) {
                lastError = e
                Log.e(TAG, "sendCommand retry ${retries+1}/${maxRetries+1} error: ${e.message}")
                retries++
            }
        }

        Log.e(TAG, "Command '$cmd' failed after $maxRetries retries: ${lastError?.message}")
        return ""
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
        var lastRead = start
        var promptFound = false

        try {
            while (System.currentTimeMillis() - start < timeoutMs && !promptFound) {
                if (input.available() > 0) {
                    val data = ByteArray(256)
                    val len = input.read(data)

                    if (len > 0) {
                        val chunk = String(data, 0, len)
                        buf.append(chunk)
                        lastRead = System.currentTimeMillis()

                        // Stop if we find the prompt character
                        if (chunk.contains('>')) {
                            promptFound = true
                            break
                        }
                    }
                } else {
                    // Check for inactivity timeout (no data for 1.5 seconds)
                    if (System.currentTimeMillis() - lastRead > 1500) {
                        Log.w(TAG, "No data received for 1.5s, breaking read loop")
                        break
                    }
                    Thread.sleep(50)
                }
            }

            if (!promptFound && System.currentTimeMillis() - start >= timeoutMs) {
                Log.w(TAG, "readResponse timed out after ${timeoutMs}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "readResponse error: ${e.message}")
        }

        return buf.toString().replace('>', ' ').trim()
    }

    // Parser for PID
    private fun parseOBDResponse(normalized: String, pid: String): List<String> {
        // Try standard format first (41 0C XX XX)
        val standardRegex = Regex("41 $pid ([0-9A-F]{2})(?: ([0-9A-F]{2}))?")
        val match = standardRegex.find(normalized)

        if (match != null) {
            return match.groupValues.drop(1).filter { it.isNotEmpty() }
        }

        // Try alternate format (410C XXXX)
        val noSpaceRegex = Regex("41${pid}((?:[0-9A-F]{2}){1,2})")
        val noSpaceMatch = noSpaceRegex.find(normalized)

        if (noSpaceMatch != null) {
            val hexString = noSpaceMatch.groupValues[1]
            return if (hexString.length >= 2) {
                listOf(
                    hexString.substring(0, 2),
                    if (hexString.length >= 4) hexString.substring(2, 4) else ""
                ).filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
        }

        // If neither format matches, just try to find pairs of hex digits after "41"
        if (normalized.contains("41", ignoreCase = true)) {
            val startIdx = normalized.indexOf("41", ignoreCase = true) + 2
            if (startIdx < normalized.length) {
                val remainder = normalized.substring(startIdx).replace(" ", "")
                // Skip the PID digits and get the next hex pairs
                val dataStart = if (remainder.startsWith(pid, ignoreCase = true))
                    pid.length else 0

                if (dataStart + 2 <= remainder.length) {
                    val result = mutableListOf<String>()
                    result.add(remainder.substring(dataStart, dataStart + 2))
                    if (dataStart + 4 <= remainder.length) {
                        result.add(remainder.substring(dataStart + 2, dataStart + 4))
                    }
                    return result
                }
            }
        }

        return emptyList()
    }

    /**
     * Reads a PID and returns its integer value.
     */
    suspend fun readPID(pidHex: Int): Int = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.e(TAG, "readPID called before init")
            return@withContext -1
        }

        val pid = String.format(Locale.US, "%02X", pidHex)
        val cmd = "01$pid"
        val resp = sendCommand(cmd)
        Log.d(TAG, "PID $pid raw response: '${resp.visualize()}'")

        if (resp.isBlank() || resp.contains("NO DATA", ignoreCase = true)) {
            Log.w(TAG, "No data received for PID $pid")
            return@withContext -1
        }

        // Normalize response
        val normalized = resp.uppercase(Locale.US).replace(Regex("\\s+"), " ")
        Log.d(TAG, "PID $pid normalized: '$normalized'")

        // Use the more flexible parser
        val bytes = parseOBDResponse(normalized, pid)

        if (bytes.isNotEmpty()) {
            Log.d(TAG, "PID $pid parser matched, bytes: $bytes")

            try {
                return@withContext when (pidHex) {
                    0x0C -> { // RPM requires two bytes
                        if (bytes.size < 2) {
                            Log.e(TAG, "Not enough bytes for RPM calculation")
                            return@withContext -1
                        }
                        val A = bytes[0].toInt(16)
                        val B = bytes[1].toInt(16)
                        val rpm = (A * 256 + B) / 4
                        Log.d(TAG, "RPM calculated: $rpm (A=$A, B=$B)")
                        rpm
                    }
                    0x0D -> { // Speed one byte
                        bytes[0].toInt(16)
                    }
                    else -> bytes[0].toInt(16)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Parsing error for PID $pid: ${e.message}", e)
                return@withContext -1
            }
        } else {
            Log.e(TAG, "Parser did not match for PID $pid in: '$normalized'")
            return@withContext -1
        }
    }

    // Extension for easy visualization of control chars
    private fun String.visualize() = this
        .replace("\r", "\\r")
        .replace("\n", "\\n")

    // Helper to quickly test basic match before regex
    private fun String.matchRegex(pattern: String) =
        Regex(pattern.replace(" ", "\\s*")).containsMatchIn(this)
}
