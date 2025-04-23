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

    suspend fun setupELM(input: InputStream, output: OutputStream): Boolean {
        this.input = input
        this.output = output

        try {
            // Give adapter time to be ready
            delay(1000)

            // Reset and wait
            if (!sendCommand("ATZ").contains("ELM")) {
                Log.e(TAG, "Failed to reset ELM327")
                return false
            }
            delay(1000) // Important delay after reset

            // Configuration with delay between commands
            val commands = listOf(
                "ATD",     // Set defaults
                "ATE0",    // Echo off
                "ATL0",    // Linefeeds off
                "ATS0",    // Spaces off
                "ATH0",    // Headers off
                "ATSP0",   // Auto protocol
                "0100",    // Check if OBD is ready
                "ATAT1",   // Adaptive timing on
                "ATST64"   // Set timeout to 200ms
            )

            for (cmd in commands) {
                val response = sendCommand(cmd)
                Log.d(TAG, "Init cmd: $cmd, response: $response")
                delay(300) // Give adapter time to process

                // Check if we got a connection to the vehicle ECU
                if (cmd == "0100" && !response.contains("41 00")) {
                    Log.e(TAG, "Failed to connect to vehicle ECU")
                    return false
                }
            }

            isInitialized = true
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error in setupELM: ${e.message}")
            return false
        }
    }

    private fun sendCommand(cmd: String): String {
        val full = (cmd + "\r").toByteArray()
        try {
            clearBuffer()
            output.write(full)
            output.flush()

            val response = readResponseWithTimeout()
            Log.d(TAG, "Sent: $cmd | Response: $response")
            return response
        } catch (e: IOException) {
            Log.e(TAG, "Error sending command: ${e.message}")
            return "ERROR"
        }
    }

    private fun clearBuffer() {
        try {
            // Safety check
            if (!::input.isInitialized) return

            // Read all available bytes
            while (input.available() > 0) {
                input.read()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error clearing buffer: ${e.message}")
        }
    }

    private fun readResponseWithTimeout(): String {
        // Wait up to 5 seconds for a response
        val startTime = System.currentTimeMillis()
        val timeout = 5000L
        val buffer = StringBuilder()

        try {
            while (System.currentTimeMillis() - startTime < timeout) {
                // See if we have data
                if (input.available() > 0) {
                    // Read data
                    val data = ByteArray(128)
                    val bytes = input.read(data)
                    buffer.append(String(data, 0, bytes))

                    // If we have a complete response, we're done
                    // ELM327 responses end with > prompt character
                    if (buffer.toString().contains(">")) {
                        break
                    }
                }

                // Small sleep to prevent CPU hogging
                Thread.sleep(100)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading response: ${e.message}")
        }

        val response = buffer.toString().replace(">", "").trim()
        return response
    }

    suspend fun readPID(pidHex: Int): Int = withContext(Dispatchers.IO) {
        // Check if ELM has been properly initialized
        if (!isInitialized) {
            Log.e(TAG, "ELM not initialized before reading PID")
            return@withContext -1
        }

        val pid = String.format(Locale.US, "%02X", pidHex)
        val command = "01$pid"
        val response = sendCommand(command)

        if (response.contains("NO DATA") || response.isEmpty() || response == "ERROR") {
            Log.e(TAG, "No data received for PID $pid: $response")
            return@withContext -1
        }

        try {
            // Clean up the response - remove any spaces and split by line breaks
            val lines = response.trim().split("\r", "\n").filter { it.isNotEmpty() }

            // Find the line containing the response (often the last line)
            var responseLine = ""
            for (line in lines) {
                if (line.contains("41") && line.contains(pid)) {
                    responseLine = line
                    break
                }
            }

            if (responseLine.isEmpty()) {
                Log.e(TAG, "Cannot find valid response line for PID $pid in: $response")
                return@withContext -1
            }

            // Split the response into parts, filtering out empty strings
            val parts = responseLine.split(" ").filter { it.isNotEmpty() }
            Log.d(TAG, "Response parts for PID $pid: $parts")

            // Check for minimum valid response format
            if (parts.size < 3) {
                Log.e(TAG, "Invalid response format for PID $pid: $responseLine")
                return@withContext -1
            }

            // Look for the "41 XX" pattern which indicates a successful response
            var dataIndex = -1
            for (i in 0 until parts.size - 1) {
                if (parts[i] == "41" && parts[i+1] == pid) {
                    dataIndex = i + 2
                    break
                }
            }

            if (dataIndex == -1 || dataIndex >= parts.size) {
                Log.e(TAG, "Cannot find data bytes in response for PID $pid: $responseLine")
                return@withContext -1
            }

            // Calculate value based on PID
            return@withContext when (pidHex) {
                0x0C -> { // RPM
                    if (dataIndex + 1 >= parts.size) {
                        Log.e(TAG, "Not enough data bytes for RPM in response: $responseLine")
                        -1
                    } else {
                        val A = parts[dataIndex].toIntOrNull(16) ?: 0
                        val B = parts[dataIndex + 1].toIntOrNull(16) ?: 0
                        Log.d(TAG, "RPM calculation: A=$A, B=$B, result=${(A * 256 + B) / 4}")
                        (A * 256 + B) / 4
                    }
                }
                0x0D -> { // Speed (km/h)
                    parts[dataIndex].toIntOrNull(16) ?: -1
                }
                else -> parts[dataIndex].toIntOrNull(16) ?: -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response for PID $pid: ${e.message}")
            -1
        }
    }
}