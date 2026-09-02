package com.mekromn.apkbox.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleChatTranscriptBuilderTest {
    @Test
    fun overlappingScrollWindowsAreDeduplicatedInOrder() {
        val builder = VisibleChatTranscriptBuilder()
        builder.add(VisibleChatTurn("user", "First question", "fp-user-1"))
        builder.add(VisibleChatTurn("assistant", "First answer", "fp-assistant-1"))
        // Same turn appears again because adjacent UI snapshots overlap.
        builder.add(VisibleChatTurn("assistant", "First answer", "fp-assistant-1"))
        builder.add(VisibleChatTurn("user", "Second question", "fp-user-2"))

        val result = builder.build(
            sourceConversationUrl = "https://chatgpt.com/c/example",
            runId = "run-1",
            expectedLatestFingerprint = "fp-user-2",
        )

        assertEquals(3, result.turnCount)
        assertEquals("fp-user-2", result.lastFingerprint)
        assertEquals(1, Regex("First answer").findAll(result.markdown).count())
        assertTrue(result.markdown.indexOf("First question") < result.markdown.indexOf("First answer"))
        assertTrue(result.markdown.indexOf("First answer") < result.markdown.indexOf("Second question"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesExportWhenLatestVerifiedTurnIsMissing() {
        VisibleChatTranscriptBuilder().apply {
            add(VisibleChatTurn("user", "Old turn", "old"))
        }.build(
            sourceConversationUrl = "",
            runId = "run-2",
            expectedLatestFingerprint = "newest",
        )
    }

    @Test
    fun outputExplicitlyScopesToVisibleContentAndBootstrapsContinuation() {
        val result = VisibleChatTranscriptBuilder().apply {
            add(VisibleChatTurn("user", "Please continue", "a"))
            add(VisibleChatTurn("assistant", "Working", "b"))
        }.build(
            sourceConversationUrl = "https://chatgpt.com/c/example",
            runId = "camera-run-42",
            expectedLatestFingerprint = "b",
        )

        assertTrue(result.markdown.contains("user-visible conversation content only"))
        assertTrue(result.markdown.contains("private hidden model chain-of-thought is not accessible"))
        assertTrue(result.markdown.contains("Continue the APKbox autonomous run `camera-run-42`"))
        assertTrue(result.bytes > 128)
        assertEquals(64, result.sha256.length)
        assertFalse(result.sha256.any { it !in "0123456789abcdef" })
    }
}
