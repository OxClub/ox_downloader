package com.oxclub.universaldownloader.downloader

/** Moving-window speed estimate, smoothed so the UI and ETA do not jump around. */
class SpeedMeter(private val windowMs: Long = 4000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var smoothed = 0.0

    fun sample(timeMs: Long, totalBytes: Long): Double {
        samples.addLast(timeMs to totalBytes)
        while (samples.size > 2 && timeMs - samples.first().first > windowMs) samples.removeFirst()
        val first = samples.first()
        val dt = timeMs - first.first
        if (dt <= 0) return smoothed
        val instantaneous = ((totalBytes - first.second).coerceAtLeast(0)) * 1000.0 / dt
        smoothed = if (smoothed == 0.0) instantaneous else 0.7 * smoothed + 0.3 * instantaneous
        return smoothed
    }
}
