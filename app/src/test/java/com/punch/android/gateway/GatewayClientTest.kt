package com.punch.android.gateway

import android.util.Base64
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayUrlTest {
    @Test
    fun acceptsHttpsForAnyHost() {
        assertEquals(
            "https://100.64.0.2:4096",
            GatewayUrl.normalizeOrigin("https://100.64.0.2:4096").getOrThrow(),
        )
        assertEquals(
            "https://example.com",
            GatewayUrl.normalizeOrigin("https://example.com/v1/").getOrThrow(),
        )
    }

    @Test
    fun schemeLessInputDefaultsToHttps() {
        assertEquals(
            "https://example.com:4096",
            GatewayUrl.normalizeOrigin("example.com:4096").getOrThrow(),
        )
        assertEquals("https://pi.local:4096", GatewayUrl.normalizeOrigin("pi.local:4096").getOrThrow())
    }

    @Test
    fun acceptsHttpForTailnetAndLocalNames() {
        listOf(
            "http://pi.tail1234.ts.net:4096",
            "http://pi.local:4096",
            "http://pi.lan:4096",
            "http://pi.home.arpa:4096",
            "http://localhost:4096",
            "http://10.0.2.2:4096",
        ).forEach { url ->
            assertEquals(url, GatewayUrl.normalizeOrigin(url).getOrThrow())
        }
    }

    @Test
    fun rejectsHttpForOtherHosts() {
        listOf(
            "http://example.com:4096",
            "http://203.0.113.5:4096",
            "http://192.168.1.10:4096",
            "http://evilts.net:4096",
            "http://ts.net.example.com:4096",
        ).forEach { url ->
            assertTrue(url, GatewayUrl.normalizeOrigin(url).isFailure)
        }
    }

    @Test
    fun detectsCleartextOrigins() {
        assertTrue(GatewayUrl.isCleartext("http://pi.local:4096"))
        assertTrue(GatewayUrl.isCleartext("HTTP://pi.local:4096"))
        assertFalse(GatewayUrl.isCleartext("https://pi.local:4096"))
        assertFalse(GatewayUrl.isCleartext(""))
    }

    @Test
    fun cleartextAllowlistMatchesNetworkSecurityConfig() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/network_security_config.xml"))
        val base = doc.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))

        val withSubdomains = mutableListOf<String>()
        val exact = mutableListOf<String>()
        val configs = doc.getElementsByTagName("domain-config")
        for (i in 0 until configs.length) {
            val config = configs.item(i) as Element
            if (config.getAttribute("cleartextTrafficPermitted") != "true") continue
            val domains = config.getElementsByTagName("domain")
            for (j in 0 until domains.length) {
                val domain = domains.item(j) as Element
                val name = domain.textContent.trim()
                if (domain.getAttribute("includeSubdomains") == "true") withSubdomains += name else exact += name
            }
        }
        assertEquals(GatewayUrl.CLEARTEXT_DOMAINS.sorted(), withSubdomains.sorted())
        assertEquals(GatewayUrl.CLEARTEXT_HOSTS.sorted(), exact.sorted())
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
    fun healthProbeOmitsAuthorizationButSessionCheckSendsIt() {
        val headersByUrl = mutableMapOf<String, Map<String, String>>()
        val transport = GatewayTransport { _, url, headers, _ ->
            headersByUrl[url] = headers
            GatewayHttpResponse(200, """{"id":"probe-sess"}""")
        }
        val health = GatewayClient(transport).health("https://pi.example", "opencode", "secret")
        assertTrue(health.ok)
        assertFalse(headersByUrl.getValue("https://pi.example/health").containsKey("Authorization"))
        assertEquals(
            GatewayClient.authHeaders("opencode", "secret")["Authorization"],
            headersByUrl.getValue("https://pi.example/session")["Authorization"],
        )
    }

    @Test
    fun healthRetainsProbeSessionIdWhenUnset() {
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
    fun healthAbortsProbeSessionWhenActiveSessionExists() {
        val calls = mutableListOf<String>()
        val transport = GatewayTransport { method, url, _, _ ->
            calls += "$method $url"
            when {
                url.endsWith("/health") -> GatewayHttpResponse(200, """{"ok":true}""")
                method == "POST" && url.endsWith("/session") ->
                    GatewayHttpResponse(200, """{"id":"probe-sess"}""")
                url.endsWith("/session/probe-sess/abort") -> GatewayHttpResponse(200, "{}")
                else -> GatewayHttpResponse(404, "not found")
            }
        }
        val client = GatewayClient(transport)
        client.sessionId = "existing-sess"
        val health = client.health("http://10.0.0.2:4096", "opencode", "secret")
        assertTrue(health.ok)
        assertEquals("existing-sess", client.sessionId)
        assertTrue(
            calls.contains("POST http://10.0.0.2:4096/session/probe-sess/abort"),
        )
    }

    @Test
    fun healthLogsFailedProbeAbort() {
        val transport = GatewayTransport { method, url, _, _ ->
            when {
                url.endsWith("/health") -> GatewayHttpResponse(200, """{"ok":true}""")
                method == "POST" && url.endsWith("/session") ->
                    GatewayHttpResponse(200, """{"id":"probe-sess"}""")
                url.endsWith("/session/probe-sess/abort") -> GatewayHttpResponse(500, "error")
                else -> GatewayHttpResponse(404, "not found")
            }
        }
        val client = GatewayClient(transport)
        client.sessionId = "existing-sess"
        val health = client.health("http://10.0.0.2:4096", "opencode", "secret")
        assertTrue(health.ok)
        assertEquals("existing-sess", client.sessionId)
    }

    @Test
    fun healthFailsWhenSessionUnauthorized() {
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
    fun blockedCleartextIsNotReportedAsTlsMismatch() {
        val detail = GatewayErrors.describe(
            statusCode = null,
            error = IOException("Cleartext HTTP traffic to example.com not permitted"),
        )
        assertTrue(detail, detail.startsWith("Plain http:// is blocked"))
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
