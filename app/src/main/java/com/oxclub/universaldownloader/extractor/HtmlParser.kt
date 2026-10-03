package com.oxclub.universaldownloader.extractor

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Parses static HTML only. JavaScript is never executed. */
object HtmlParser {
    fun parse(html: String, baseUrl: String): Document = Jsoup.parse(html, baseUrl)

    fun title(doc: Document): String? = doc.title().trim().takeIf { it.isNotEmpty() }

    fun scriptCount(doc: Document): Int = doc.select("script").size
}
