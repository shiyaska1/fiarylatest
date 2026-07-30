package com.billing.pos.auth

import android.net.Uri

/** Holds a backup Uri received via a share/open intent until it can be imported. */
object PendingImport {
    @Volatile var uri: Uri? = null

    fun consume(): Uri? {
        val u = uri
        uri = null
        return u
    }
}
