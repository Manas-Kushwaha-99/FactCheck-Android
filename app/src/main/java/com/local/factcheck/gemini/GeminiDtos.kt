package com.local.factcheck.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ---------- Structured fact-check result ---------- */

@Serializable
data class Claim(
    val claim: String,
    val verdict: String,
    val explanation: String = "",
    val confidence: String? = null
)

@Serializable
data class FactCheckResult(
    val claims: List<Claim> = emptyList(),
    val overall: String = "",
    val uncertainty: String? = null,
    val sources: List<GemSource> = emptyList(),
    /** Search queries the model actually ran (from groundingMetadata). */
    val searched: List<String> = emptyList()
)

@Serializable
data class GemSource(val uri: String?, val title: String?) {
    val domain: String
        get() = runCatching {
            val u = uri ?: return@runCatching null
            val host = java.net.URI(u).host ?: return@runCatching null
            host.removePrefix("www.")
        }.getOrNull() ?: title ?: uri ?: ""
}

/* ---------- Own web-search pipeline (fallback with no quota needed) ---------- */

/** Stage 1 output: claims visible on screen + a web query for each. */
@Serializable
data class ClaimsPlan(val checks: List<ClaimPlan> = emptyList())

@Serializable
data class ClaimPlan(val claim: String, val query: String = "")

/* ---------- Gemini REST DTOs (camelCase JSON) ---------- */

@Serializable
data class GenerateResponse(
    val candidates: List<Candidate> = emptyList(),
    @SerialName("promptFeedback") val promptFeedback: PromptFeedback? = null,
    @SerialName("usageMetadata") val usageMetadata: UsageMetadata? = null,
    @SerialName("modelVersion") val modelVersion: String? = null
) {
    val blockedReason: String? get() = promptFeedback?.blockReason
}

@Serializable
data class Candidate(
    val content: Content? = null,
    @SerialName("finishReason") val finishReason: String? = null,
    @SerialName("groundingMetadata") val groundingMetadata: GroundingMeta? = null
)

@Serializable
data class Content(val parts: List<Part> = emptyList(), val role: String? = null)

@Serializable
data class Part(val text: String? = null)

@Serializable
data class PromptFeedback(val blockReason: String? = null, val blockReasonMessage: String? = null)

@Serializable
data class UsageMetadata(@SerialName("totalTokenCount") val totalTokenCount: Int? = null)

@Serializable
data class GroundingMeta(
    @SerialName("groundingChunks") val chunks: List<GroundingChunk> = emptyList(),
    @SerialName("searchEntryChunk") val searchEntryChunk: List<GroundingChunk> = emptyList(),
    @SerialName("webSearchQueries") val webSearchQueries: List<String> = emptyList()
)

@Serializable
data class GroundingChunk(val web: GroundingWeb? = null)

@Serializable
data class GroundingWeb(val uri: String? = null, @SerialName("title") val title: String? = null)

/* ---------- Models listing ---------- */

@Serializable
data class ListModelsResponse(val models: List<ModelInfo> = emptyList())

@Serializable
data class ModelInfo(
    val name: String,
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("supportedGenerationMethods") val supportedGenerationMethods: List<String> = emptyList(),
    @SerialName("updateTime") val updateTime: String? = null
) {
    /** e.g. "models/gemini-3.5-flash-lite" → "gemini-3.5-flash-lite" */
    val id: String get() = name.removePrefix("models/")
}
