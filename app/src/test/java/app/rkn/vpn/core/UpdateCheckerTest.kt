package app.rkn.vpn.core

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
              "html_url": "https://github.com/leo797/RKN-VPN/releases/tag/v9.9.9",
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
}
