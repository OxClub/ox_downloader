package com.oxclub.universaldownloader

import com.oxclub.universaldownloader.utils.Checksums
import com.oxclub.universaldownloader.utils.FileNames
import com.oxclub.universaldownloader.utils.Formatters
import com.oxclub.universaldownloader.utils.HostPolicy
import com.oxclub.universaldownloader.utils.UrlCheck
import com.oxclub.universaldownloader.utils.UrlProblem
import com.oxclub.universaldownloader.utils.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilsTest {
    @Test fun validHttpsUrl() {
        val r = UrlUtils.normalize("https://example.com/file.zip#frag")
        assertTrue(r is UrlCheck.Valid)
        assertEquals("https://example.com/file.zip", (r as UrlCheck.Valid).url.toString())
    }

    @Test fun missingSchemeGetsHttps() {
        val r = UrlUtils.normalize("example.com/a.zip") as UrlCheck.Valid
        assertEquals("https", r.url.scheme)
    }

    @Test fun rejectsDangerousSchemes() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "ftp://example.com/a", "content://x/y", "data:text/plain,hi").forEach {
            val r = UrlUtils.normalize(it)
            assertTrue("$it should be rejected", r is UrlCheck.Invalid)
            assertEquals(UrlProblem.UNSUPPORTED_SCHEME, (r as UrlCheck.Invalid).problem)
        }
    }

    @Test fun rejectsEmptyMalformedAndCredentials() {
        assertEquals(UrlProblem.EMPTY, (UrlUtils.normalize("  ") as UrlCheck.Invalid).problem)
        assertEquals(UrlProblem.MALFORMED, (UrlUtils.normalize("not a url") as UrlCheck.Invalid).problem)
        assertEquals(UrlProblem.MALFORMED, (UrlUtils.normalize("hello") as UrlCheck.Invalid).problem)
        assertEquals(UrlProblem.HAS_CREDENTIALS, (UrlUtils.normalize("https://user:pw@example.com/") as UrlCheck.Invalid).problem)
    }

    @Test fun blocksLocalTargetsUnlessAllowed() {
        listOf("http://localhost/a", "http://127.0.0.1/a", "http://192.168.1.5/a", "http://10.0.0.2/a", "http://169.254.1.1/a", "http://[::1]/a")
            .forEach { assertEquals(it, UrlProblem.LOCAL_TARGET, (UrlUtils.normalize(it) as UrlCheck.Invalid).problem) }
        assertTrue(UrlUtils.normalize("http://192.168.1.5/a", allowLocal = true) is UrlCheck.Valid)
        assertFalse(HostPolicy.isLocalHostName("example.com"))
        assertFalse(HostPolicy.isLocalHostName("8.8.8.8"))
    }

    @Test fun extractsUrlsFromSharedText() {
        val urls = UrlUtils.extractUrls("Download this:\nhttps://example.com/file.zip, and (http://a.org/b.pdf).")
        assertEquals(listOf("https://example.com/file.zip", "http://a.org/b.pdf"), urls)
        assertTrue(UrlUtils.extractUrls("no links here").isEmpty())
    }

    @Test fun sanitizesFileNames() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.txt", FileNames.sanitize("C:\\Windows\\evil.txt"))
        assertEquals("download", FileNames.sanitize(".."))
        assertEquals("a_b_c.txt", FileNames.sanitize("a<b>c.txt"))
        assertEquals("_CON.txt", FileNames.sanitize("CON.txt"))
        assertEquals("download", FileNames.sanitize(null))
        assertTrue(FileNames.sanitize("x".repeat(500) + ".mp4").length <= 120)
        assertTrue(FileNames.sanitize("x".repeat(500) + ".mp4").endsWith(".mp4"))
    }

    @Test fun uniqueNameAddsCounter() {
        val taken = setOf("video.mp4", "video (1).mp4")
        assertEquals("video (2).mp4", FileNames.uniqueName("video.mp4") { it in taken })
        assertEquals("new.mp4", FileNames.uniqueName("new.mp4") { it in taken })
    }

    @Test fun formatsSizesSpeedsAndEta() {
        assertEquals("0 B", Formatters.size(0))
        assertEquals("1.0 KB", Formatters.size(1024))
        assertEquals("1.4 GB", Formatters.size((1.4 * 1024 * 1024 * 1024).toLong()))
        assertEquals("8.0 MB/s", Formatters.speed(8.0 * 1024 * 1024))
        assertEquals(1000L, Formatters.etaSeconds(1_000_000, 1000.0))
        assertNull(Formatters.etaSeconds(1000, 10.0))
        assertEquals("5m 12s", Formatters.duration(312))
        assertEquals("1h 02m", Formatters.duration(3720))
        assertEquals(62, Formatters.percent(62, 100))
        assertEquals(0, Formatters.percent(5, -1))
    }

    @Test fun parsesChecksums() {
        assertEquals("md5:d41d8cd98f00b204e9800998ecf8427e", Checksums.parse("D41D8CD98F00B204E9800998ECF8427E"))
        assertNotNull(Checksums.parse("sha256:" + "a".repeat(64)))
        assertNull(Checksums.parse("xyz"))
        assertNull(Checksums.parse("abc123"))
        assertEquals("md5:d41d8cd98f00b204e9800998ecf8427e", Checksums.fromContentMd5("1B2M2Y8AsgTpgAmY7PhCfg=="))
    }
}
