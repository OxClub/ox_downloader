package com.oxclub.universaldownloader.utils

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/** Raised when a URL points at a loopback / private-network address and that is not allowed. */
class BlockedTargetException(host: String) : UnknownHostException("Blocked local address: $host")

object HostPolicy {
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isIpLiteral(host: String): Boolean = IPV4.matches(host) || host.contains(':')

    /** Checks host names and IP literals without performing any DNS lookups. */
    fun isLocalHostName(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") ||
            h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa")
        ) return true
        if (isIpLiteral(h)) {
            return try {
                isPrivateAddress(InetAddress.getByName(h))
            } catch (e: Exception) {
                true
            }
        }
        return false
    }

    fun isPrivateAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
            a.isSiteLocalAddress || a.isMulticastAddress
        ) return true
        val b = a.address
        if (a is Inet4Address && b.size == 4) {
            // Carrier-grade NAT 100.64.0.0/10
            if ((b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64) return true
        }
        if (a is Inet6Address && b.isNotEmpty()) {
            // Unique local addresses fc00::/7
            if ((b[0].toInt() and 0xFE) == 0xFC) return true
        }
        return false
    }
}
