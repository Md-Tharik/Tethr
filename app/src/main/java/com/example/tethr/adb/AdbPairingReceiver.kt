package com.example.tethr.adb

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdbPairingReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ADB_PAIR = "com.example.tethr.ACTION_ADB_PAIR"
        const val EXTRA_CONN_PORT = "EXTRA_CONN_PORT"
        const val REMOTE_INPUT_KEY = "KEY_PAIR_INPUT"
        const val NOTIFICATION_ID = 1001
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADB_PAIR) return

        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val userInput = remoteInput?.getCharSequence(REMOTE_INPUT_KEY)?.toString()?.trim()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (userInput.isNullOrEmpty()) {
            updateNotification(context, notificationManager, "Failed: Input was empty.")
            return
        }

        // The user input should now JUST be the 6-digit code
        val pairCode = userInput

        // Get the dynamically discovered ports
        val connPort = AdbDiscoveryManager.discoveredConnPort
        val pairPort = AdbDiscoveryManager.discoveredPairPort

        if (connPort == null || pairPort == null) {
            updateNotification(context, notificationManager, "Failed: Ports not discovered yet. Ensure Wireless Debugging is ON.")
            return
        }

        updateNotification(context, notificationManager, "Pairing with port $pairPort... Please wait.", true)

        // Launch coroutine to do ADB pairing
        CoroutineScope(Dispatchers.IO).launch {
            val result = AdbManager.pairAndConnect(context, connPort, pairPort, pairCode)

            withContext(Dispatchers.Main) {
                when (result) {
                    is AdbManager.AdbResult.Success -> {
                        updateNotification(context, notificationManager, "Success! Hardware Grayscale permissions granted.")
                        Toast.makeText(context, "Tethr activated successfully!", Toast.LENGTH_LONG).show()
                    }
                    is AdbManager.AdbResult.Error -> {
                        updateNotification(context, notificationManager, "Error: ${result.message}")
                    }
                }
            }
        }
    }

    private fun updateNotification(context: Context, manager: NotificationManager, text: String, ongoing: Boolean = false) {
        val builder = NotificationCompat.Builder(context, "tethr_setup_v2")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Make sure to use a valid icon, R.drawable.ic_dialog_info exists natively
            .setContentTitle("Tethr Activation")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
