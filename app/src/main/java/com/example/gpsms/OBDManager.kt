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

    private var isInitialized = false
    private var adapterType = "Unknown" // To track adapter type

    suspend fun setupELM(input: InputStream, output: OutputStream): Boolean {
        this.input = input
        this.output = output

        try {
            // Important: Give hardware time to stabilize after BT connection
            delay(2000)
            clearBuffer()

            // Try with a more lenient approach - just send reset and check for ANY response
            Log.d(TAG, "Sending initial reset command")
            val resetResponse = sendCommandWithRetry("ATZ", 3)

            // If we get any response, we likely have communication with the adapter
            if (resetResponse.isEmpty()) {
                Log.e(TAG, "No response from adapter after reset")
                return false
            }

            // Store adapter info for debugging
            adapterType = resetResponse
            Log.d(TAG, "Adapter identified as: $adapterType")

            // More gentle delay after reset
            delay(2000)
            clearBuffer()

            // Try different initialization sequences based on common adapter types
            if (tryStandardInitialization() || tryAlternativeInitialization()) {
                isInitialized = true
                return true
            }

            Log.e(TAG, "All initialization attempts failed")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "Exception during setupELM: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    private suspend fun tryStandardInitialization(): Boolean {
        Log.d(TAG, "Attempting standard initialization sequence")

        val commands = listOf(
            "ATE0",    // Echo off
            "ATL0",    // Linefeeds off
            "ATH0",    // Headers off
            "ATS0",    // Spaces off
            "ATSP0"    // Auto protocol
        )

        for (cmd in commands) {
            val response = sendCommandWithRetry(cmd, 2)
            Log.d(TAG, "Standard init cmd: $cmd, response: $response")
            delay(300)

            // Most commands should return "OK" if successful
            if (!response.contains("OK") && !response.contains("ELM") && response != "?") {
                Log.w(TAG, "Command $cmd did not return expected response")
                // Continue anyway - some adapters don't strictly follow protocol
            }
        }

        // Try a simple test command to see if we can talk to the car
        val testResponse = sendCommandWithRetry("0100", 3) // Request supported PIDs 01-20
        Log.d(TAG, "Test command response: $testResponse")

        // Check if we got any reasonable response (41 = response to command 01)
        return testResponse.contains("41") || testResponse.contains("SEARCHING")
    }

    private suspend fun tryAlternativeInitialization(): Boolean {
        Log.d(TAG, "Attempting alternative initialization sequence")

        // Reset again before trying alternative approach
        sendCommandWithRetry("ATZ", 2)
        delay(1000)
        clearBuffer()

        val altCommands = listOf(
            "ATD",     // Set all to defaults
            "ATE0",    // Echo off
            "ATPP 0C SV FF", // Hot start
            "ATPP 0D SV FF", // Enable all protocols
            "ATM0",    // Memory off
            "ATSP6",   // Try CAN protocol specifically (common for newer cars)
            "ATCAF0",  // CAN Auto Format off
            "ATAT1",   // Allow adaptive timing
            "ATST64"   // Timeout 400ms (hex 64)
        )

        for (cmd in altCommands) {
            val response = sendCommandWithRetry(cmd, 2)
            Log.d(TAG, "Alt init cmd: $cmd, response: $response")
            delay(300)
        }

        // Try a different test command
        val testResponse = sendCommandWithRetry("01 00", 3)
        Log.d(TAG, "Alt test command response: $testResponse")

        // If that didn't work, try one more approach with slower timing
        if (!testResponse.contains("41") && !testResponse.contains("SEARCHING")) {
            Log.d(TAG, "Trying slow timing approach")
            sendCommandWithRetry("ATST C8", 1) // Much longer timeout (200ms × 8)
            sendCommandWithRetry("ATAT2", 1)   // Adaptive timing more aggressive

            val finalTest = sendCommandWithRetry("0100", 3)
            Log.d(TAG, "Final test response: $finalTest")
            return finalTest.contains("41") || finalTest.contains("SEARCHING")
        }

        return testResponse.contains("41") || testResponse.contains("SEARCHING")
    }

    private suspend fun sendCommandWithRetry(cmd: String, retries: Int): String {
        var attempts = 0
        var response = ""

        while (attempts < retries) {
            response = sendCommand(cmd)

            // If we got any meaningful response, return it
            if (response.isNotEmpty() && response != "?" && !response.contains("ERROR")) {
                return response
            }

            Log.d(TAG, "Retry $attempts for command $cmd")
            attempts++
            delay(300)
        }

        return response
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
            if (!::input.isInitialized) return

            var count = 0
            while (input.available() > 0 && count < 1000) { // Safety limit
                input.read()
                count++
            }
            if (count > 0) {
                Log.d(TAG, "Cleared $count bytes from buffer")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error clearing buffer: ${e.message}")
        }
    }

    private fun readResponseWithTimeout(): String {
        val startTime = System.currentTimeMillis()
        val timeout = 3000L // 3 seconds max wait
        val buffer = StringBuilder()
        var lastReadTime = startTime

        try {
            while (System.currentTimeMillis() - startTime < timeout) {
                if (input.available() > 0) {
                    val data = ByteArray(128)
                    val bytes = input.read(data)
                    if (bytes > 0) {
                        buffer.append(String(data, 0, bytes))
                        lastReadTime = System.currentTimeMillis()
                    }

                    // Check for response termination
                    val currentResponse = buffer.toString()
                    if (currentResponse.contains(">") ||
                        currentResponse.contains("?") ||
                        (currentResponse.contains("OK") && !currentResponse.contains("OK>") && !currentResponse.contains("ELM"))) {
                        break
                    }
                }

                // If no new data for 500ms, we're likely done
                if (System.currentTimeMillis() - lastReadTime > 500 && buffer.isNotEmpty()) {
                    break
                }

                Thread.sleep(50)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading response: ${e.message}")
        }

        val response = buffer.toString().replace(">", "").trim()
        return response
    }

    suspend fun readPID(pidHex: Int): Int = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.e(TAG, "ELM not initialized before reading PID")
            return@withContext -1
        }

        val pid = String.format(Locale.US, "%02X", pidHex)
        val command = "01$pid"
        val response = sendCommandWithRetry(command, 2)

        if (response.contains("NO DATA") || response.isEmpty() || response == "ERROR" || response == "?") {
            Log.e(TAG, "No data received for PID $pid: $response")
            return@withContext -1
        }

        try {
            Log.d(TAG, "Processing response for PID $pid: $response")
            // Clean and split the response
            val cleanResponse = response.replace("\r", " ").replace("\n", " ")
                .replace(">", " ").replace("41", " 41")

            val parts = cleanResponse.split(" ").filter { it.isNotEmpty() }
            Log.d(TAG, "Parsed parts: $parts")

            // Look for the 41 XX pattern (41 = response code, XX = PID)
            var dataIndex = -1
            for (i in 0 until parts.size - 1) {
                if (parts[i] == "41" && parts[i+1].uppercase() == pid) {
                    dataIndex = i + 2
                    break
                }
            }

            if (dataIndex == -1 || dataIndex >= parts.size) {
                Log.e(TAG, "Cannot find data bytes in response for PID $pid")
                return@withContext -1
            }

            when (pidHex) {
                0x0C -> { // RPM calculation: ((A * 256) + B) / 4
                    if (dataIndex + 1 >= parts.size) {
                        Log.e(TAG, "Not enough data bytes for RPM")
                        return@withContext -1
                    }

                    val A = parts[dataIndex].toIntOrNull(16) ?: 0
                    val B = parts[dataIndex + 1].toIntOrNull(16) ?: 0
                    val rpm = (A * 256 + B) / 4
                    Log.d(TAG, "Calculated RPM: $rpm (A=$A, B=$B)")
                    rpm
                }
                else -> {
                    parts[dataIndex].toIntOrNull(16) ?: -1
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response: ${e.message}")
            -1
        }
    }
}