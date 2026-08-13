package com.punch.android.gateway

import java.net.URI

/**
 * Normalizes user-entered Pi gateway origins.
 * Accepts http/https. Tailscale MagicDNS to :4096 is still http.
 */
object GatewayUrl {
    fun normalizeOrigin(raw: String): Result<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Gateway URL is empty"))
        }
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
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
