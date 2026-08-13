package com.punch.android.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownTextTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stripsInlineMarkers() {
        composeRule.setContent {
            MarkdownText("Hello **world** with `code` and *italic*")
        }
        composeRule.onNodeWithText("Hello world with code and italic").assertExists()
    }

    @Test
    fun rendersHeading() {
        composeRule.setContent {
            MarkdownText("# Title\nplain")
        }
        composeRule.onNodeWithText("Title").assertExists()
        composeRule.onNodeWithText("plain").assertExists()
    }

    @Test
    fun rendersListAndCodeBlock() {
        composeRule.setContent {
            MarkdownText("- one\n- two\n\n```kotlin\nval x = 1\n```")
        }
        composeRule.onNodeWithText("•  one").assertExists()
        composeRule.onNodeWithText("•  two").assertExists()
        composeRule.onNodeWithText("val x = 1").assertExists()
    }

    @Test
    fun boldWithInnerAsteriskKeepsText() {
        composeRule.setContent {
            MarkdownText("**a * b**")
        }
        composeRule.onNodeWithText("a * b").assertExists()
    }

    @Test
    fun rendersLinkWithClickableAnnotation() {
        val annotated = inlineMarkdown("see [docs](https://example.com/path?q=1) here") {}
        assertEquals("see docs here", annotated.text)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val link = links[0].item
        assertTrue(link is LinkAnnotation.Clickable)
        assertEquals("https://example.com/path?q=1", (link as LinkAnnotation.Clickable).tag)
    }

    @Test
    fun unsupportedUrlRenderedAsPlainTextWithoutAnnotation() {
        val annotated = inlineMarkdown("call [us](tel:+123456789) now") {}
        assertEquals("call us now", annotated.text)
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
    }

    @Test
    fun supportedUrlValidationAcceptsHttp() {
        assertTrue(isSupportedUrl("https://example.com"))
        assertTrue(isSupportedUrl("http://example.com/a?b=1"))
        assertTrue(isSupportedUrl("HTTPS://example.com"))
        assertTrue(isSupportedUrl("hTtP://example.com"))
    }

    @Test
    fun supportedUrlValidationRejectsUnsafeOrInvalid() {
        val cases = listOf(
            "", "javascript:alert(1)", "tel:+123456789", "file:///etc/passwd",
            "ftp://example.com", "https://", "//example.com", "not a url",
        )
        for (url in cases) {
            assertFalse("expected rejected: <$url>", isSupportedUrl(url))
        }
    }
}
