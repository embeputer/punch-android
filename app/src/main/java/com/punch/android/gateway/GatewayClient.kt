package com.punch.android.gateway

import android.util.Base64
import android.util.Log
import com.punch.android.data.OutboundPart
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP client for a Pi coding-agent gateway (Punch-style adapter over `pi --mode rpc`).
 *
 * POST /session → POST /session/{id}/message → optional POST /session/{id}/abort
 */
class GatewayClient(
    private val transport: GatewayTransport = UrlConnectionTransport(),
) {
    @Volatile
    var sessionId: String? = null

    fun health(origin: String, username: String, password: String): GatewayHealth {
        return try {
            val ping = exchange("GET", GatewayUrl.healthUrl(origin), username, password, body = null)
            if (ping.statusCode !in 200..299) {
                return GatewayHealth(
                    false,
                    GatewayErrors.describe(ping.statusCode, null, ping.body, username),
                )
            }
            // /health is unauthenticated; POST /session is the supported auth check.
            val authed = exchange("POST", GatewayUrl.sessionUrl(origin), username, password, body = "{}")
            if (authed.statusCode !in 200..299) {
                return GatewayHealth(
                    false,
                    GatewayErrors.describe(authed.statusCode, null, authed.body, username),
                )
            }
            val probeSessionId = runCatching { JSONObject(authed.body).optString("id") }.getOrNull()
            if (!probeSessionId.isNullOrBlank()) {
                sessionId = probeSessionId
            }
            GatewayHealth(ok = true, detail = "HTTP ${ping.statusCode}")
        } catch (e: Exception) {
            Log.d(TAG, "health failed: ${e.javaClass.simpleName}")
            GatewayHealth(ok = false, detail = GatewayErrors.describe(null, e, username = username))
        }
    }

    fun prompt(
        origin: String,
        username: String,
        password: String,
        text: String,
        extraParts: List<OutboundPart> = emptyList(),
    ): GatewayChatResult {
        val sid = ensureSession(origin, username, password)
        val parts = JSONArray().put(
            JSONObject().put("type", "text").put("text", text),
        )
        extraParts.forEach { part ->
            val obj = JSONObject().put("type", part.type)
            part.text?.let { obj.put("text", it) }
            part.mimeType?.let { obj.put("mimeType", it) }
            part.filename?.let { obj.put("filename", it) }
            part.data?.let { obj.put("data", it) }
            parts.put(obj)
        }
        val payload = JSONObject().put("parts", parts).toString()
        val response = exchange(
            method = "POST",
            url = GatewayUrl.messageUrl(origin, sid),
            username = username,
            password = password,
            body = payload,
        )
        if (response.statusCode == 404) {
            sessionId = null
            val retrySid = ensureSession(origin, username, password)
            val retry = exchange(
                method = "POST",
                url = GatewayUrl.messageUrl(origin, retrySid),
                username = username,
                password = password,
                body = payload,
            )
            return parseAssistant(retry, retrySid, username)
        }
        return parseAssistant(response, sid, username)
    }

    fun abort(origin: String, username: String, password: String) {
        val sid = sessionId
        Log.d(TAG, "abort sessionConfigured=${!sid.isNullOrBlank()}")
        if (!sid.isNullOrBlank()) {
            try {
                exchange(
                    method = "POST",
                    url = GatewayUrl.abortUrl(origin, sid),
                    username = username,
                    password = password,
                    body = "{}",
                )
            } catch (e: Exception) {
                Log.d(TAG, "abort failed: ${e.javaClass.simpleName}")
            }
        }
        (transport as? UrlConnectionTransport)?.cancelActive()
    }

    fun cancel() {
        Log.d(TAG, "cancel active request")
        (transport as? UrlConnectionTransport)?.cancelActive()
    }

    private fun ensureSession(origin: String, username: String, password: String): String {
        val existing = sessionId
        if (!existing.isNullOrBlank()) return existing
        val response = exchange(
            method = "POST",
            url = GatewayUrl.sessionUrl(origin),
            username = username,
            password = password,
            body = "{}",
        )
        if (response.statusCode !in 200..299) {
            throw GatewayException(
                message = GatewayErrors.describe(response.statusCode, null, response.body, username),
                statusCode = response.statusCode,
            )
        }
        val id = JSONObject(response.body).optString("id")
        if (id.isBlank()) {
            throw GatewayException("Pi session response missing id")
        }
        sessionId = id
        Log.d(TAG, "session created")
        return id
    }

    private fun parseAssistant(
        response: GatewayHttpResponse,
        sid: String,
        username: String,
    ): GatewayChatResult {
        if (response.statusCode !in 200..299) {
            val err = runCatching { JSONObject(response.body).optString("error") }.getOrNull()
            throw GatewayException(
                message = err?.takeIf { it.isNotBlank() }
                    ?: GatewayErrors.describe(response.statusCode, null, response.body, username),
                statusCode = response.statusCode,
            )
        }
        return GatewayChatResult(
            text = extractAssistantText(response.body),
            sessionId = sid,
        )
    }

    private fun exchange(
        method: String,
        url: String,
        username: String,
        password: String,
        body: String?,
    ): GatewayHttpResponse {
        val headers = linkedMapOf("Accept" to "application/json")
        if (body != null) headers["Content-Type"] = "application/json"
        headers.putAll(authHeaders(username, password))
        return transport.exchange(method, url, headers, body)
    }

    companion object {
        private const val TAG = "PunchPi"

        fun authHeaders(username: String, password: String): Map<String, String> {
            val user = GatewayErrors.resolveAuthUser(username)
            val pass = password.trim()
            val token = Base64.encodeToString(
                "$user:$pass".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            return mapOf("Authorization" to "Basic $token")
        }

        fun extractAssistantText(body: String): String {
            val root = JSONObject(body)
            val parts = root.optJSONArray("parts")
            if (parts != null) {
                val text = collectTextParts(parts)
                if (text.isNotBlank()) return text
            }
            val info = root.optJSONObject("info")
            val err = info?.optString("error").orEmpty()
            if (err.isNotBlank()) return err
            val direct = root.optString("text")
            if (direct.isNotBlank()) return direct
            throw GatewayException("Empty Pi assistant response")
        }

        private fun collectTextParts(parts: JSONArray): String {
            val chunks = mutableListOf<String>()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") {
                    val text = part.optString("text")
                    if (text.isNotBlank()) chunks += text
                }
            }
            return chunks.joinToString("").trim()
        }
    }
}

class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 300_000,
) : GatewayTransport {
    private val active = AtomicReference<HttpURLConnection?>(null)

    override fun exchange(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): GatewayHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doInput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
            }
        }
        active.set(connection)
        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            return GatewayHttpResponse(statusCode = code, body = text)
        } finally {
            active.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    fun cancelActive() {
        active.getAndSet(null)?.disconnect()
    }
}
