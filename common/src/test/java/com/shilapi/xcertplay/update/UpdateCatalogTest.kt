package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateCatalogTest {
    private val releaseJson = """
        [
          {
            "tag_name": "v0.2.15",
            "draft": true,
            "assets": [
              {"name": "CarPlay-independent-0.2.15.apk", "browser_download_url": "https://github.com/SOLHK/CarPlay/releases/download/v0.2.15/CarPlay-independent-0.2.15.apk"},
              {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/SOLHK/CarPlay/releases/download/v0.2.15/SHA256SUMS.txt"}
            ]
          },
          {
            "tag_name": "v0.2.14",
            "draft": false,
            "prerelease": true,
            "assets": [
              {"name": "CarPlay-independent-0.2.14.apk", "browser_download_url": "https://github.com/SOLHK/CarPlay/releases/download/v0.2.14/CarPlay-independent-0.2.14.apk"},
              {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/SOLHK/CarPlay/releases/download/v0.2.14/SHA256SUMS.txt"}
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun skipsDraftsAndReturnsTheFirstPublishedRelease() {
        val release = UpdateCatalog.parse(releaseJson)
        assertEquals("v0.2.14", release?.tagName)
        assertEquals("CarPlay-independent-0.2.14.apk", release?.apkName)
        assertEquals(
            "https://github.com/SOLHK/CarPlay/releases/download/v0.2.14/CarPlay-independent-0.2.14.apk",
            release?.apkUrl,
        )
        assertEquals(
            "https://github.com/SOLHK/CarPlay/releases/download/v0.2.14/SHA256SUMS.txt",
            release?.checksumsUrl,
        )
    }

    @Test
    fun returnsNullWithoutAnApkAsset() {
        val json = """
            [
              {"tag_name": "v0.2.14", "draft": false, "assets": [
                {"name": "source.zip", "browser_download_url": "https://example.com/source.zip"}
              ]}
            ]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(json))
    }

    @Test
    fun returnsNullWithoutChecksums() {
        val json = """
            [
              {"tag_name": "v0.2.14", "draft": false, "assets": [
                {"name": "CarPlay-independent-0.2.14.apk", "browser_download_url": "https://example.com/CarPlay-independent-0.2.14.apk"}
              ]}
            ]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(json))
    }

    @Test
    fun returnsNullForAnEmptyReleaseList() {
        assertNull(UpdateCatalog.parse("[]"))
    }

    @Test
    fun ignoresOldPackagesWithAnotherApplicationId() {
        val json = """
            [{"tag_name":"v0.2.33","draft":false,"assets":[
              {"name":"CarPlay-0.2.33.apk","browser_download_url":"https://example.com/old.apk"},
              {"name":"SHA256SUMS.txt","browser_download_url":"https://example.com/SHA256SUMS.txt"}
            ]}]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(json))
    }
}
