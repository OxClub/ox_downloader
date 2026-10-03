package com.oxclub.universaldownloader.extractor

import com.oxclub.universaldownloader.domain.model.FileCategory
import com.oxclub.universaldownloader.utils.FileNames
import java.net.URLDecoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Pure-Kotlin MIME / extension / file-name logic (no Android dependencies, so it is unit-testable). */
object ResourceDetector {
    private val EXT_MIME: Map<String, String> = mapOf(
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "mov" to "video/quicktime",
        "avi" to "video/x-msvideo", "m4v" to "video/x-m4v", "3gp" to "video/3gpp", "flv" to "video/x-flv",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "wav" to "audio/x-wav", "flac" to "audio/flac",
        "ogg" to "audio/ogg", "aac" to "audio/aac", "opus" to "audio/opus",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "gif" to "image/gif", "svg" to "image/svg+xml", "bmp" to "image/bmp",
        "pdf" to "application/pdf", "zip" to "application/zip", "rar" to "application/x-rar-compressed",
        "7z" to "application/x-7z-compressed", "tar" to "application/x-tar", "gz" to "application/gzip",
        "apk" to "application/vnd.android.package-archive", "exe" to "application/x-msdownload",
        "msi" to "application/x-msi", "dmg" to "application/x-apple-diskimage", "deb" to "application/vnd.debian.binary-package",
        "iso" to "application/x-iso9660-image",
        "txt" to "text/plain", "csv" to "text/csv", "json" to "application/json", "xml" to "application/xml",
        "srt" to "application/x-subrip", "vtt" to "text/vtt",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "m3u8" to "application/vnd.apple.mpegurl", "mpd" to "application/dash+xml",
    )

    private val MIME_EXT: Map<String, String> = buildMap {
        EXT_MIME.forEach { (ext, mime) -> putIfAbsent(mime, ext) }
        put("image/jpeg", "jpg"); put("audio/mpeg", "mp3"); put("text/html", "html")
        put("application/octet-stream", "bin")
    }

    private val EXT_CATEGORY: Map<String, FileCategory> = buildMap {
        listOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "flv").forEach { put(it, FileCategory.VIDEO) }
        listOf("mp3", "m4a", "wav", "flac", "ogg", "aac", "opus").forEach { put(it, FileCategory.MUSIC) }
        listOf("jpg", "jpeg", "png", "webp", "gif", "svg", "bmp").forEach { put(it, FileCategory.IMAGE) }
        listOf("pdf", "txt", "csv", "json", "xml", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
            .forEach { put(it, FileCategory.DOCUMENT) }
        listOf("zip", "rar", "7z", "tar", "gz", "iso").forEach { put(it, FileCategory.COMPRESSED) }
        listOf("apk", "exe", "msi", "dmg", "deb").forEach { put(it, FileCategory.PROGRAM) }
        listOf("srt", "vtt").forEach { put(it, FileCategory.SUBTITLE) }
    }

    private val GENERIC_MIMES = setOf("application/octet-stream", "binary/octet-stream", "application/force-download", "application/download")

    fun mimeForExtension(ext: String): String? = EXT_MIME[ext.lowercase()]
    fun extensionForMime(mime: String?): String? = mime?.lowercase()?.let { MIME_EXT[it] }
    fun isKnownExtension(ext: String): Boolean = ext.lowercase() in EXT_MIME

    fun isHtml(contentType: String?): Boolean {
        val ct = contentType?.lowercase() ?: return false
        return ct.startsWith("text/html") || ct.startsWith("application/xhtml")
    }

    fun categoryOf(mime: String?, filename: String?): FileCategory {
        val m = mime?.substringBefore(';')?.trim()?.lowercase()
        val ext = filename?.let { FileNames.extensionOf(it) }.orEmpty()
        val byExt = EXT_CATEGORY[ext]
        if (m == null || m in GENERIC_MIMES) return byExt ?: FileCategory.OTHER
        if (byExt == FileCategory.SUBTITLE) return FileCategory.SUBTITLE
        val byMime = when {
            m.startsWith("video/") -> FileCategory.VIDEO
            m.startsWith("audio/") -> FileCategory.MUSIC
            m.startsWith("image/") -> FileCategory.IMAGE
            m == "application/pdf" || m.startsWith("text/") || m == "application/json" || m == "application/xml" ||
                m.contains("msword") || m.contains("officedocument") || m.contains("ms-excel") || m.contains("ms-powerpoint") ->
                FileCategory.DOCUMENT
            m == "application/zip" || m.contains("rar") || m.contains("7z") || m == "application/x-tar" ||
                m == "application/gzip" || m == "application/x-gzip" || m.contains("iso9660") -> FileCategory.COMPRESSED
            m.contains("vnd.android.package-archive") || m.contains("msdownload") || m.contains("apple-diskimage") ||
                m.contains("debian") || m.contains("x-msi") -> FileCategory.PROGRAM
            else -> null
        }
        return byMime ?: byExt ?: FileCategory.OTHER
    }

    /** Extracts a file name from a Content-Disposition header value, or null. */
    fun filenameFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            return try {
                URLDecoder.decode(m.groupValues[2].trim().trim('"'), charset)
            } catch (e: Exception) {
                null
            }
        }
        Regex("filename\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(header)?.let { return it.groupValues[1] }
        Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { return it.groupValues[1].trim() }
        return null
    }

    fun isAttachment(header: String?): Boolean = header?.trimStart()?.startsWith("attachment", ignoreCase = true) == true

    /** Best-effort safe local file name for a URL + headers. */
    fun filenameFrom(contentDisposition: String?, url: String, mime: String?): String {
        val fromHeader = filenameFromDisposition(contentDisposition)
        val fromUrl = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
        var name = FileNames.sanitize(fromHeader ?: fromUrl ?: url.toHttpUrlOrNull()?.host ?: "download")
        if (FileNames.extensionOf(name).isEmpty()) {
            extensionForMime(mime?.substringBefore(';')?.trim())?.let { name = "$name.$it" }
        }
        return name
    }

    fun extensionOfUrl(url: String): String {
        val last = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
        return FileNames.extensionOf(last)
    }
}
