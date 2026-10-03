package com.oxclub.universaldownloader.utils

import android.content.Context
import com.oxclub.universaldownloader.R
import com.oxclub.universaldownloader.data.network.ChecksumMismatchException
import com.oxclub.universaldownloader.data.network.FolderNotAccessibleException
import com.oxclub.universaldownloader.data.network.HttpStatusException
import com.oxclub.universaldownloader.data.network.InsufficientStorageException
import com.oxclub.universaldownloader.data.network.InvalidUrlException
import com.oxclub.universaldownloader.data.network.PrematureEndException
import com.oxclub.universaldownloader.data.network.RangeNotHonoredException
import com.oxclub.universaldownloader.data.network.RedirectLimitException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Turns exceptions into specific, human-readable messages (never a generic "something went wrong"). */
object ErrorMessages {
    fun problemRes(p: UrlProblem): Int = when (p) {
        UrlProblem.EMPTY -> R.string.err_url_empty
        UrlProblem.UNSUPPORTED_SCHEME -> R.string.err_url_scheme
        UrlProblem.MALFORMED -> R.string.err_url_invalid
        UrlProblem.HAS_CREDENTIALS -> R.string.err_url_credentials
        UrlProblem.LOCAL_TARGET -> R.string.err_blocked_target
    }

    fun http(context: Context, code: Int): String = when (code) {
        401 -> context.getString(R.string.err_http_401)
        403 -> context.getString(R.string.err_http_403)
        404 -> context.getString(R.string.err_http_404)
        410 -> context.getString(R.string.err_http_410)
        429 -> context.getString(R.string.err_http_429)
        in 500..599 -> context.getString(R.string.err_http_5xx, code)
        else -> context.getString(R.string.err_http_other, code)
    }

    fun describe(context: Context, e: Throwable): String = when (e) {
        is HttpStatusException -> http(context, e.code)
        is BlockedTargetException -> context.getString(R.string.err_blocked_target)
        is UnknownHostException -> context.getString(R.string.err_unknown_host)
        is SocketTimeoutException -> context.getString(R.string.err_timeout)
        is ConnectException -> context.getString(R.string.err_connect)
        is SSLException -> context.getString(R.string.err_ssl)
        is RedirectLimitException -> context.getString(R.string.err_redirect_limit)
        is InvalidUrlException -> context.getString(problemRes(e.problem))
        is InsufficientStorageException -> storage(context, e)
        is ChecksumMismatchException -> context.getString(R.string.err_checksum)
        is RangeNotHonoredException -> context.getString(R.string.err_range)
        is PrematureEndException -> context.getString(R.string.err_premature)
        is FolderNotAccessibleException -> context.getString(R.string.err_folder)
        is IOException -> context.getString(R.string.err_io, e.message ?: e.javaClass.simpleName)
        else -> context.getString(R.string.err_unexpected, e.javaClass.simpleName)
    }

    fun storage(context: Context, e: InsufficientStorageException): String =
        context.getString(R.string.err_storage, Formatters.size(e.required), Formatters.size(e.available))
}
