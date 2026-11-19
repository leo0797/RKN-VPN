package app.rkn.vpn.data

import android.content.Context
import app.rkn.vpn.R
import app.rkn.vpn.core.Strings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionRepositoryTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val repository: SubscriptionRepository
        get() = SubscriptionRepository(Prefs(context), Strings { id, args -> context.getString(id, *args) })

    private val vlessLink =
        "vless://ac9bc33d-db20-4d77-a13b-25cbb5592e14@fin.example.com:443?encryption=none&" +
            "flow=xtls-rprx-vision&type=tcp&security=reality&sni=fin.example.com&fp=firefox&" +
            "pbk=zXTQT-b1AXBwOKu9yWMHmeHO_lSZ6lVG5O-NkIy-WF8&sid=0d6037ea074c9f2e#Finland"

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    @Test
    fun parsesBase64SubscriptionAndHeaders() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("subscription-userinfo", "upload=10; download=20; total=1000; expire=1900000000")
                .addHeader("profile-title", "base64:" + b64("Мой тариф"))
                .setBody(b64(vlessLink))
        )
        server.start()
        try {
            val result = repository.fetch(server.url("/sub").toString())
            assertEquals(1, result.nodes.size)
            assertEquals("Finland", result.nodes.first().name)
            assertEquals(30L, result.info.upload + result.info.download)
            assertEquals("Мой тариф", result.info.title)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun parsesClashYamlSubscription() {
        val yaml = """
            proxies:
              - name: VLESS Reality
                type: vless
                server: fin.example.com
                port: 443
                uuid: 11111111-2222-3333-4444-555555555555
                tls: true
                servername: fin.example.com
                udp: true
                flow: xtls-rprx-vision
        """.trimIndent()
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(yaml))
        server.start()
        try {
            val result = repository.fetch(server.url("/sub.yaml").toString())
            assertEquals(1, result.nodes.size)
            assertTrue(result.nodes.first().name.contains("VLESS"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun reportsNotFoundWithLocalizedHint() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        server.start()
        try {
            val error = runCatching {
                repository.fetch(server.url("/sub").toString())
            }.exceptionOrNull()
            assertTrue(error?.message?.contains("404") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsOversizedResponse() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(2 * 1024 * 1024 + 1)))
        server.start()
        try {
            val error = runCatching {
                repository.fetch(server.url("/sub").toString())
            }.exceptionOrNull()
            assertEquals(
                context.getString(R.string.error_sub_too_large),
                error?.message
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun followsRedirectWithinLimit() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", "/final")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(b64(vlessLink)))
        server.start()
        try {
            val result = repository.fetch(server.url("/sub").toString())
            assertEquals(1, result.nodes.size)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
