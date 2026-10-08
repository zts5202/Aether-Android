package com.zhousl.aether.data

/**
 * Xiaomi MiMo V2 chat models, as documented at mimo.mi.com (updated 2026-09-22).
 *
 * This app talks to them through Pi's OpenAI Chat Completions transport
 * (`POST /v1/chat/completions`). On that API, deep thinking is only
 * `thinking.type` = `enabled` or `disabled` (default `enabled`). The Responses
 * API lists named effort values, but Xiaomi says they are not differentiated:
 * `none` turns thinking off and every other value turns it on.
 *
 * The picker therefore offers the two states the chat API actually honors:
 * 关闭 (`off` → `disabled`) and 高 (`high` → `enabled`). `high` is an existing
 * effort id so the saved choice stays valid when the user switches to another
 * provider. Finer steps such as 低 or 中等 are not sent, because Xiaomi would
 * treat them the same as 高.
 */
const val MiMoV26FlashModelId = "mimo-v2.6-flash"

val XiaomiBuiltinProviderIds: Set<String> = setOf(
    "xiaomi",
    "xiaomi-token-plan-cn",
    "xiaomi-token-plan-ams",
    "xiaomi-token-plan-sgp",
)

/** Effort ids shown for every MiMo V2 chat model on the built-in Xiaomi providers. */
val MiMoThinkingLevels: List<String> = listOf("off", "high")

/**
 * Maps a global effort that MiMo cannot express onto [MiMoThinkingLevels].
 * `off` is intentionally absent so the picker keeps showing 关闭.
 */
fun miMoThinkingLevelClamps(): Map<String, String> = mapOf(
    "minimal" to "high",
    "low" to "high",
    "medium" to "high",
    "xhigh" to "high",
    "max" to "high",
)

fun isXiaomiBuiltinProvider(piProviderId: String?): Boolean =
    piProviderId?.trim() in XiaomiBuiltinProviderIds

fun normalizedMiMoModelId(modelId: String): String =
    modelId.substringAfterLast('/').trim().lowercase()

fun isMiMoV2Model(modelId: String): Boolean =
    normalizedMiMoModelId(modelId).startsWith("mimo-v2")

/**
 * Image support from the Chat Completions docs. `null` means "not a known MiMo
 * id" and the caller should keep its normal catalog rule.
 *
 * Vision: `mimo-v2.6-flash`, `mimo-v2.6-pro`, `mimo-v2.6-pro-ultraspeed`, `mimo-v2.5`.
 * Text only: `mimo-v2.5-pro`, `mimo-v2.5-pro-ultraspeed`.
 */
fun miMoSupportsImageInput(modelId: String): Boolean? = when (normalizedMiMoModelId(modelId)) {
    "mimo-v2.6-flash",
    "mimo-v2.6-pro",
    "mimo-v2.6-pro-ultraspeed",
    "mimo-v2.5",
    -> true
    "mimo-v2.5-pro",
    "mimo-v2.5-pro-ultraspeed",
    -> false
    else -> null
}

/** Thinking levels for a built-in Xiaomi MiMo V2 model, or null when this pair is not MiMo. */
fun miMoThinkingLevels(piProviderId: String?, modelId: String): List<String>? =
    if (isXiaomiBuiltinProvider(piProviderId) && isMiMoV2Model(modelId)) {
        MiMoThinkingLevels
    } else {
        null
    }

/**
 * `thinking.type` for Chat Completions. Anything other than 关闭 enables thinking,
 * matching Xiaomi's rule that only `disabled` skips the reasoning pass.
 */
fun miMoThinkingType(reasoningEffort: String?): String =
    if (normalizeReasoningEffort(reasoningEffort) == "off") "disabled" else "enabled"

/**
 * OpenAI-compatible image part URL Xiaomi documents for Chat Completions:
 * `data:{mime};base64,{data}`.
 */
fun miMoImageDataUrl(mimeType: String, base64: String): String {
    val mime = mimeType.trim().ifBlank { "application/octet-stream" }
    return "data:$mime;base64,${base64.trim()}"
}

/** Model id that must stay selectable on every built-in Xiaomi provider. */
fun xiaomiEnsuredModelIds(piProviderId: String?): List<String> =
    if (isXiaomiBuiltinProvider(piProviderId)) listOf(MiMoV26FlashModelId) else emptyList()
