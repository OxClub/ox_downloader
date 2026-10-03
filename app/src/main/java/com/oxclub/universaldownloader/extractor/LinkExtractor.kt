package com.oxclub.universaldownloader.extractor

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class RawLink(val url: String, val tag: String)

object LinkExtractor {
    private const val MAX_LINKS = 300

    /** (selector, attribute, accept-without-known-extension) */
    private val RULES = listOf(
        Triple("a[href]", "href", false),
        Triple("link[href]", "href", false),
        Triple("object[data]", "data", false),
        Triple("iframe[src]", "src", false),
        Triple("embed[src]", "src", false),
        Triple("img[src]", "src", true),
        Triple("video[src]", "src", true),
        Triple("video[poster]", "poster", true),
        Triple("audio[src]", "src", true),
        Triple("source[src]", "src", true),
        Triple("track[src]", "src", true),
        Triple("meta[property^='og:video']", "content", true),
        Triple("meta[property^='og:audio']", "content", true),
        Triple("meta[property^='og:image']", "content", true),
    )

    fun extract(doc: Document): List<RawLink> {
        val base = doc.location().substringBefore('#')
        val out = LinkedHashMap<String, RawLink>()
        for ((selector, attr, acceptAny) in RULES) {
            for (el in doc.select(selector)) {
                if (out.size >= MAX_LINKS) return out.values.toList()
                add(out, el, attr, acceptAny, base)
            }
        }
        return out.values.toList()
    }

    private fun add(out: MutableMap<String, RawLink>, el: Element, attr: String, acceptAny: Boolean, base: String) {
        val abs = el.absUrl(attr).trim()
        if (abs.isEmpty()) return
        val parsed = abs.toHttpUrlOrNull() ?: return
        if (parsed.scheme != "http" && parsed.scheme != "https") return
        val clean = parsed.newBuilder().fragment(null).build().toString()
        if (clean == base) return
        val ext = ResourceDetector.extensionOfUrl(clean)
        val known = ResourceDetector.isKnownExtension(ext)
        if (!known && !acceptAny) return
        out.putIfAbsent(clean, RawLink(clean, el.tagName()))
    }
}
