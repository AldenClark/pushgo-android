package io.ethan.pushgo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalNavigationTest {

    @Test
    fun normalizeExternalOpenUrl_allowsSupportedSchemes() {
        assertEquals(
            "https://example.com/path?q=1",
            normalizeExternalOpenUrl("https://example.com/path?q=1")
        )
        assertEquals(
            "https://example.com/path",
            normalizeExternalOpenUrl("example.com/path")
        )
        assertEquals(
            "pushgo://message/123",
            normalizeExternalOpenUrl("pushgo://message/123")
        )
        assertEquals(
            "ftp://example.com/file.txt",
            normalizeExternalOpenUrl("ftp://example.com/file.txt")
        )
    }

    @Test
    fun normalizeExternalOpenUrl_blocksDangerousSchemesAndBypasses() {
        assertNull(normalizeExternalOpenUrl("javascript:alert(1)"))
        assertNull(normalizeExternalOpenUrl("%6a%61%76%61%73%63%72%69%70%74:alert(1)"))
        assertNull(normalizeExternalOpenUrl("java%0d%0ascript:alert(1)"))
        assertNull(normalizeExternalOpenUrl("intent://scan/#Intent;scheme=zxing;package=com.example;end"))
        assertNull(normalizeExternalOpenUrl("data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="))
        assertNull(normalizeExternalOpenUrl("https://user:pass@example.com/"))
        assertNull(normalizeExternalOpenUrl(""))
    }

    @Test
    fun normalizeExternalImageUrl_isStrictlyRemoteHttpAndSafeHost() {
        assertEquals(
            "https://cdn.example.com/a.png",
            normalizeExternalImageUrl("https://cdn.example.com/a.png")
        )

        assertNull(normalizeExternalImageUrl("ftp://cdn.example.com/a.png"))
        assertNull(normalizeExternalImageUrl("http://localhost/a.png"))
        assertNull(normalizeExternalImageUrl("http://127.0.0.1/a.png"))
        assertNull(normalizeExternalImageUrl("http://10.0.0.1/a.png"))
        assertNull(normalizeExternalImageUrl("http://192.168.1.8/a.png"))
        assertNull(normalizeExternalImageUrl("data:image/png;base64,AAA"))
        assertNull(normalizeExternalImageUrl("%64%61%74%61:image/png;base64,AAA"))
    }

    @Test
    fun normalizeExternalOpenUrl_rejectsOversizedAndInvalidHostInput() {
        val oversized = "https://" + "a".repeat(4097)
        assertNull(normalizeExternalOpenUrl(oversized))
        assertNull(normalizeExternalOpenUrl("local/path-without-host"))
        assertNull(normalizeExternalOpenUrl("http:///missing-host"))
    }

    @Test
    fun rewriteVisibleUrlsInText_blocksUnsafeMarkdownDestinations() {
        assertEquals(
            "[safe](https://example.com) [bad](#)",
            rewriteVisibleUrlsInText("[safe](example.com) [bad](javascript:alert(1))")
        )
        assertEquals(
            "[x](#) trailing",
            rewriteVisibleUrlsInText("[x](javascript:alert(1)) trailing")
        )
    }
}
