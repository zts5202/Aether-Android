package com.zhousl.aether.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Image-input capability that Pi's built-in provider catalog reports for a model, keyed by Pi
 * provider id and then by model id.
 *
 * A missing entry means "not reported", not "unsupported": the kernel enables image input for every
 * model absent from its static catalog (custom providers and freshly released ids), so unknown
 * models must stay permissive instead of being silently downgraded to text-only.
 */
data class ProviderModelCapabilities(
    private val byProviderAndModel: Map<String, Map<String, Boolean>> = emptyMap(),
) {
    /** True unless the catalog explicitly reports a text-only model. */
    fun supportsImageInput(piProviderId: String?, modelId: String): Boolean =
        lookup(piProviderId, modelId) ?: true

    /** True when the catalog has an explicit entry for this provider/model pair. */
    fun isReported(piProviderId: String?, modelId: String): Boolean =
        lookup(piProviderId, modelId) != null

    val reportedModelCount: Int
        get() = byProviderAndModel.values.sumOf { it.size }

    private fun lookup(piProviderId: String?, modelId: String): Boolean? {
        val providerId = piProviderId?.trim().orEmpty()
        val normalizedModelId = modelId.trim()
        if (providerId.isEmpty() || normalizedModelId.isEmpty()) return null
        return byProviderAndModel[providerId]?.get(normalizedModelId)
    }

    companion object {
        val Empty = ProviderModelCapabilities()
    }
}

/**
 * Parses the `list_providers` payload emitted by the Pi bridge, which reports each built-in model's
 * declared `input` modalities.
 */
fun parseProviderModelCapabilities(payload: JsonObject): ProviderModelCapabilities {
    val providers = payload["providers"] as? JsonArray ?: return ProviderModelCapabilities.Empty
    val byProvider = buildMap {
        providers.forEach { providerElement ->
            val provider = providerElement as? JsonObject ?: return@forEach
            val providerId = provider.stringOrNull("id").orEmpty()
            val models = provider["models"] as? JsonArray ?: return@forEach
            val byModel = buildMap {
                models.forEach { modelElement ->
                    val model = modelElement as? JsonObject ?: return@forEach
                    val modelId = model.stringOrNull("id").orEmpty()
                    val input = model["input"] as? JsonArray ?: return@forEach
                    if (modelId.isEmpty()) return@forEach
                    put(modelId, input.any { it.primitiveOrNull() == "image" })
                }
            }
            if (providerId.isNotEmpty() && byModel.isNotEmpty()) put(providerId, byModel)
        }
    }
    return ProviderModelCapabilities(byProvider)
}

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()

private fun JsonElement.primitiveOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

/**
 * Parses a serialized `list_providers` payload. Platforms that hand the bridge response around as
 * a JSON string (Android's `org.json` based client) can use this directly.
 */
fun parseProviderModelCapabilities(payloadJson: String): ProviderModelCapabilities =
    (runCatching { Json.parseToJsonElement(payloadJson) }.getOrNull() as? JsonObject)
        ?.let(::parseProviderModelCapabilities)
        ?: ProviderModelCapabilities.Empty
