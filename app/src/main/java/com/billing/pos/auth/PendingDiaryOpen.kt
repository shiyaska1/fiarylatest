package com.billing.pos.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A diary entry id captured from a reminder notification, to open in edit mode after login. */
object PendingDiaryOpen {
    var id by mutableStateOf<Long?>(null)

    fun consume(): Long? {
        val v = id
        id = null
        return v
    }
}
