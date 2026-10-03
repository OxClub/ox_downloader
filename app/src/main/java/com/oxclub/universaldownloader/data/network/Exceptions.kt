package com.oxclub.universaldownloader.data.network

import com.oxclub.universaldownloader.utils.UrlProblem
import java.io.IOException

class HttpStatusException(val code: Int, val retryAfterSeconds: Long? = null) : IOException("HTTP $code")
class RedirectLimitException : IOException("Redirect limit exceeded")
class RangeNotHonoredException : IOException("Server did not honour the range request")
class PrematureEndException : IOException("Connection closed before the download finished")
class InsufficientStorageException(val required: Long, val available: Long) : IOException("Insufficient storage")
class ChecksumMismatchException : IOException("Checksum mismatch")
class InvalidUrlException(val problem: UrlProblem) : IOException("Invalid URL: $problem")
class FolderNotAccessibleException : IOException("Download folder is not accessible")
