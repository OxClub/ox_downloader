package com.oxclub.universaldownloader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oxclub.universaldownloader.data.database.AppDatabase
import com.oxclub.universaldownloader.data.database.DownloadEntity
import com.oxclub.universaldownloader.domain.model.DownloadStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadDaoTest {
    private lateinit var db: AppDatabase

    @Before fun open() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
    }

    @After fun close() { db.close() }

    @Test fun persistsProgressAndStatus() = runBlocking {
        val dao = db.downloadDao()
        val id = dao.insert(DownloadEntity(url = "https://e.com/a.zip", filename = "a.zip"))
        dao.updateProgress(id, 1234, "0:0:9:5", 900)
        dao.setStatus(id, DownloadStatus.PAUSED, null)
        val e = dao.get(id)!!
        assertEquals(1234L, e.downloadedBytes)
        assertEquals("0:0:9:5", e.partsState)
        assertEquals(DownloadStatus.PAUSED, e.status)
    }

    @Test fun recoversInterruptedDownloads() = runBlocking {
        val dao = db.downloadDao()
        val id = dao.insert(DownloadEntity(url = "https://e.com/a", filename = "a", status = DownloadStatus.DOWNLOADING))
        dao.moveStatuses(listOf(DownloadStatus.ANALYZING, DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING), DownloadStatus.QUEUED)
        assertEquals(DownloadStatus.QUEUED, dao.get(id)!!.status)
    }

    @Test fun findsActiveDuplicatesAndRequeuesRetryable() = runBlocking {
        val dao = db.downloadDao()
        dao.insert(DownloadEntity(url = "https://e.com/x", filename = "x", status = DownloadStatus.PAUSED))
        val active = listOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED)
        assertEquals("x", dao.findByUrl("https://e.com/x", active)!!.filename)
        assertNull(dao.findByUrl("https://e.com/none", active))
        val f = dao.insert(DownloadEntity(url = "https://e.com/f", filename = "f", status = DownloadStatus.FAILED, retryable = true))
        dao.requeueRetryable()
        assertEquals(DownloadStatus.QUEUED, dao.get(f)!!.status)
    }
}
