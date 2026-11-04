package com.punch.android.gateway

import java.net.URI

/**
 * Normalizes user-entered Pi gateway origins.
 * Input without a scheme becomes https://. http:// is only accepted for hosts that
 * network_security_config.xml lets through in cleartext (tailnet / LAN names).
 */
object GatewayUrl {
    /** Mirrors the cleartext domain-config in res/xml/network_security_config.xml. */
    val CLEARTEXT_DOMAINS: List<String> = listOf("ts.net", "local", "lan", "home.arpa")
    val CLEARTEXT_HOSTS: List<String> = listOf("localhost", "127.0.0.1", "10.0.2.2")

    fun isCleartextAllowedHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h in CLEARTEXT_HOSTS) return true
        return CLEARTEXT_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    fun isCleartext(origin: String): Boolean = origin.trim().startsWith("http://", ignoreCase = true)

    fun normalizeOrigin(raw: String): Result<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Gateway URL is empty"))
        }
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid gateway URL", e))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.failure(IllegalArgumentException("URL must start with http:// or https://"))
        }
        if (uri.host.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Gateway URL is missing a host"))
        }
        if (scheme == "http" && !isCleartextAllowedHost(uri.host)) {
            return Result.failure(
                IllegalArgumentException(
                    "http:// is only allowed for Tailscale (*.ts.net) or local (.local, .lan, " +
                        ".home.arpa) names. Use https://, or the full MagicDNS name " +
                        "http://<machine>.<tailnet>.ts.net:4096.",
                ),
            )
        }
        val path = uri.path.orEmpty().trimEnd('/')
        val cleanedPath = when {
            path.equals("/v1", ignoreCase = true) -> ""
            path.endsWith("/v1", ignoreCase = true) -> path.dropLast(3).trimEnd('/')
            else -> path
        }
        val portPart = if (uri.port != -1) ":${uri.port}" else ""
        val origin = buildString {
            append(scheme)
            append("://")
            append(uri.host)
            append(portPart)
            if (cleanedPath.isNotEmpty()) append(cleanedPath)
        }
        return Result.success(origin)
    }

    fun healthUrl(origin: String): String = "${origin.trimEnd('/')}/health"

    fun sessionUrl(origin: String): String = "${origin.trimEnd('/')}/session"

    fun messageUrl(origin: String, sessionId: String): String =
        "${origin.trimEnd('/')}/session/$sessionId/message"

    fun abortUrl(origin: String, sessionId: String): String =
        "${origin.trimEnd('/')}/session/$sessionId/abort"
}
