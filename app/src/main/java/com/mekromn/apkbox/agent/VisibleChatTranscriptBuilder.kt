package com.mekromn.apkbox.agent

import java.security.MessageDigest

data class TranscriptBuildResult(
    val markdown: String,
    val sha256: String,
    val bytes: Long,
    val turnCount: Int,
    val lastFingerprint: String,
)

class VisibleChatTranscriptBuilder {
    private val turns = LinkedHashMap<String, VisibleChatTurn>()

    fun add(turn: VisibleChatTurn) {
        val fingerprint = turn.sourceFingerprint.trim()
        val markdown = turn.markdown.trim()
        if (fingerprint.isBlank() || markdown.isBlank()) return
        // UIAutomator captures overlap between adjacent scroll windows. The stable source fingerprint
        // keeps the first complete copy and discards viewport duplicates without changing order.
        turns.putIfAbsent(fingerprint, turn.copy(markdown = markdown, sourceFingerprint = fingerprint))
    }

    fun addAll(items: Iterable<VisibleChatTurn>) = items.forEach(::add)

    fun build(
        sourceConversationUrl: String,
        runId: String,
        expectedLatestFingerprint: String = "",
    ): TranscriptBuildResult {
        require(turns.isNotEmpty()) { "No visible ChatGPT turns were captured." }
        if (expectedLatestFingerprint.isNotBlank()) {
            require(turns.containsKey(expectedLatestFingerprint)) {
                "Transcript does not contain the latest verified ChatGPT turn."
            }
        }

        val markdown = buildString {
            appendLine("# ChatGPT Conversation Handoff")
            appendLine()
            appendLine("- APKbox run: `$runId`")
            if (sourceConversationUrl.isNotBlank()) appendLine("- Source conversation: $sourceConversationUrl")
            appendLine("- Export scope: user-visible conversation content only")
            appendLine("- Note: private hidden model chain-of-thought is not accessible to APKbox and is not represented here.")
            appendLine()
            appendLine("---")
            appendLine()
            turns.values.forEachIndexed { index, turn ->
                val role = when (turn.role.trim().lowercase()) {
                    "user" -> "User"
                    "assistant" -> "Assistant"
                    "system" -> "System"
                    else -> turn.role.trim().ifBlank { "Message" }
                }
                appendLine("## $role · ${index + 1}")
                appendLine()
                appendLine(turn.markdown.trim())
                appendLine()
            }
            appendLine("---")
            appendLine()
            appendLine("## Continuation instruction")
            appendLine()
            appendLine("Read this entire handoff before responding. Continue the APKbox autonomous run `$runId` from the latest Continuity checkpoint. Do not restart completed steps unless the checkpoint explicitly requires it.")
        }.trimEnd() + "\n"

        val bytes = markdown.toByteArray(Charsets.UTF_8)
        require(bytes.size >= 128) { "Transcript export is unexpectedly small." }
        val latest = expectedLatestFingerprint.takeIf { it.isNotBlank() } ?: turns.keys.last()
        return TranscriptBuildResult(
            markdown = markdown,
            sha256 = sha256(bytes),
            bytes = bytes.size.toLong(),
            turnCount = turns.size,
            lastFingerprint = latest,
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
