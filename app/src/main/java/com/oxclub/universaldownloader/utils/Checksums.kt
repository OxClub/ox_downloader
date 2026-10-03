package com.oxclub.universaldownloader.utils

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Checksums are stored as "algo:hex", e.g. "sha256:ab12...". */
object Checksums {
    private val HEX = Regex("[0-9a-f]+")

    /** Accepts a bare hex MD5 (32), SHA-1 (40) or SHA-256 (64) digest, optionally prefixed with its algorithm. */
    fun parse(input: String): String? {
        val raw = input.trim().lowercase()
        val hex = raw.substringAfter(':', raw).trim()
        if (hex.isEmpty() || !HEX.matches(hex)) return null
        return when (hex.length) {
            32 -> "md5:$hex"
            40 -> "sha1:$hex"
            64 -> "sha256:$hex"
            else -> null
        }
    }

    fun fromContentMd5(base64: String?): String? {
        if (base64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.getDecoder().decode(base64.trim())
            if (bytes.size != 16) null else "md5:" + bytes.joinToString("") { "%02x".format(it) }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun algorithmName(tag: String): String? = when (tag) {
        "md5" -> "MD5"
        "sha1" -> "SHA-1"
        "sha256" -> "SHA-256"
        else -> null
    }

    /** Returns true when the file matches [expected]; throws if the algorithm is unknown. */
    suspend fun verify(file: File, expected: String): Boolean {
        val tag = expected.substringBefore(':')
        val want = expected.substringAfter(':')
        val algo = algorithmName(tag) ?: throw IllegalArgumentException("Unknown checksum algorithm: $tag")
        val md = MessageDigest.getInstance(algo)
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(want, ignoreCase = true)
    }
}
