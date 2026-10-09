package com.zhousl.aether.data.pi

import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.LocalRuntimeId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DynamicPromptPlaceholderRegex = Regex("""\{\{\s*([A-Za-z0-9_-]+)\s*\}\}""")

internal fun buildPiAgentInstructions(
    settings: AppSettings,
    workspaceDirectory: String,
    runtimeId: LocalRuntimeId,
    agentModeEnabled: Boolean,
    chromeEnabled: Boolean = false,
): String = buildString {
    val configuredPrompt = expandDynamicPromptPlaceholders(settings.systemPrompt).trim()
    if (configuredPrompt.isNotBlank()) {
        append(configuredPrompt)
        append("\n\n")
    }
    append(
        "You are running inside Aether on Android. " +
            "The current local runtime is ${runtimeId.storageValue} and its session cwd is $workspaceDirectory. " +
            "Aether keeps Alpine and Termux workspaces independent when the runtime changes. " +
            "User-uploaded files are placed under uploads/; use read on the provided path when image or file contents are needed. " +
            "Aether-owned configuration, Skill, runtime, Extension, Agent Mode, scheduled-task, and developer operations are exposed only through available aether_* tools. " +
            "Never modify LLM provider credentials or model configuration through self-management tools. " +
            "Only claim device actions or command results that were actually observed. " +
            "Write all user-visible text in the language of the user's latest message, including the short notes " +
            "between tool calls (they are shown live to the user), even though these instructions are in English."
    )
    if (agentModeEnabled) {
        append(
            "\n\nAgent Mode is enabled for this chat. Use agent_display only when operating the isolated Android virtual display is required. " +
                "Sending a chat message is launch, then find_and_input, then find_and_tap on the send label. Do not take a screenshot for those steps. " +
                "launch returns the new screen's OCR elements and omits the image unless include_screenshot is true. Next, find_and_tap the label. Do not take a screenshot first. " +
                "Prefer find_and_tap, find_and_input, and tap_node over raw coordinates. " +
                "Do not guess the same area again after a miss; two failures on the same target stop the next attempt. " +
                "Do not convert screenshot pixels or guess a y near the composer. OCR results already include bbox_norm, and the app taps that box center. " +
                "A word inside a merged line has its own element box. Tap that word, not the line. " +
                "find_and_tap does not scroll unless scroll is true, and a send label is never scrolled for. " +
                "Routine taps and typing do not include a screenshot. Pass include_screenshot only when the image is needed, or call action=screenshot. " +
                "If accessibility_hint is present, the accessibility service is off or restricted: keep using find_and_input and find_and_tap. " +
                "sources_tried lists each tree source. An empty tree is unavailable and falls through to the next source, then OCR. " +
                "After an empty tree, later actions set tree_skipped and use OCR without reading the tree again. " +
                "The text argument is only the user's message, never OCR text. clipboard_paste means the composer was replaced, not that the message was sent. " +
                "composer_has_text is true only when that text is in the composer. If it is missing, the app focuses the composer and pastes once. Do not retry the input, and do not look for send until composer_has_text is true. " +
                "A send-like tap returns status uncertain when the chat did not clearly change. Take one screenshot to check. Do not tap or type it again. " +
                "Do not re-read logs or repeat the same check. If a tool says it stopped for no progress, tell the user the current state and stop."
        )
    }
    if (chromeEnabled) {
        append(
            "\n\nThe chat has enabled the browser tool (Chrome Extension tool). Prefer selectors and DOM-reading actions, and use coordinates only as a fallback."
        )
    }
}

private fun expandDynamicPromptPlaceholders(
    prompt: String,
    now: ZonedDateTime = ZonedDateTime.now(),
): String {
    if (!prompt.contains("{{")) return prompt
    val values = mapOf(
        "current_datetime" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        "current_date" to now.toLocalDate().toString(),
        "current_time" to now.toLocalTime().withNano(0).toString(),
        "timezone" to now.zone.id,
        "unix_timestamp" to now.toEpochSecond().toString(),
    )
    return DynamicPromptPlaceholderRegex.replace(prompt) { match ->
        values[match.groupValues[1].lowercase(Locale.US)] ?: match.value
    }
}
