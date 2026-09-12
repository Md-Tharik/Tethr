package com.example.tethr.update

import android.app.Activity
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppUpdateHelper(private val activity: Activity) {

    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)
    private val UPDATE_REQUEST_CODE = 4321

    private val _updateDownloaded = MutableStateFlow(false)
    val updateDownloaded: StateFlow<Boolean> = _updateDownloaded

    private val installStateUpdatedListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            _updateDownloaded.value = true
        }
    }

    /**
     * Checks for updates and starts the update flow if available.
     * It automatically selects IMMEDIATE (blocking) for high priority updates (>= 4) 
     * or if the update has been available for 5 or more days. Otherwise, FLEXIBLE.
     */
    fun checkAndStartUpdate() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                
                val priority = appUpdateInfo.updatePriority() // 0-5 from Google Play Developer API
                val stalenessDays = appUpdateInfo.clientVersionStalenessDays() ?: 0

                // Decide update type: IMMEDIATE if high priority OR available for >= 5 days
                val updateType = if (priority >= 4 || stalenessDays >= 5) {
                    AppUpdateType.IMMEDIATE
                } else {
                    AppUpdateType.FLEXIBLE
                }
                
                if (appUpdateInfo.isUpdateTypeAllowed(updateType)) {
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        activity,
                        AppUpdateOptions.newBuilder(updateType).build(),
                        UPDATE_REQUEST_CODE
                    )
                }
            }
        }.addOnFailureListener {
            Log.e("AppUpdateHelper", "In-App Update check failed", it)
        }
    }

    /**
     * Call this in the Activity's onResume() to resume any interrupted update.
     */
    fun onResume() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            // For FLEXIBLE updates: If the update is downloaded but not installed, notify the user.
            if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                _updateDownloaded.value = true
            }

            // For IMMEDIATE updates: If the update is already in progress, resume it.
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    activity,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                    UPDATE_REQUEST_CODE
                )
            }
        }.addOnFailureListener {
            Log.e("AppUpdateHelper", "Failed to get update info on resume", it)
        }
    }

    /**
     * Call this in the Activity's onCreate() to register the state listener.
     */
    fun registerListener() {
        appUpdateManager.registerListener(installStateUpdatedListener)
    }

    /**
     * Call this in the Activity's onDestroy() to unregister the state listener to avoid memory leaks.
     */
    fun unregisterListener() {
        appUpdateManager.unregisterListener(installStateUpdatedListener)
    }

    /**
     * Call this when the user clicks 'RESTART' to complete the flexible update.
     */
    fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }
}
