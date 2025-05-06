package com.example.gpsms

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

class OBDManager {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val TAG = "OBDManager"
    private var isInitialized = false

    /**
     * Sets up the ELM327 adapter with AT commands and protocol negotiation.
     */
    suspend fun setupELM(input: InputStream, output: OutputStream): Boolean {
        this.input = input
        this.output = output

        return withContext(Dispatchers.IO) {
            try {
                delay(3000)  // allow startup

                // Reset
                var resp = sendCommand("ATZ")
                Log.d(TAG, "ATZ resp: '$resp'")
                if (!resp.contains("ELM", ignoreCase = true)) {
                    Log.w(TAG, "Missing ELM banner, retrying ATZ")
                    resp = sendCommand("ATZ")
                    Log.d(TAG, "ATZ retry resp: '$resp'")
                    if (!resp.contains("ELM", ignoreCase = true)) {
                        Log.e(TAG, "ELM reset failed: '$resp'")
                        return@withContext false
                    }
                }
                delay(2000)

                // Basic config
                listOf("ATE0","ATL0","ATS0","ATH0","ATAT1","ATST64").forEach { cmd ->
                    val r = sendCommand(cmd)
                    Log.d(TAG, "$cmd -> '$r'")
                    delay(300)
                }

                // Protocols
                val protocols = listOf("ATSP0","ATSP6","ATSP5")
                var connected = false
                for (p in protocols) {
                    Log.d(TAG, "Trying $p")
                    sendCommand(p); delay(1000)
                    val check = sendCommand("0100")
                    Log.d(TAG, "0100 resp: '$check'")
                    if (check.contains("41 00", ignoreCase = true) ||
                        check.contains("4100", ignoreCase = true)) {
                        Log.i(TAG, "Protocol $p success")
                        connected = true
                        break
                    }
                }
                if (!connected) {
                    Log.e(TAG, "No protocol succeeded")
                    return@withContext false
                }

                isInitialized = true
                true
            } catch (e: Exception) {
                Log.e(TAG, "setupELM error: ${e.message}", e)
                false
            }
        }
    }

    private fun sendCommand(cmd: String, maxRetries: Int = 2): String {
        var retries = 0
        var lastErr: IOException? = null
        while (retries <= maxRetries) {
            try {
                clearBuffer()
                if (cmd.isNotEmpty()) {
                    output.write((cmd + "\r").toByteArray())
                    output.flush()
                }
                val resp = readResponseWithTimeout(5000)
                Log.d(TAG, "Cmd '$cmd' raw: '${resp.visualize()}'")
                if (resp.contains("?", ignoreCase = true)
                    && !resp.contains("OK", ignoreCase = true)
                    && !resp.contains("41", ignoreCase = true)
                ) {
                    retries++; continue
                }
                return resp
            } catch (e: IOException) {
                lastErr = e
                retries++
            }
        }
        Log.e(TAG, "Cmd '$cmd' failed: ${lastErr?.message}")
        return ""
    }

    private fun clearBuffer() {
        try {
            while (::input.isInitialized && input.available() > 0) {
                input.read()
            }
        } catch (e: IOException) {
            Log.e(TAG, "clearBuffer error: ${e.message}")
        }
    }

    private fun readResponseWithTimeout(timeoutMs: Long): String {
        val start = System.currentTimeMillis()
        val buf = StringBuilder()
        var lastRead = start
        var prompt = false
        try {
            while (System.currentTimeMillis() - start < timeoutMs && !prompt) {
                if (input.available() > 0) {
                    val data = ByteArray(256)
                    val len = input.read(data)
                    if (len > 0) {
                        val s = String(data, 0, len)
                        buf.append(s)
                        lastRead = System.currentTimeMillis()
                        if (s.contains('>')) { prompt = true; break }
                    }
                } else {
                    if (System.currentTimeMillis() - lastRead > 1500) break
                    Thread.sleep(50)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "read error: ${e.message}")
        }
        return buf.toString().replace('>', ' ').trim()
    }

    suspend fun readPID(pidHex: Int): Int = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.e(TAG, "readPID before init")
            return@withContext -1
        }
        val pid = String.format(Locale.US, "%02X", pidHex)
        val resp = sendCommand("01$pid")
        if (resp.isBlank() || resp.contains("NO DATA", ignoreCase = true)) return@withContext -1

        val norm = resp.uppercase(Locale.US).replace(Regex("\\s+"), " ")
        val bytes = parseOBDResponse(norm, pid)
        if (bytes.isEmpty()) return@withContext -1

        return@withContext try {
            when (pidHex) {
                0x0C -> {
                    val a = bytes[0].toInt(16)
                    val b = bytes[1].toInt(16)
                    (a * 256 + b) / 4
                }
                0x0D -> bytes[0].toInt(16)
                else -> bytes[0].toInt(16)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parsing error: ${e.message}")
            -1
        }
    }

    private fun parseOBDResponse(normalized: String, pid: String): List<String> {
        val std = Regex("41 $pid ([0-9A-F]{2})(?: ([0-9A-F]{2}))?")
        std.find(normalized)?.let { m ->
            return m.groupValues.drop(1).filter { it.isNotEmpty() }
        }
        val noSpace = Regex("41${pid}((?:[0-9A-F]{2}){1,2})")
        noSpace.find(normalized)?.let { m ->
            val hex = m.groupValues[1]
            return listOf(hex.substring(0,2))
                .plus(if (hex.length>=4) hex.substring(2,4) else "")
                .filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    private fun String.visualize() = replace("\r","\\r").replace("\n","\\n")
}
