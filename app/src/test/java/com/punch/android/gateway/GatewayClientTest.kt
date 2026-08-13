package com.punch.android.gateway

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayUrlTest {
    @Test
    fun acceptsHttpAndHttps() {
        assertEquals(
            "http://192.168.1.10:4096",
            GatewayUrl.normalizeOrigin("http://192.168.1.10:4096").getOrThrow(),
        )
        assertEquals(
            "https://100.64.0.2:4096",
            GatewayUrl.normalizeOrigin("https://100.64.0.2:4096").getOrThrow(),
        )
    }

    @Test
    fun rejectsNonHttpSchemes() {
        val result = GatewayUrl.normalizeOrigin("ftp://example.com")
        assertTrue(result.isFailure)
    }

    @Test
    fun buildsPiEndpoints() {
        val origin = "http://192.168.1.10:4096"
        assertEquals("http://192.168.1.10:4096/health", GatewayUrl.healthUrl(origin))
        assertEquals("http://192.168.1.10:4096/session", GatewayUrl.sessionUrl(origin))
        assertEquals(
            "http://192.168.1.10:4096/session/abc/message",
            GatewayUrl.messageUrl(origin, "abc"),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayClientTest {
    @Test
    fun extractsAssistantTextFromParts() {
        val body = """
            {
              "info": {"role": "assistant"},
              "parts": [{"type": "text", "text": "hello from pi"}]
            }
        """.trimIndent()
        assertEquals("hello from pi", GatewayClient.extractAssistantText(body))
    }

    @Test
    fun promptCreatesSessionThenPostsMessage() {
        val calls = mutableListOf<String>()
        val transport = GatewayTransport { method, url, headers, body ->
            calls += "$method $url"
            when {
                url.endsWith("/session") && method == "POST" ->
                    GatewayHttpResponse(200, """{"id":"sess-1"}""")
                url.endsWith("/session/sess-1/message") -> {
                    assertTrue(headers["Authorization"]!!.startsWith("Basic "))
                    assertTrue(body!!.contains("ping"))
                    GatewayHttpResponse(
                        200,
                        """{"info":{"role":"assistant"},"parts":[{"type":"text","text":"pong"}]}""",
                    )
                }
                else -> GatewayHttpResponse(404, """{"error":"not found"}""")
            }
        }
        val client = GatewayClient(transport)
        val result = client.prompt(
            origin = "http://10.0.0.2:4096",
            username = "opencode",
            password = "secret",
            text = "ping",
        )
        assertEquals("pong", result.text)
        assertEquals("sess-1", result.sessionId)
        assertEquals(
            listOf(
                "POST http://10.0.0.2:4096/session",
                "POST http://10.0.0.2:4096/session/sess-1/message",
            ),
            calls,
        )
    }

    @Test
    fun healthUsesHealthThenPostSession() {
        val calls = mutableListOf<String>()
        val transport = GatewayTransport { method, url, _, body ->
            calls += "$method $url"
            if (method == "POST" && url.endsWith("/session")) {
                assertEquals("{}", body)
            }
            GatewayHttpResponse(200, """{"ok":true,"tools":["bash"]}""")
        }
        val health = GatewayClient(transport).health("https://100.64.1.1:4096", "u", "p")
        assertTrue(health.ok)
        assertEquals(
            listOf(
                "GET https://100.64.1.1:4096/health",
                "POST https://100.64.1.1:4096/session",
            ),
            calls,
        )
    }

    @Test
    fun healthRetainsProbeSessionId() {
        val transport = GatewayTransport { method, url, _, _ ->
            when {
                url.endsWith("/health") -> GatewayHttpResponse(200, """{"ok":true}""")
                method == "POST" && url.endsWith("/session") ->
                    GatewayHttpResponse(200, """{"id":"probe-sess"}""")
                else -> GatewayHttpResponse(404, "not found")
            }
        }
        val client = GatewayClient(transport)
        val health = client.health("http://10.0.0.2:4096", "opencode", "secret")
        assertTrue(health.ok)
        assertEquals("probe-sess", client.sessionId)
    }

    @Test
        val transport = GatewayTransport { method, url, _, _ ->
            if (url.endsWith("/health")) {
                GatewayHttpResponse(200, """{"ok":true}""")
            } else if (method == "POST" && url.endsWith("/session")) {
                GatewayHttpResponse(401, "Unauthorized")
            } else {
                GatewayHttpResponse(404, "not found")
            }
        }
        val health = GatewayClient(transport).health("http://10.0.0.2:4096", "customuser", "wrong")
        assertFalse(health.ok)
        assertTrue(health.detail.contains("401"))
        assertTrue(health.detail.contains("customuser"))
    }

    @Test
    fun healthFailsWhenSessionForbidden() {
        val transport = GatewayTransport { method, url, _, _ ->
            if (url.endsWith("/health")) {
                GatewayHttpResponse(200, """{"ok":true}""")
            } else if (method == "POST" && url.endsWith("/session")) {
                GatewayHttpResponse(403, "Forbidden")
            } else {
                GatewayHttpResponse(404, "not found")
            }
        }
        val health = GatewayClient(transport).health("http://10.0.0.2:4096", "opencode", "secret")
        assertFalse(health.ok)
        assertTrue(health.detail.contains("403"))
    }

    @Test
    fun healthFailsWhenRateLimited() {
        val transport = GatewayTransport { method, url, _, _ ->
            if (url.endsWith("/health")) {
                GatewayHttpResponse(200, """{"ok":true}""")
            } else if (method == "POST" && url.endsWith("/session")) {
                GatewayHttpResponse(429, "rate limit")
            } else {
                GatewayHttpResponse(404, "not found")
            }
        }
        val health = GatewayClient(transport).health("http://10.0.0.2:4096", "opencode", "secret")
        assertFalse(health.ok)
        assertTrue(health.detail.contains("429"))
    }

    @Test
    fun basicAuthEncodesUserAndPassword() {
        val header = GatewayClient.authHeaders("opencode", "devpassword")["Authorization"]!!
        val encoded = header.removePrefix("Basic ")
        val decoded = String(Base64.decode(encoded, Base64.NO_WRAP))
        assertEquals("opencode:devpassword", decoded)
    }

    @Test
    fun blankUsernameSendsOpencodeBasic() {
        val header = GatewayClient.authHeaders("", "secret")["Authorization"]!!
        val decoded = String(Base64.decode(header.removePrefix("Basic "), Base64.NO_WRAP))
        assertEquals("opencode:secret", decoded)
    }

    @Test
    fun trimsPasswordBeforeEncoding() {
        val header = GatewayClient.authHeaders("opencode", " secret \n")["Authorization"]!!
        val decoded = String(Base64.decode(header.removePrefix("Basic "), Base64.NO_WRAP))
        assertEquals("opencode:secret", decoded)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayErrorsTest {
    @Test
    fun tlsPacketHeaderPointsAtHttp() {
        val detail = GatewayErrors.describe(
            statusCode = null,
            error = javax.net.ssl.SSLHandshakeException("Unable to parse TLS packet header"),
        )
        assertTrue(detail.contains("http://"))
        assertTrue(detail.contains("4096"))
    }

    @Test
    fun unauthorizedPointsAtBasicAuth() {
        val detail = GatewayErrors.describe(401, null, "Unauthorized")
        assertTrue(detail.contains("opencode"))
        assertTrue(detail.contains("OPENCODE_SERVER_PASSWORD"))
    }

    @Test
    fun unauthorizedUsesProvidedUsername() {
        val detail = GatewayErrors.describe(401, null, "Unauthorized", username = "myuser")
        assertTrue(detail.contains("myuser"))
        assertFalse(detail.contains("opencode"))
    }

    @Test
    fun resolveAuthUserDefaultsBlankToOpencode() {
        assertEquals("opencode", GatewayErrors.resolveAuthUser(""))
        assertEquals("opencode", GatewayErrors.resolveAuthUser("   "))
        assertEquals("pi", GatewayErrors.resolveAuthUser(" pi "))
    }

    @Test
    fun followUpStormIsUnauthorized() {
        val detail = GatewayErrors.describe(
            statusCode = null,
            error = java.net.ProtocolException("Too many follow-up requests: 21"),
        )
        assertTrue(detail.contains("401"))
        assertTrue(detail.contains("opencode"))
    }

    @Test
    fun rateLimitedIsExplicit() {
        val detail = GatewayErrors.describe(429, null, "too many requests")
        assertTrue(detail.contains("429"))
        assertTrue(detail.contains("Wait"))
    }
}
