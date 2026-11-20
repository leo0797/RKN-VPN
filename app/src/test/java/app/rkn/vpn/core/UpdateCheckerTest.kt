package app.rkn.vpn.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckerTest {

    @Test
    fun comparesVersions() {
        assertTrue(UpdateChecker.isNewer("1.0.4", "1.0.3"))
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.0.9"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("1.0.4-beta", "1.0.3"))
        assertFalse(UpdateChecker.isNewer("1.0.3", "1.0.3"))
        assertFalse(UpdateChecker.isNewer("1.0.2", "1.0.3"))
    }

    @Test
    fun parsesGitHubRelease() {
        val body = """
            {
              "tag_name": "v9.9.9",
              "body": "Новое",
              "html_url": "https://github.com/leo0797/RKN-VPN/releases/tag/v9.9.9",
              "assets": [
                {"name": "app-armeabi-v7a-release.apk", "browser_download_url": "https://example.com/arm.apk"},
                {"name": "app-arm64-v8a-release.apk", "browser_download_url": "https://example.com/arm64.apk"}
              ]
            }
        """.trimIndent()
        val release = UpdateChecker.parse(body)!!
        assertTrue(release.version == "9.9.9")
        assertTrue(release.apkUrl!!.endsWith(".apk"))
        assertTrue(release.pageUrl.contains("github.com"))
    }

    @Test
    fun parsesChecksumsAndBindsThemToAssets() {
        val hash = "a".repeat(64)
        val other = "b".repeat(64)
        val notes = """
            Что нового
            app-arm64-v8a-release.apk: $hash
            `$other  app-armeabi-v7a-release.apk`
            random: $other
        """.trimIndent()
        val parsed = UpdateChecker.parseChecksums(notes)
        assertEquals(hash, parsed["app-arm64-v8a-release.apk"])
        assertEquals(other, parsed["app-armeabi-v7a-release.apk"])
        assertEquals(2, parsed.size)
    }

    @Test
    fun releaseContainsMatchingApkSha256() {
        val hash = "c".repeat(64)
        val body = """
            {
              "tag_name": "v9.9.9",
              "body": "app-arm64-v8a-release.apk: $hash",
              "html_url": "https://github.com/leo0797/RKN-VPN/releases/tag/v9.9.9",
              "assets": [
                {"name": "app-arm64-v8a-release.apk", "browser_download_url": "https://github.com/leo0797/RKN-VPN/releases/download/v9.9.9/app-arm64-v8a-release.apk"}
              ]
            }
        """.trimIndent()
        val release = UpdateChecker.parse(body)!!
        assertEquals(hash, release.apkSha256)
    }

    @Test
    fun distinguishesUpdateNoUpdateAndFailures() {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/releases/latest").toString()

            server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson("v9.9.9")))
            val available = UpdateChecker.checkUrl(url)
            assertTrue(available is UpdateChecker.Result.Available)
            assertEquals("9.9.9", (available as UpdateChecker.Result.Available).release.version)

            server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson("v0.0.1")))
            assertTrue(UpdateChecker.checkUrl(url) is UpdateChecker.Result.UpToDate)

            server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
            val serverFailure = UpdateChecker.checkUrl(url) as UpdateChecker.Result.Failed
            assertEquals(
                UpdateChecker.Result.Failure.SERVER,
                serverFailure.reason
            )

            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
            val malformed = UpdateChecker.checkUrl(url) as UpdateChecker.Result.Failed
            assertEquals(
                UpdateChecker.Result.Failure.MALFORMED,
                malformed.reason
            )
        } finally {
            server.shutdown()
        }
    }

    private fun releaseJson(tag: String) = """
        {
          "tag_name": "$tag",
          "body": "changelog",
          "html_url": "https://github.com/leo0797/RKN-VPN/releases/tag/$tag",
          "assets": [
            {"name": "app-arm64-v8a-release.apk", "browser_download_url": "https://example.com/arm64.apk"}
          ]
        }
    """.trimIndent()
}
