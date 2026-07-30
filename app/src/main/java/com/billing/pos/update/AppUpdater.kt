package com.billing.pos.update

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Prompts for a Play Store update as soon as the app opens.
 *
 * IMMEDIATE flow: Play shows a full-screen, blocking update screen and installs it, so a
 * customer cannot keep running an old build once a new one is published. This only does
 * anything for copies installed from Play — a sideloaded APK is silently left alone, which
 * is why every call is wrapped: on those devices the Play API simply fails.
 */
object AppUpdater {

    private const val REQUEST_CODE = 4712

    fun check(activity: Activity) {
        runCatching {
            val manager = AppUpdateManagerFactory.create(activity)
            manager.appUpdateInfo
                .addOnSuccessListener { info ->
                    val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    val allowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    if (available && allowed) {
                        runCatching {
                            manager.startUpdateFlowForResult(info, AppUpdateType.IMMEDIATE, activity, REQUEST_CODE)
                        }
                    }
                }
                .addOnFailureListener { /* not installed from Play, or offline — ignore */ }
        }
    }

    /**
     * Resumes an update that was already downloading when the app was closed, so a
     * half-finished update does not leave the app stuck on the old version.
     */
    fun resumeIfInterrupted(activity: Activity) {
        runCatching {
            val manager = AppUpdateManagerFactory.create(activity)
            manager.appUpdateInfo.addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    runCatching {
                        manager.startUpdateFlowForResult(info, AppUpdateType.IMMEDIATE, activity, REQUEST_CODE)
                    }
                }
            }
        }
    }
}
