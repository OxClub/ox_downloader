package com.oxclub.universaldownloader.utils

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Formatters {
    fun size(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = -1
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024
            i++
        }
        return String.format(Locale.US, "%.1f %s", value, units[i])
    }

    fun speed(bytesPerSec: Double): String {
        if (bytesPerSec < 1) return "0 KB/s"
        return size(bytesPerSec.toLong()) + "/s"
    }

    /** Seconds remaining, or null when the speed is too low/unstable to estimate. */
    fun etaSeconds(remainingBytes: Long, bytesPerSec: Double): Long? {
        if (remainingBytes < 0 || bytesPerSec < 512.0) return null
        return (remainingBytes / bytesPerSec).toLong()
    }

    fun duration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, sec)
            else -> "${sec}s"
        }
    }

    fun percent(bytes: Long, total: Long): Int =
        if (total <= 0) 0 else ((bytes * 100) / total).toInt().coerceIn(0, 100)

    fun dateTime(millis: Long?): String =
        if (millis == null) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}
