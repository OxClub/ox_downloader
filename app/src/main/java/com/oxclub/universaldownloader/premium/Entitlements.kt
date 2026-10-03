package com.oxclub.universaldownloader.premium

/**
 * Premium architecture. No purchase flow exists in this build (purchases are never faked), so
 * every feature is available. A Google Play Billing backed implementation can replace
 * [OpenEntitlements] and gate: ad-free, more simultaneous downloads / parts, scheduling, themes, priority queue.
 */
interface Entitlements {
    val adFree: Boolean
    val maxSimultaneousDownloads: Int
    val maxParts: Int
}

object OpenEntitlements : Entitlements {
    override val adFree: Boolean = true
    override val maxSimultaneousDownloads: Int = 5
    override val maxParts: Int = 16
}
