package com.punch.android.gateway

/** Maps gateway failures to something a human can act on. */
object GatewayErrors {
    const val DEFAULT_USERNAME = "opencode"

    fun resolveAuthUser(username: String): String =
        username.trim().ifBlank { DEFAULT_USERNAME }

    fun describe(
        statusCode: Int?,
        error: Throwable?,
        body: String? = null,
        username: String? = null,
    ): String {
        val authUser = resolveAuthUser(username.orEmpty())
        val blob = buildString {
            append(error?.message.orEmpty())
            var cause = error?.cause
            while (cause != null) {
                append(' ')
                append(cause.message.orEmpty())
                cause = cause.cause
            }
        }
        if (looksLikeTlsToHttp(blob)) {
            return "HTTPS hit a plain HTTP port. Punch's gateway on :4096 is HTTP — " +
                "use http://<tailscale-name>:4096. Use https:// only if Tailscale Serve is on 443."
        }
        if (looksLikeAuthRetryStorm(blob)) {
            return "Unauthorized (401). HTTP Basic was sent as user $authUser; " +
                "password does not match OPENCODE_SERVER_PASSWORD."
        }
        if (statusCode == 429 ||
            blob.contains("rate limit", ignoreCase = true) ||
            body?.contains("rate limit", ignoreCase = true) == true
        ) {
            return "Rate limited (429). Wait a bit, then Test once."
        }
        if (statusCode == 401 || body?.contains("Unauthorized", ignoreCase = true) == true) {
            return "Unauthorized (401). HTTP Basic was sent as user $authUser; " +
                "password does not match OPENCODE_SERVER_PASSWORD."
        }
        if (statusCode != null) return "HTTP $statusCode"
        return error?.message?.takeIf { it.isNotBlank() } ?: "unreachable"
    }

    private fun looksLikeAuthRetryStorm(blob: String): Boolean {
        val s = blob.lowercase()
        return s.contains("too many follow-up requests")
    }

    private fun looksLikeTlsToHttp(blob: String): Boolean {
        val s = blob.lowercase()
        return s.contains("tls packet") ||
            s.contains("wrong_version_number") ||
            s.contains("plaintext") ||
            s.contains("not an ssl") ||
            s.contains("cleartext communication")
    }
}
