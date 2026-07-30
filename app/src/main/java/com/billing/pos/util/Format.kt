package com.billing.pos.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    private val dateFmt = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    private val dateTimeFmt = SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.getDefault())

    fun money(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)

    /** With rupee symbol, for UI. */
    fun rupee(value: Double): String = "₹" + money(value)

    fun date(millis: Long): String = dateFmt.format(Date(millis))
    fun dateTime(millis: Long): String = dateTimeFmt.format(Date(millis))

    fun qty(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.getDefault(), "%.2f", value)
}
