package com.oxclub.universaldownloader.extractor

import com.oxclub.universaldownloader.data.network.FetchResponse
import com.oxclub.universaldownloader.data.network.HttpFetcher
import com.oxclub.universaldownloader.data.network.HttpStatusException
import com.oxclub.universaldownloader.data.network.withCallCancellation
import com.oxclub.universaldownloader.domain.model.AnalysisResult
import com.oxclub.universaldownloader.domain.model.DiscoveredResource
import com.oxclub.universaldownloader.domain.model.NetConfig
import com.oxclub.universaldownloader.domain.model.PageContent
import com.oxclub.universaldownloader.domain.model.ProbeResult
import okio.Buffer
import okhttp3.Response

/**
 * Inspects what a URL really is by talking to the server. Nothing here guesses file types by
 * appending extensions: decisions come from response headers and, for HTML, from the document.
 */
class UrlAnalyzer(private val fetcher: HttpFetcher) {

    /**
     * Sends a `Range: bytes=0-0` GET: one request reveals status, type, total size (via
     * Content-Range), resume support and validators without downloading the body.
     */
    suspend fun probe(url: String, headers: Map<String, String>, cfg: NetConfig): ProbeResult {
        val h = LinkedHashMap(headers)
        h["Range"] = "bytes=0-0"
        h["Accept-Encoding"] = "identity"
        if (h.keys.none { it.equals("Accept", true) }) h["Accept"] = "*/*"
        var fr: FetchResponse = fetcher.fetch(url, h, cfg)
        try {
            if (fr.response.code == 416) {
                fr.close()
                h.remove("Range")
                fr = fetcher.fetch(url, h, cfg)
            }
            val r = fr.response
            if (r.code != 200 && r.code != 206) throw HttpStatusException(r.code, retryAfter(r))
            return buildProbe(url, fr, r)
        } finally {
            fr.close()
        }
    }

    suspend fun analyze(url: String, headers: Map<String, String>, cfg: NetConfig): AnalysisResult {
        val p = probe(url, headers, cfg)
        val kind = MediaDetector.streamKind(p.contentType, p.filename)
        if (kind != null) return AnalysisResult.Manifest(p, kind)
        if (ResourceDetector.isHtml(p.contentType) && !ResourceDetector.isAttachment(p.contentDisposition)) {
            return AnalysisResult.Page(p)
        }
        return AnalysisResult.Direct(p, resourceFrom(p))
    }

    /** Downloads (at most 2 MiB of) the HTML page and extracts the resources it exposes statically. */
    suspend fun loadPage(probe: ProbeResult, headers: Map<String, String>, cfg: NetConfig): PageContent {
        val h = LinkedHashMap(headers)
        h["Accept"] = "text/html,application/xhtml+xml,*/*;q=0.8"
        h["Accept-Encoding"] = "identity"
        val fr = fetcher.fetch(probe.finalUrl, h, cfg)
        try {
            val r = fr.response
            if (r.code != 200) throw HttpStatusException(r.code, retryAfter(r))
            val body = r.body ?: return PageContent(null, emptyList(), true)
            val html = withCallCancellation(fr.call) {
                val buffer = Buffer()
                val source = body.source()
                while (buffer.size < MAX_HTML_BYTES && source.read(buffer, 8192) != -1L) { /* keep reading */ }
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                String(buffer.readByteArray(), charset)
            }
            val doc = HtmlParser.parse(html, fr.finalUrl)
            val resources = LinkExtractor.extract(doc).map { link -> resourceFromLink(link) }
            val incomplete = resources.size < 3 && HtmlParser.scriptCount(doc) > 0
            return PageContent(HtmlParser.title(doc), resources, incomplete || resources.isEmpty())
        } finally {
            fr.close()
        }
    }

    /** Fills in size / real MIME type of a discovered resource with a cheap probe. */
    suspend fun enrich(resource: DiscoveredResource, headers: Map<String, String>, cfg: NetConfig): DiscoveredResource {
        val p = probe(resource.url, headers, cfg)
        val mime = p.contentType ?: resource.mimeType
        val name = if (resource.filename.isNotBlank()) resource.filename else p.filename
        return resource.copy(
            filename = name,
            mimeType = mime,
            category = ResourceDetector.categoryOf(mime, name),
            sizeBytes = p.contentLength,
            probed = true,
        )
    }

    fun resourceFrom(p: ProbeResult): DiscoveredResource = DiscoveredResource(
        url = p.finalUrl,
        filename = p.filename,
        mimeType = p.contentType,
        category = ResourceDetector.categoryOf(p.contentType, p.filename),
        sizeBytes = p.contentLength,
        probed = true,
    )

    private fun resourceFromLink(link: RawLink): DiscoveredResource {
        val ext = ResourceDetector.extensionOfUrl(link.url)
        val mime = ResourceDetector.mimeForExtension(ext)
        val name = ResourceDetector.filenameFrom(null, link.url, mime)
        return DiscoveredResource(
            url = link.url,
            filename = name,
            mimeType = mime,
            category = ResourceDetector.categoryOf(mime, name),
            sourceTag = link.tag,
            streamKind = MediaDetector.streamKind(mime, name),
        )
    }

    private fun buildProbe(originalUrl: String, fr: FetchResponse, r: Response): ProbeResult {
        val contentType = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val total: Long = if (r.code == 206) {
            r.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
        } else {
            r.header("Content-Length")?.toLongOrNull() ?: -1L
        }
        val ranges = r.code == 206 || r.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
        val disposition = r.header("Content-Disposition")
        return ProbeResult(
            originalUrl = originalUrl,
            finalUrl = fr.finalUrl,
            statusCode = r.code,
            contentType = contentType,
            contentLength = total,
            contentDisposition = disposition,
            filename = ResourceDetector.filenameFrom(disposition, fr.finalUrl, contentType),
            supportsRanges = ranges,
            etag = r.header("ETag"),
            lastModified = r.header("Last-Modified"),
            server = r.header("Server"),
            cacheControl = r.header("Cache-Control"),
            contentMd5Base64 = r.header("Content-MD5"),
            redirectCount = fr.redirectCount,
        )
    }

    companion object {
        private const val MAX_HTML_BYTES = 2L * 1024 * 1024

        fun retryAfter(r: Response): Long? = r.header("Retry-After")?.trim()?.toLongOrNull()
    }
}
