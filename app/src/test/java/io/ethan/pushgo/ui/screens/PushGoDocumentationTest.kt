package io.ethan.pushgo.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushGoDocumentationTest {
    @Test
    fun englishUrls_matchPublishedDocumentationRoutes() {
        assertEquals(
            "https://pushgo.dev/guides/getting-started/",
            PushGoDocumentation.urlFor(PushGoDocumentationPage.GETTING_STARTED, "en-US"),
        )
        assertEquals(
            "https://pushgo.dev/reference/api-message/",
            PushGoDocumentation.urlFor(PushGoDocumentationPage.MESSAGE_API, "en-US"),
        )
        assertEquals(
            "https://pushgo.dev/reference/e2ee/",
            PushGoDocumentation.urlFor(PushGoDocumentationPage.E2EE, "en-US"),
        )
    }

    @Test
    fun chineseLocales_useLocalizedDocumentationPrefix() {
        assertEquals(
            "https://pushgo.dev/zh/guides/getting-started/",
            PushGoDocumentation.urlFor(PushGoDocumentationPage.GETTING_STARTED, "zh-Hans-CN"),
        )
        assertEquals(
            "https://pushgo.dev/zh/reference/api-message/",
            PushGoDocumentation.urlFor(PushGoDocumentationPage.MESSAGE_API, "zh-Hant-TW"),
        )
    }

    @Test
    fun validator_rejectsNonHttpsLookalikeAndCredentialUrls() {
        assertTrue(PushGoDocumentation.isAllowedHttpsUrl("https://pushgo.dev/reference/e2ee/"))
        assertTrue(PushGoDocumentation.isAllowedHttpsUrl("https://pushgo.dev:443/reference/e2ee/"))
        assertFalse(PushGoDocumentation.isAllowedHttpsUrl("http://pushgo.dev/reference/e2ee/"))
        assertFalse(PushGoDocumentation.isAllowedHttpsUrl("https://pushgo.dev.evil.example/reference/e2ee/"))
        assertFalse(PushGoDocumentation.isAllowedHttpsUrl("https://pushgo.dev@evil.example/reference/e2ee/"))
        assertFalse(PushGoDocumentation.isAllowedHttpsUrl("https://pushgo.dev:8443/reference/e2ee/"))
    }
}
