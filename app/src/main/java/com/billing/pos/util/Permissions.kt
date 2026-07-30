package com.billing.pos.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object Permissions {
    /** Opens this app's system settings page so the user can grant a denied permission. */
    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
