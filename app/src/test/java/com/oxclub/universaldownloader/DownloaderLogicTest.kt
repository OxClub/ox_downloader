package com.oxclub.universaldownloader

import com.oxclub.universaldownloader.data.database.DownloadEntity
import com.oxclub.universaldownloader.data.network.HttpStatusException
import com.oxclub.universaldownloader.data.network.InsufficientStorageException
import com.oxclub.universaldownloader.domain.model.DownloadStatus
import com.oxclub.universaldownloader.domain.model.FileCategory
import com.oxclub.universaldownloader.downloader.DownloadQueue
import com.oxclub.universaldownloader.downloader.PartCodec
import com.oxclub.universaldownloader.downloader.PartPlanner
import com.oxclub.universaldownloader.downloader.RetryPolicy
import com.oxclub.universaldownloader.downloader.SpeedMeter
import com.oxclub.universaldownloader.ui.downloads.DownloadFilter
import com.oxclub.universaldownloader.ui.downloads.SortOrder
import com.oxclub.universaldownloader.ui.downloads.StatusFilter
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderLogicTest {
    @Test fun retriesOnlyTransientFailures() {
        val p = RetryPolicy(3, 1000)
        listOf(408, 429, 500, 502, 503, 504).forEach { assertTrue("$it", p.isRetryable(HttpStatusException(it))) }
        listOf(400, 401, 403, 404, 410).forEach { assertFalse("$it", p.isRetryable(HttpStatusException(it))) }
        assertTrue(p.isRetryable(IOException("reset")))
        assertFalse(p.isRetryable(InsufficientStorageException(10, 1)))
        assertFalse(p.isRetryable(IllegalStateException()))
    }

    @Test fun backoffIsExponentialCappedAndHonoursRetryAfter() {
        val p = RetryPolicy(5, 1000, 10_000)
        val e = IOException()
        assertEquals(1000, p.delayFor(1, e))
        assertEquals(2000, p.delayFor(2, e))
        assertEquals(4000, p.delayFor(3, e))
        assertEquals(10_000, p.delayFor(10, e))
        assertEquals(7000, p.delayFor(1, HttpStatusException(503, 7)))
    }

    @Test fun plansSegmentsCoveringWholeFileWithoutGaps() {
        val total = 10L * 1024 * 1024 + 7
        val parts = PartPlanner.plan(total, true, 4)
        assertEquals(4, parts.size)
        assertEquals(0L, parts.first().start)
        assertEquals(total - 1, parts.last().end)
        for (i in 1 until parts.size) assertEquals(parts[i - 1].end + 1, parts[i].start)
        assertEquals(total, parts.sumOf { it.length })
    }

    @Test fun fallsBackToSingleStream() {
        assertEquals(1, PartPlanner.plan(100L * 1024 * 1024, false, 8).size)
        assertEquals(1, PartPlanner.plan(-1, true, 8).size)
        assertEquals(-1L, PartPlanner.plan(-1, true, 8).single().end)
        assertEquals(1, PartPlanner.plan(1024, true, 8).size)
        assertEquals(1, PartPlanner.plan(100L * 1024 * 1024, true, 1).size)
        assertEquals(3, PartPlanner.plan(3L * 1024 * 1024, true, 16).size)
    }

    @Test fun partStateRoundTrips() {
        val parts = PartPlanner.plan(8L * 1024 * 1024, true, 4)
        parts[1].done.set(12345)
        val decoded = PartCodec.decode(PartCodec.encode(parts))!!
        assertEquals(parts.size, decoded.size)
        assertEquals(12345L, decoded[1].done.get())
        assertEquals(parts[2].start, decoded[2].start)
        assertNull(PartCodec.decode("garbage"))
        assertNull(PartCodec.decode(null))
    }

    @Test fun speedMeterSmoothsAndMeasures() {
        val m = SpeedMeter()
        assertEquals(0.0, m.sample(0, 0), 0.0)
        val s1 = m.sample(1000, 1_000_000)
        assertEquals(1_000_000.0, s1, 1.0)
        val s2 = m.sample(2000, 2_000_000)
        assertTrue(s2 > 900_000 && s2 < 1_100_000)
    }

    private fun entity(id: Long, name: String, status: DownloadStatus, cat: FileCategory = FileCategory.OTHER, created: Long = id, total: Long = 100, done: Long = 0, prio: Int = 0) =
        DownloadEntity(id = id, url = "https://e.com/$name", filename = name, status = status, category = cat, createdAt = created, totalBytes = total, downloadedBytes = done, priority = prio)

    @Test fun filtersSearchesAndSorts() {
        val list = listOf(
            entity(1, "movie.mp4", DownloadStatus.DOWNLOADING, FileCategory.VIDEO, total = 1000, done = 500),
            entity(2, "song.mp3", DownloadStatus.COMPLETED, FileCategory.MUSIC, total = 50),
            entity(3, "doc.pdf", DownloadStatus.FAILED, FileCategory.DOCUMENT, total = 10),
            entity(4, "a.zip", DownloadStatus.PAUSED, FileCategory.COMPRESSED, total = 500, done = 400),
            entity(5, "b.zip", DownloadStatus.QUEUED, FileCategory.COMPRESSED, total = 5),
        )
        fun ids(f: StatusFilter = StatusFilter.ALL, q: String = "", c: FileCategory? = null, s: SortOrder = SortOrder.NEWEST) =
            DownloadFilter.apply(list, q, f, c, s).map { it.id }
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), ids())
        assertEquals(listOf(5L, 1L), ids(StatusFilter.DOWNLOADING))
        assertEquals(listOf(2L), ids(StatusFilter.COMPLETED))
        assertEquals(listOf(3L), ids(StatusFilter.FAILED))
        assertEquals(listOf(4L), ids(StatusFilter.PAUSED))
        assertEquals(listOf(5L, 4L), ids(c = FileCategory.COMPRESSED))
        assertEquals(listOf(2L), ids(q = "SONG"))
        assertEquals(listOf(3L), ids(q = "e.com/doc"))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(s = SortOrder.OLDEST))
        assertTrue(ids(q = "nomatch").isEmpty())
        assertEquals(listOf(4L, 5L, 3L, 1L, 2L), ids(s = SortOrder.NAME))
        assertEquals(1L, ids(s = SortOrder.SIZE).first())
        assertEquals(listOf(4L, 1L), ids(s = SortOrder.PROGRESS).take(2))
    }

    @Test fun queueStartsHighestPriorityThenOldest() {
        val q = listOf(
            entity(1, "a", DownloadStatus.QUEUED, created = 10),
            entity(2, "b", DownloadStatus.QUEUED, created = 5),
            entity(3, "c", DownloadStatus.QUEUED, created = 20, prio = 5),
        )
        assertEquals(listOf(3L, 2L), DownloadQueue.nextToStart(q, emptySet(), 2).map { it.id })
        assertEquals(listOf(2L), DownloadQueue.nextToStart(q, setOf(3L), 1).map { it.id })
        assertTrue(DownloadQueue.nextToStart(q, emptySet(), 0).isEmpty())
        assertNotNull(q)
    }
}
