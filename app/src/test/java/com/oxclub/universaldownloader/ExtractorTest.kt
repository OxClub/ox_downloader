package com.oxclub.universaldownloader

import com.oxclub.universaldownloader.domain.model.FileCategory
import com.oxclub.universaldownloader.domain.model.StreamKind
import com.oxclub.universaldownloader.extractor.HtmlParser
import com.oxclub.universaldownloader.extractor.LinkExtractor
import com.oxclub.universaldownloader.extractor.MediaDetector
import com.oxclub.universaldownloader.extractor.ResourceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorTest {
    @Test fun categorizesByMimeAndExtension() {
        assertEquals(FileCategory.VIDEO, ResourceDetector.categoryOf("video/mp4", "a.mp4"))
        assertEquals(FileCategory.MUSIC, ResourceDetector.categoryOf("audio/mpeg", "a.mp3"))
        assertEquals(FileCategory.COMPRESSED, ResourceDetector.categoryOf("application/zip", "a.zip"))
        assertEquals(FileCategory.PROGRAM, ResourceDetector.categoryOf("application/vnd.android.package-archive", "a.apk"))
        assertEquals(FileCategory.DOCUMENT, ResourceDetector.categoryOf("application/pdf", "a.pdf"))
        assertEquals(FileCategory.SUBTITLE, ResourceDetector.categoryOf("text/plain", "movie.srt"))
        assertEquals(FileCategory.VIDEO, ResourceDetector.categoryOf("application/octet-stream", "clip.mkv"))
        assertEquals(FileCategory.OTHER, ResourceDetector.categoryOf("application/octet-stream", "blob"))
        assertEquals(FileCategory.IMAGE, ResourceDetector.categoryOf(null, "p.PNG"))
    }

    @Test fun readsFilenameFromContentDisposition() {
        assertEquals("a b.zip", ResourceDetector.filenameFromDisposition("attachment; filename=\"a b.zip\""))
        assertEquals("héllo.txt", ResourceDetector.filenameFromDisposition("attachment; filename*=UTF-8''h%C3%A9llo.txt"))
        assertEquals("x.bin", ResourceDetector.filenameFromDisposition("attachment; filename=x.bin; size=3"))
        assertNull(ResourceDetector.filenameFromDisposition(null))
    }

    @Test fun derivesSafeFilenames() {
        assertEquals("file.zip", ResourceDetector.filenameFrom(null, "https://e.com/path/file.zip?x=1", "application/zip"))
        assertEquals("evil.exe", ResourceDetector.filenameFrom("attachment; filename=\"../../evil.exe\"", "https://e.com/d", null))
        assertEquals("download.mp4", ResourceDetector.filenameFrom(null, "https://e.com/download", "video/mp4"))
    }

    @Test fun detectsHtmlAndManifests() {
        assertTrue(ResourceDetector.isHtml("text/html; charset=utf-8"))
        assertFalse(ResourceDetector.isHtml("application/pdf"))
        assertEquals(StreamKind.HLS, MediaDetector.streamKind("application/vnd.apple.mpegurl", null))
        assertEquals(StreamKind.HLS, MediaDetector.streamKind(null, "index.m3u8"))
        assertEquals(StreamKind.DASH, MediaDetector.streamKind(null, "manifest.mpd"))
        assertNull(MediaDetector.streamKind("video/mp4", "a.mp4"))
    }

    @Test fun extractsAndResolvesLinks() {
        val html = """
            <html><head><title> Demo </title><link rel="stylesheet" href="style.css"></head><body>
            <a href="files/report.pdf">pdf</a>
            <a href="/archive.zip#top">zip</a>
            <a href="page2.html">another page</a>
            <a href="mailto:a@b.c">mail</a>
            <a href="javascript:void(0)">js</a>
            <img src="img/pic.jpg"><img src="img/pic.jpg">
            <video src="clip.mp4" poster="poster.png"></video>
            <audio><source src="https://cdn.example.net/a.mp3"></audio>
            <video><track src="subs.vtt"></video>
            <a href="http://other.com/setup.apk">apk</a>
            </body></html>
        """.trimIndent()
        val doc = HtmlParser.parse(html, "https://example.com/dir/index.html")
        assertEquals("Demo", HtmlParser.title(doc))
        val urls = LinkExtractor.extract(doc).map { it.url }
        assertTrue(urls.contains("https://example.com/dir/files/report.pdf"))
        assertTrue(urls.contains("https://example.com/archive.zip"))
        assertTrue(urls.contains("https://example.com/dir/img/pic.jpg"))
        assertTrue(urls.contains("https://example.com/dir/clip.mp4"))
        assertTrue(urls.contains("https://example.com/dir/poster.png"))
        assertTrue(urls.contains("https://cdn.example.net/a.mp3"))
        assertTrue(urls.contains("https://example.com/dir/subs.vtt"))
        assertTrue(urls.contains("http://other.com/setup.apk"))
        assertFalse(urls.any { it.endsWith("page2.html") || it.endsWith("style.css") || it.startsWith("mailto") || it.startsWith("javascript") })
        assertEquals("duplicates removed", urls.size, urls.toSet().size)
    }
}
