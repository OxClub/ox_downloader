package com.oxclub.universaldownloader.utils

/** Turns untrusted remote names into safe local file names. */
object FileNames {
    private val RESERVED = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) { add("COM$i"); add("LPT$i") }
    }
    private val ILLEGAL = Regex("[\\u0000-\\u001F\\u007F<>:\"|?*]")
    private const val MAX_LENGTH = 120

    fun sanitize(raw: String?, fallback: String = "download"): String {
        var n = (raw ?: "").replace('\\', '/').substringAfterLast('/')
        n = ILLEGAL.replace(n, "_").trim()
        n = n.trimStart('.').trimEnd('.', ' ')
        if (n.isEmpty()) n = fallback
        val base = n.substringBeforeLast('.', n)
        if (base.uppercase() in RESERVED) n = "_$n"
        if (n.length > MAX_LENGTH) {
            val ext = extensionOf(n)
            val keep = if (ext.isEmpty()) MAX_LENGTH else MAX_LENGTH - ext.length - 1
            val stem = if (ext.isEmpty()) n else n.substringBeforeLast('.')
            n = if (ext.isEmpty()) stem.take(keep) else stem.take(keep) + "." + ext
        }
        return n
    }

    fun extensionOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i <= 0 || i == name.lastIndex) "" else name.substring(i + 1).lowercase()
    }

    /** "video.mp4" -> "video (1).mp4" until [exists] returns false. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 10_000) {
            val candidate = "$stem ($i)$ext"
            if (!exists(candidate)) return candidate
            i++
        }
        return "$stem (${System.currentTimeMillis()})$ext"
    }
}
