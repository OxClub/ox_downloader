package com.oxclub.universaldownloader.data.network

import com.oxclub.universaldownloader.domain.model.NetConfig
import com.oxclub.universaldownloader.utils.BlockedTargetException
import com.oxclub.universaldownloader.utils.HostPolicy
import com.oxclub.universaldownloader.utils.UrlProblem
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class FetchResponse(
    val response: Response,
    val call: Call,
    val finalUrl: String,
    val redirectCount: Int,
) : Closeable {
    override fun close() {
        response.close()
    }
}

/** Resolves host names but refuses private/loopback targets unless explicitly allowed. */
class FilteringDns(private val allowLocal: Boolean) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (!allowLocal && addresses.any { HostPolicy.isPrivateAddress(it) }) throw BlockedTargetException(hostname)
        return addresses
    }
}

/** Runs [block] and cancels [call] if the surrounding coroutine is cancelled (unblocks stalled reads). */
suspend fun <T> withCallCancellation(call: Call, block: suspend () -> T): T = coroutineScope {
    val watchdog = launch(Dispatchers.Unconfined) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        block()
    } finally {
        watchdog.cancel()
    }
}

/**
 * Thin OkHttp wrapper that follows redirects manually so the depth is limited, every hop is
 * validated, and credentials are not forwarded to a different host.
 */
class HttpFetcher(
    private val base: OkHttpClient = OkHttpClient.Builder().apply {
        dispatcher(Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 64 })
        connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
    }.build(),
) {
    private fun client(cfg: NetConfig): OkHttpClient = base.newBuilder()
        .connectTimeout(cfg.connectTimeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(cfg.readTimeoutSec.toLong(), TimeUnit.SECONDS)
        .writeTimeout(cfg.readTimeoutSec.toLong(), TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .dns(FilteringDns(cfg.allowLocal))
        .build()

    suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        cfg: NetConfig,
        maxRedirects: Int = MAX_REDIRECTS,
    ): FetchResponse {
        val client = client(cfg)
        var current: HttpUrl = url.toHttpUrlOrNull() ?: throw InvalidUrlException(UrlProblem.MALFORMED)
        var hdrs = headers
        var redirects = 0
        while (true) {
            if (current.scheme != "http" && current.scheme != "https") throw InvalidUrlException(UrlProblem.UNSUPPORTED_SCHEME)
            if (!cfg.allowLocal && HostPolicy.isLocalHostName(current.host)) throw BlockedTargetException(current.host)
            val builder = Request.Builder().url(current)
            if (hdrs.keys.none { it.equals("User-Agent", ignoreCase = true) }) builder.header("User-Agent", cfg.userAgent)
            hdrs.forEach { (k, v) -> builder.header(k, v) }
            val call = client.newCall(builder.build())
            val response = call.await()
            if (response.isRedirect) {
                val location = response.header("Location")
                val code = response.code
                response.close()
                if (location == null) throw HttpStatusException(code)
                redirects++
                if (redirects > maxRedirects) throw RedirectLimitException()
                val next = current.resolve(location) ?: throw InvalidUrlException(UrlProblem.MALFORMED)
                if (!next.host.equals(current.host, ignoreCase = true)) {
                    hdrs = hdrs.filterKeys { !it.equals("Cookie", true) && !it.equals("Authorization", true) }
                }
                current = next
                continue
            }
            return FetchResponse(response, call, current.toString(), redirects)
        }
    }

    companion object {
        const val MAX_REDIRECTS = 10
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    val call = this
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}
