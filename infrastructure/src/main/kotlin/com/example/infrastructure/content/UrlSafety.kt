package com.example.infrastructure.content

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Guards server-side fetches of caller-supplied URLs against SSRF. A URL passes
 * only when it is HTTPS and every address its host resolves to is publicly
 * routable — loopback, link-local (incl. the `169.254.169.254` cloud-metadata
 * endpoint), site-local/private, IPv6 unique-local, wildcard and multicast
 * targets are all rejected.
 *
 * Resolution happens here so a public DNS name pointing at an internal address
 * is caught up front; redirect bypass is defeated separately by fetching with
 * redirect following disabled, so a 3xx cannot escape a validated host.
 */
object UrlSafety {
    /** Returns the parsed URI when [rawUrl] is a safe public HTTPS target, else throws. */
    fun requirePublicHttps(rawUrl: String): URI {
        val uri = runCatching { URI(rawUrl) }.getOrElse { throw IllegalArgumentException("malformed URL") }
        require(uri.scheme?.lowercase() == "https") { "only https URLs are allowed" }
        val host = uri.host ?: throw IllegalArgumentException("URL has no host")
        val addresses =
            runCatching { InetAddress.getAllByName(host) }
                .getOrElse { throw IllegalArgumentException("host does not resolve") }
        require(addresses.isNotEmpty()) { "host does not resolve" }
        require(addresses.all { isPubliclyRoutable(it) }) { "URL resolves to a non-public address" }
        return uri
    }

    private fun isPubliclyRoutable(addr: InetAddress): Boolean =
        !(
            addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isAnyLocalAddress ||
                addr.isMulticastAddress ||
                isUniqueLocalIpv6(addr)
        )

    /** IPv6 unique-local fc00::/7 is not covered by [InetAddress.isSiteLocalAddress]. */
    private fun isUniqueLocalIpv6(addr: InetAddress): Boolean =
        addr is Inet6Address && (
            addr.address
                .firstOrNull()
                ?.toInt()
                ?.and(0xFE) == 0xFC
        )
}
