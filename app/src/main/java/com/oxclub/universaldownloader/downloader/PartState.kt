package com.oxclub.universaldownloader.downloader

import java.util.concurrent.atomic.AtomicLong

/** One byte range of the file. [end] is inclusive, or -1 when the total size is unknown. */
class PartState(val index: Int, val start: Long, val end: Long, initialDone: Long = 0) {
    val done = AtomicLong(initialDone)
    val length: Long get() = if (end >= 0) end - start + 1 else -1
    val isComplete: Boolean get() = end >= 0 && done.get() >= length
}

object PartCodec {
    fun encode(parts: List<PartState>): String =
        parts.joinToString("|") { "${it.index}:${it.start}:${it.end}:${it.done.get()}" }

    fun decode(text: String?): List<PartState>? {
        if (text.isNullOrBlank()) return null
        return try {
            text.split('|').map { chunk ->
                val f = chunk.split(':')
                if (f.size != 4) return null
                PartState(f[0].toInt(), f[1].toLong(), f[2].toLong(), f[3].toLong())
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}

object PartPlanner {
    const val MIN_PART_BYTES = 1L shl 20

    fun plan(totalBytes: Long, supportsRanges: Boolean, desiredParts: Int): List<PartState> {
        if (totalBytes <= 0 || !supportsRanges || desiredParts <= 1 || totalBytes < 2 * MIN_PART_BYTES) {
            return listOf(PartState(0, 0, if (totalBytes > 0) totalBytes - 1 else -1))
        }
        val n = minOf(desiredParts.toLong(), totalBytes / MIN_PART_BYTES).toInt().coerceAtLeast(1)
        val size = totalBytes / n
        return (0 until n).map { i ->
            val start = i * size
            val end = if (i == n - 1) totalBytes - 1 else start + size - 1
            PartState(i, start, end)
        }
    }
}
