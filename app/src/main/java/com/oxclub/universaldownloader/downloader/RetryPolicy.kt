package com.oxclub.universaldownloader.downloader

import com.oxclub.universaldownloader.data.network.ChecksumMismatchException
import com.oxclub.universaldownloader.data.network.FolderNotAccessibleException
import com.oxclub.universaldownloader.data.network.HttpStatusException
import com.oxclub.universaldownloader.data.network.InsufficientStorageException
import com.oxclub.universaldownloader.data.network.InvalidUrlException
import com.oxclub.universaldownloader.data.network.RedirectLimitException
import com.oxclub.universaldownloader.utils.BlockedTargetException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Exponential backoff with a cap. Only transient failures are retried. */
class RetryPolicy(
    val maxRetries: Int,
    private val baseDelayMs: Long,
    private val maxDelayMs: Long = 60_000L,
) {
    fun isRetryable(e: Throwable): Boolean = when (e) {
        is HttpStatusException -> e.code in RETRYABLE_CODES
        is BlockedTargetException,
        is InsufficientStorageException,
        is ChecksumMismatchException,
        is RedirectLimitException,
        is InvalidUrlException,
        is FolderNotAccessibleException,
        is SSLHandshakeException,
        is SSLPeerUnverifiedException -> false
        is IOException -> true
        else -> false
    }

    /** Delay before retry number [attempt] (1-based). Honours Retry-After when larger. */
    fun delayFor(attempt: Int, e: Throwable): Long {
        val exp = baseDelayMs * (1L shl (attempt - 1).coerceIn(0, 10))
        val retryAfter = ((e as? HttpStatusException)?.retryAfterSeconds ?: 0L) * 1000L
        return minOf(maxDelayMs, maxOf(exp, retryAfter))
    }

    companion object {
        val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504)
    }
}
