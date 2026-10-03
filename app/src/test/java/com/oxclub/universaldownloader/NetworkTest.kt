package com.oxclub.universaldownloader

import com.oxclub.universaldownloader.data.network.HttpFetcher
import com.oxclub.universaldownloader.data.network.HttpStatusException
import com.oxclub.universaldownloader.data.network.RedirectLimitException
import com.oxclub.universaldownloader.domain.model.AnalysisResult
import com.oxclub.universaldownloader.domain.model.FileCategory
import com.oxclub.universaldownloader.domain.model.NetConfig
import com.oxclub.universaldownloader.downloader.PartPlanner
import com.oxclub.universaldownloader.downloader.SegmentDownloader
import com.oxclub.universaldownloader.downloader.SegmentSpec
import com.oxclub.universaldownloader.extractor.UrlAnalyzer
import com.oxclub.universaldownloader.utils.BlockedTargetException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Exercises the real networking stack against a local MockWebServer (no mocks of our own code). */
class NetworkTest {
    private lateinit var server: MockWebServer
    private val cfg = NetConfig(connectTimeoutSec = 5, readTimeoutSec = 5, userAgent = "test", allowLocal = true)
    private val fetcher = HttpFetcher()
    private val analyzer = UrlAnalyzer(fetcher)
    private val data = ByteArray(5 * 1024 * 1024 + 123) { (it * 31 % 251).toByte() }
    @Volatile private var rangeRequests = 0

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    path == "/file.bin" -> serveBytes(request, data, ranges = true)
                    path == "/noranges.bin" -> serveBytes(request, data, ranges = false)
                    path == "/redirect" -> MockResponse().setResponseCode(302).setHeader("Location", "/file.bin")
                    path == "/loop" -> MockResponse().setResponseCode(302).setHeader("Location", "/loop")
                    path == "/missing" -> MockResponse().setResponseCode(404)
                    path == "/denied" -> MockResponse().setResponseCode(403)
                    path == "/boom" -> MockResponse().setResponseCode(503).setHeader("Retry-After", "2")
                    path == "/named" -> MockResponse().setHeader("Content-Type", "application/zip")
                        .setHeader("Content-Disposition", "attachment; filename=\"../evil name.zip\"").setBody("PK")
                    path == "/page" -> MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(
                        """<html><head><title>T</title></head><body><a href="/clip.mp4">f</a><img src="/pic.jpg"><a href="/next.html">n</a></body></html>""",
                    )
                    path == "/stream.m3u8" -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun serveBytes(req: RecordedRequest, bytes: ByteArray, ranges: Boolean): MockResponse {
        val range = req.getHeader("Range")
        val r = MockResponse().setHeader("Content-Type", "application/octet-stream").setHeader("ETag", "\"v1\"")
        if (ranges) r.setHeader("Accept-Ranges", "bytes")
        if (range == null || !ranges) return r.setBody(Buffer().write(bytes))
        rangeRequests++
        val m = Regex("bytes=(\\d+)-(\\d*)").find(range) ?: return MockResponse().setResponseCode(416)
        val start = m.groupValues[1].toInt()
        val end = m.groupValues[2].ifEmpty { null }?.toInt() ?: (bytes.size - 1)
        return r.setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
            .setBody(Buffer().write(bytes.copyOfRange(start, end + 1)))
    }

    private fun url(p: String) = server.url(p).toString()

    @Test fun probeReadsSizeRangesAndValidators() = runBlocking {
        val p = analyzer.probe(url("/file.bin"), emptyMap(), cfg)
        assertEquals(data.size.toLong(), p.contentLength)
        assertTrue(p.supportsRanges)
        assertEquals("\"v1\"", p.etag)
        assertEquals("file.bin", p.filename)
        assertEquals(206, p.statusCode)
    }

    @Test fun probeWithoutRangeSupportReportsIt() = runBlocking {
        val p = analyzer.probe(url("/noranges.bin"), emptyMap(), cfg)
        assertFalse(p.supportsRanges)
        assertEquals(data.size.toLong(), p.contentLength)
    }

    @Test fun followsRedirectsAndReportsFinalUrl() = runBlocking {
        val p = analyzer.probe(url("/redirect"), emptyMap(), cfg)
        assertTrue(p.finalUrl.endsWith("/file.bin"))
        assertEquals(1, p.redirectCount)
    }

    @Test fun redirectLoopIsStopped() {
        try {
            runBlocking { analyzer.probe(url("/loop"), emptyMap(), cfg) }
            fail("expected RedirectLimitException")
        } catch (e: RedirectLimitException) {
            // expected
        }
    }

    @Test fun reportsRealHttpErrors() {
        for ((path, code) in listOf("/missing" to 404, "/denied" to 403, "/boom" to 503)) {
            try {
                runBlocking { analyzer.probe(url(path), emptyMap(), cfg) }
                fail("expected failure for $path")
            } catch (e: HttpStatusException) {
                assertEquals(code, e.code)
            }
        }
    }

    @Test fun contentDispositionNameIsSanitized() = runBlocking {
        val p = analyzer.probe(url("/named"), emptyMap(), cfg)
        assertEquals("evil name.zip", p.filename)
    }

    @Test fun classifiesDirectFilesPagesAndManifests() = runBlocking {
        assertTrue(analyzer.analyze(url("/file.bin"), emptyMap(), cfg) is AnalysisResult.Direct)
        assertTrue(analyzer.analyze(url("/page"), emptyMap(), cfg) is AnalysisResult.Page)
        assertTrue(analyzer.analyze(url("/stream.m3u8"), emptyMap(), cfg) is AnalysisResult.Manifest)
        val page = analyzer.analyze(url("/page"), emptyMap(), cfg) as AnalysisResult.Page
        val content = analyzer.loadPage(page.probe, emptyMap(), cfg)
        assertEquals("T", content.title)
        assertTrue(content.resources.any { it.url.endsWith("/clip.mp4") })
        assertTrue(content.resources.any { it.category == FileCategory.IMAGE })
        assertFalse(content.resources.any { it.url.endsWith("next.html") })
    }

    @Test fun localTargetsAreBlockedByDefault() {
        try {
            runBlocking { analyzer.probe(url("/file.bin"), emptyMap(), cfg.copy(allowLocal = false)) }
            fail("expected BlockedTargetException")
        } catch (e: BlockedTargetException) {
            // expected
        }
    }

    private fun tempFile(): File = File.createTempFile("seg", ".part").also { it.deleteOnExit() }

    @Test fun segmentedDownloadProducesIdenticalBytes() = runBlocking {
        val file = tempFile()
        val parts = PartPlanner.plan(data.size.toLong(), true, 4)
        assertEquals(4, parts.size)
        val spec = SegmentSpec(url("/file.bin"), emptyMap(), cfg, data.size.toLong(), "\"v1\"")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(data.size.toLong())
            val ch = raf.channel
            val sd = SegmentDownloader(fetcher)
            coroutineScope { parts.map { p -> async { sd.download(p, spec, ch) } }.awaitAll() }
        }
        assertTrue(parts.all { it.isComplete })
        assertArrayEquals(data, file.readBytes())
        assertTrue(rangeRequests >= 4)
    }

    @Test fun resumeContinuesFromSavedOffset() = runBlocking {
        val file = tempFile()
        val parts = PartPlanner.plan(data.size.toLong(), true, 1)
        val half = 2_000_000
        file.writeBytes(ByteArray(data.size).also { System.arraycopy(data, 0, it, 0, half) })
        parts[0].done.set(half.toLong())
        val spec = SegmentSpec(url("/file.bin"), emptyMap(), cfg, data.size.toLong(), "\"v1\"")
        RandomAccessFile(file, "rw").use { raf ->
            SegmentDownloader(fetcher).download(parts[0], spec, raf.channel)
        }
        assertArrayEquals(data, file.readBytes())
    }

    @Test fun singleStreamWithoutRangeSupport() = runBlocking {
        val file = tempFile()
        val parts = PartPlanner.plan(data.size.toLong(), false, 8)
        assertEquals(1, parts.size)
        val spec = SegmentSpec(url("/noranges.bin"), emptyMap(), cfg, data.size.toLong(), null)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(data.size.toLong())
            SegmentDownloader(fetcher).download(parts[0], spec, raf.channel)
        }
        assertArrayEquals(data, file.readBytes())
    }

    @Test fun serverErrorSurfacesFromSegment() {
        val file = tempFile()
        val part = PartPlanner.plan(-1, false, 1).single()
        val spec = SegmentSpec(url("/missing"), emptyMap(), cfg, -1, null)
        try {
            runBlocking { RandomAccessFile(file, "rw").use { SegmentDownloader(fetcher).download(part, spec, it.channel) } }
            fail("expected HttpStatusException")
        } catch (e: HttpStatusException) {
            assertEquals(404, e.code)
        } catch (e: IOException) {
            fail("wrong exception type: $e")
        }
    }
}
