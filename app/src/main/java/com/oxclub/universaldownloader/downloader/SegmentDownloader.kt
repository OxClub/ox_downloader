package com.oxclub.universaldownloader.downloader

import com.oxclub.universaldownloader.data.network.HttpFetcher
import com.oxclub.universaldownloader.data.network.HttpStatusException
import com.oxclub.universaldownloader.data.network.PrematureEndException
import com.oxclub.universaldownloader.data.network.RangeNotHonoredException
import com.oxclub.universaldownloader.data.network.withCallCancellation
import com.oxclub.universaldownloader.domain.model.NetConfig
import com.oxclub.universaldownloader.extractor.UrlAnalyzer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SegmentSpec(
    val url: String,
    val headers: Map<String, String>,
    val cfg: NetConfig,
    val totalBytes: Long,
    /** ETag / Last-Modified sent as If-Range so a changed file is detected instead of corrupted. */
    val validator: String?,
)

/** Streams one byte range of a resource straight into a shared [FileChannel] (positional writes). */
class SegmentDownloader(private val fetcher: HttpFetcher) {

    suspend fun download(part: PartState, spec: SegmentSpec, channel: FileChannel) {
        val startPos = part.start + part.done.get()
        if (part.end >= 0 && startPos > part.end) return

        val whole = startPos == 0L && (part.end < 0 || (spec.totalBytes > 0 && part.end == spec.totalBytes - 1))
        val headers = LinkedHashMap(spec.headers)
        headers["Accept-Encoding"] = "identity"
        if (!whole) {
            headers["Range"] = "bytes=$startPos-" + (if (part.end >= 0) part.end.toString() else "")
            if (startPos > 0 && spec.validator != null) headers["If-Range"] = spec.validator
        }

        val fr = fetcher.fetch(spec.url, headers, spec.cfg)
        try {
            val response = fr.response
            val code = response.code
            if (code == 416) throw RangeNotHonoredException()
            if (code != 200 && code != 206) throw HttpStatusException(code, UrlAnalyzer.retryAfter(response))
            if (!whole && code != 206) throw RangeNotHonoredException()
            val body = response.body ?: throw IOException("Empty response body")

            withCallCancellation(fr.call) {
                val source = body.source()
                val buffer = ByteArray(BUFFER_SIZE)
                var position = startPos
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        var want = buffer.size
                        if (part.end >= 0) {
                            val remaining = part.end - position + 1
                            if (remaining <= 0) break
                            if (remaining < want) want = remaining.toInt()
                        }
                        val n = source.read(buffer, 0, want)
                        if (n < 0) break
                        writeFully(channel, ByteBuffer.wrap(buffer, 0, n), position)
                        position += n
                        part.done.addAndGet(n.toLong())
                    }
                } catch (e: IOException) {
                    // A cancelled call surfaces as an IOException; report it as cancellation instead.
                    currentCoroutineContext().ensureActive()
                    throw e
                }
            }
            if (part.end >= 0 && part.start + part.done.get() <= part.end) throw PrematureEndException()
        } finally {
            fr.close()
        }
    }

    private fun writeFully(channel: FileChannel, data: ByteBuffer, startPosition: Long) {
        var position = startPosition
        while (data.hasRemaining()) {
            position += channel.write(data, position)
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
