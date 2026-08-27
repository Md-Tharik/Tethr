package com.example.tethr.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * AdbManager
 * Handles local Wireless Debugging (Android 11+) using a bundled native ADB binary.
 */
object AdbManager {
    private const val TAG = "AdbManager"

    sealed class AdbResult {
        object Success : AdbResult()
        data class Error(val message: String) : AdbResult()
    }

    suspend fun pairAndConnect(context: Context, connPort: Int, pairPort: Int, pairingCode: String): AdbResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting native ADB pairing logic...")
            
            // 1. Locate the bundled libadb.so which Android automatically extracts to nativeLibraryDir
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val adbPath = "$nativeLibDir/libadb.so"
            val adbFile = File(adbPath)
            
            if (!adbFile.exists()) {
                Log.e(TAG, "ADB binary not found at $adbPath")
                return@withContext AdbResult.Error("ADB binary missing. Are you running on ARM64?")
            }
            
            if (!adbFile.canExecute()) {
                // Should be executable by default in nativeLibraryDir, but just in case
                adbFile.setExecutable(true)
            }

            // 2. Set up the environment for ADB. 
            // Crucial: ADB needs a writable HOME directory to generate and store adbkey RSA pairs.
            val envp = arrayOf(
                "HOME=${context.filesDir.absolutePath}",
                "TMPDIR=${context.cacheDir.absolutePath}"
            )

            // 3. Execute Pairing
            val pairCmd = arrayOf(adbPath, "pair", "localhost:$pairPort", pairingCode)
            Log.d(TAG, "Running: ${pairCmd.joinToString(" ")}")
            val pairProcess = Runtime.getRuntime().exec(pairCmd, envp)
            val pairResult = pairProcess.waitFor()
            
            val pairOutput = readOutput(pairProcess)
            Log.d(TAG, "Pairing Output: $pairOutput")
            
            if (pairResult != 0 || pairOutput.contains("Failed", ignoreCase = true)) {
                return@withContext AdbResult.Error("Pairing failed: $pairOutput")
            }

            // 4. Execute Connection
            val connectCmd = arrayOf(adbPath, "connect", "localhost:$connPort")
            Log.d(TAG, "Running: ${connectCmd.joinToString(" ")}")
            val connectProcess = Runtime.getRuntime().exec(connectCmd, envp)
            val connectResult = connectProcess.waitFor()
            
            val connectOutput = readOutput(connectProcess)
            Log.d(TAG, "Connect Output: $connectOutput")
            
            if (connectResult != 0 || connectOutput.contains("failed", ignoreCase = true) || connectOutput.contains("cannot connect", ignoreCase = true)) {
                return@withContext AdbResult.Error("Connection failed: $connectOutput")
            }

            // 5. Execute pm grant command
            val grantCmd = arrayOf(adbPath, "-s", "localhost:$connPort", "shell", "pm", "grant", context.packageName, "android.permission.WRITE_SECURE_SETTINGS")
            Log.d(TAG, "Running: ${grantCmd.joinToString(" ")}")
            val grantProcess = Runtime.getRuntime().exec(grantCmd, envp)
            val grantResult = grantProcess.waitFor()
            
            val grantOutput = readOutput(grantProcess)
            Log.d(TAG, "Grant Output: $grantOutput")
            
            if (grantResult != 0) {
                return@withContext AdbResult.Error("Permission grant failed: $grantOutput")
            }
            
            // Clean up: Disconnect to free up ports and resources
            Runtime.getRuntime().exec(arrayOf(adbPath, "disconnect", "localhost:$connPort"), envp)

            Log.d(TAG, "Successfully paired and granted permissions!")
            return@withContext AdbResult.Success
            
        } catch (e: Exception) {
            Log.e(TAG, "ADB execution failed", e)
            return@withContext AdbResult.Error(e.message ?: "Unknown error occurred")
        }
    }
    
    private fun readOutput(process: Process): String {
        val stdOut = BufferedReader(InputStreamReader(process.inputStream)).readText()
        val stdErr = BufferedReader(InputStreamReader(process.errorStream)).readText()
        return (stdOut + "\n" + stdErr).trim()
    }
}
