package com.local.factcheck.gemini

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.local.factcheck.util.Config
import com.local.factcheck.util.WebSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Thin Gemini REST client on OkHttp, purpose-built for the fact-check flow.
 *
 * Auth: standard `x-goog-api-key` header.
 * Fail-soft strategy: on request-shape rejections (tool/schema unsupported,
 * model renamed/retired) we transparently retry with the reduced shape so the
 * user always gets an answer when one is honestly available.
 */
class GeminiClient(private val config: Config) {

    companion object {
        private const val TAG = "GeminiClient"
        private const val BASE = "https://generativelanguage.googleapis.com"
        private const val VERSION = "v1beta"
        private const val MAX_IMAGE_EDGE = 1280
        private const val JPEG_QUALITY = 82

        val PROMPT = """
You are a meticulous, honest fact-checker. You receive a screenshot of the user's screen; this may contain a news article, a social post, a chat, a video player, or a document.

Your task:
1. Identify the 3–6 most important factual claims visible as TEXT on the screen (headlines, statements, numbers, dates, prices, quotes, medical/legal advice, event reports). Ignore decorative images, stock illustrations, unreadable charts, ads and navigation UI. Never take image content as the claim itself.
2. Treat your own knowledge as possibly outdated. For EVERY claim about current events, politics, health, finance, sports, science, or anything time-sensitive, you MUST run the provided Google Search tool and base the verdict on the search results. Do not answer such claims from memory.
3. If search results contradict your memory, trust the search results.
4. Allowed verdict values: TRUE, MOSTLY TRUE, MISLEADING, FALSE, UNVERIFIED. Do not invent other labels.
5. Write an "overall" one-sentence reliability summary of the whole screen.
6. In "uncertainty", briefly list anything you could not verify, could not read clearly, or that depends on context.

Rules:
- Each explanation should briefly name the matched fact/source you verified against.
- Output values ONLY through the JSON fields given to you. No markdown, no extra keys.
- Total text under 180 words. Explanations max 25 words each.
- If nothing fact-checkable is on screen: claims=[] and overall explains why.
- The same text claim must get the same verdict regardless of the surrounding image or layout.
- Do not speculate. "UNVERIFIED" is better than a confident wrong answer.
        """.trimIndent()
    }

    class GeminiHttpException(val httpCode: Int, message: String, val body: String = "") :
        Exception(message)

    /* ------------------------- Public API ------------------------- */

    suspend fun factCheck(jpeg: Bitmap, key: String, modelIn: String): FactCheckResult =
        withContext(Dispatchers.IO) {
            val b64 = toBase64Jpeg(jpeg)
            var model = modelIn
            var schema = true
            var lastError: GeminiHttpException? = null

            /* -------- Phase 1: native Google Search grounding (fast path) -------- */
            var quick429RetryDone = false
            for (attempt in 1..4) {
                try {
                    return@withContext runCheck(b64, key, model, true, schema, false)
                } catch (e: GeminiHttpException) {
                    lastError = e
                    val m = (e.message ?: "error").lowercase()
                    Log.w(TAG, "phase1 a=$attempt model=$model schema=$schema → ${e.httpCode} ${e.message}")

                    // Model id retired/renamed → resolve the newest Flash-Lite.
                    if (e.httpCode == 404 && ("model" in m || "not found" in m)) {
                        val alt = pickBestFlashLite(key)
                        if (alt != null && alt != model) {
                            model = alt
                            config.model = alt
                            Config.autoModelNote = "Auto-switched to $alt after model error."
                            continue
                        }
                    }
                    // Rate limited: ONE short retry (≤15 s), then fall through to
                    // the free pipeline instead of stalling.
                    if (e.httpCode == 429) {
                        if (!quick429RetryDone) {
                            quick429RetryDone = true
                            val wait = retryDelaySeconds(e.body).coerceIn(2, 15)
                            Log.w(TAG, "429 — retrying in ${wait}s, then fallback pipeline")
                            delay(wait * 1000L)
                            continue
                        }
                    }
                    // Google Search tool itself rejected → go straight to pipeline.
                    if ("google_search" in m || "googlesearch" in m) break
                    // Structured output rejected → drop schema, retry.
                    if (schema && ("schema" in m || "response_mime_type" in m ||
                            "responsemimetype" in m)
                    ) {
                        schema = false
                        continue
                    }
                    if (e.httpCode in 400..499 && "api key" in m) {
                        throw GeminiHttpException(
                            e.httpCode,
                            (e.message ?: "error") + "  " + (quotaDetails(e.body) ?: ""),
                            e.body
                        )
                    }
                    // Any other hard failure → try the pipeline anyway below.
                    break
                }
            }

            /* -------- Phase 2: own free web pipeline (no Gemini quota needed) --------
             *  1) model extracts claims + one web query per claim from the screenshot
             *  2) app fetches DuckDuckGo results itself (free, no API key)
             *  3) model verifies the claims against the fetched snippets            */
            Log.w(TAG, "native search unavailable (${lastError?.httpCode}) — switching to built-in web pipeline")
            try {
                val plan = planQueries(b64, key, model)
                val checks = plan.checks.filter { it.query.isNotBlank() }.take(4)
                if (checks.isEmpty()) {
                    throw GeminiHttpException(500, "could not extract fact-checkable claims from the screen")
                }
                val snippets = coroutineScope {
                    checks.map { async(Dispatchers.IO) { searchWeb(it.query) } }.awaitAll()
                }
                val total = snippets.sumOf { it.size }
                Log.d(TAG, "pipeline: ${checks.size} queries fetched, $total snippets")
                if (total == 0) {
                    throw GeminiHttpException(503, "web search failed — the device may be offline, or DuckDuckGo blocked this network. Check internet and try again.")
                }
                return@withContext verifyFromResults(b64, key, model, checks.zip(snippets))
            } catch (t: Throwable) {
                val msg = (t as? GeminiHttpException)?.message ?: "search pipeline failed: ${t.message ?: t.javaClass.simpleName}"
                val orig = lastError?.let { " (native search said: ${it.httpCode} ${it.message?.take(80)})" } ?: ""
                throw GeminiHttpException((t as? GeminiHttpException)?.httpCode ?: 500, msg + orig)
            }
        }

    /** Cheap validation: list models with this key (no generation cost). */
    suspend fun listFlashLiteModels(key: String): List<ModelInfo> =
        withContext(Dispatchers.IO) {
            val url = "$BASE/$VERSION/models?pageSize=50"
            val body = doGet(url, key)
            json.decodeFromString<ListModelsResponse>(body).models
        }

    suspend fun pickBestFlashLite(key: String): String? =
        withContext(Dispatchers.IO) {
            val bad = setOf("image", "tts", "live", "transcribe", "omni", "audio", "embedding", "thinking")
            val candidates = listFlashLiteModels(key).asSequence()
                .filter { "flash-lite" in it.id }
                .filter { bad.none { b -> it.id.contains(b) } }
                .filter { "generateContent" in it.supportedGenerationMethods }
                .sortedWith(
                    compareBy({ versionScore(it.id) * -1 }, { it.updateTime ?: "" })
                )
                .toList()
            candidates.firstOrNull()?.id
        }

    private fun versionScore(id: String): Int {
        val r = Regex("""(\d+)\.(\d+)""").find(id) ?: return 0
        val (a, b) = r.destructured
        return (a.toIntOrNull() ?: 0) * 1000 + (b.toIntOrNull() ?: 0)
    }

    /* ------------------------- own web pipeline (free, no API quota) ------------------------- */

    private val PLAN_PROMPT = """
Read the screenshot. List up to 5 factual claims visible as TEXT (headlines, statements, numbers, dates, prices, event reports). For each, craft ONE precise web search query that a news search engine can answer (include names, numbers, dates). Output the value of "checks" through the JSON schema only — no prose.
    """.trimIndent()

    private val VERIFY_PROMPT = """
You are a fact-checker. The screenshot is attached. For each claim below, web search results were fetched; verify the claim strictly against those results, then give the verdict, a ≤25-word explanation naming the matched fact, a source domain, and a confidence (HIGH/MEDIUM/LOW). Do not use your own memory when the results do not cover it — use UNVERIFIED.
    """.trimIndent()

    private suspend fun planQueries(b64: String, key: String, model: String): ClaimsPlan {
        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", PLAN_PROMPT) }
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "image/jpeg")
                                put("data", b64)
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", planSchema())
            }
        }
        val bodyText = doPost("$BASE/$VERSION/models/$model:generateContent", key, payload)
        val resp = json.decodeFromString<GenerateResponse>(bodyText)
        resp.blockedReason?.let { throw GeminiHttpException(400, "Blocked by Gemini safety policy: $it") }
        val raw = resp.candidates.firstOrNull()?.content?.parts
            ?.mapNotNull { it.text }?.joinToString("")?.trim().orEmpty()
        return try { json.decodeFromString<ClaimsPlan>(cleanJsonText(raw)) } catch (t: Throwable) { ClaimsPlan() }
    }

    private suspend fun verifyFromResults(
        b64: String,
        key: String,
        model: String,
        pairs: List<Pair<ClaimPlan, List<WebSearch.Snippet>>>
    ): FactCheckResult = withContext(Dispatchers.IO) {
        val ctx = StringBuilder()
        pairs.forEach { (plan, snippets) ->
            ctx.appendLine("Claim: " + plan.claim)
            if (snippets.isEmpty()) ctx.appendLine("  (no search results)")
            snippets.take(4).forEach {
                ctx.appendLine("  · ${it.domain} — ${it.title}: ${it.snippet.take(280)}")
            }
            ctx.appendLine()
        }
        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", VERIFY_PROMPT + "\n\n" + "WEB SEARCH RESULTS:\n" + ctx) }
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "image/jpeg")
                                put("data", b64)
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", factCheckSchema())
            }
        }
        val bodyText = doPost("$BASE/$VERSION/models/$model:generateContent", key, payload)
        val resp = json.decodeFromString<GenerateResponse>(bodyText)
        resp.blockedReason?.let { throw GeminiHttpException(400, "Blocked by Gemini safety policy: $it") }
        val raw = resp.candidates.firstOrNull()?.content?.parts
            ?.mapNotNull { it.text }?.joinToString("")?.trim().orEmpty()
        val sources = pairs.asSequence()
            .flatMap { it.second.asSequence() }
            .map { GemSource(it.link, it.domain) }
            .filter { it.uri != null }
            .distinctBy { it.domain }
            .toList()
        val queries = pairs.map { it.first.query }.distinct()
        return@withContext try {
            json.decodeFromString<FactCheckResult>(cleanJsonText(raw))
                .copy(sources = sources, searched = queries)
        } catch (t: Throwable) {
            Log.w(TAG, "verify parse failed — raw text", t)
            FactCheckResult(claims = emptyList(), overall = raw, sources = sources, searched = queries)
        }
    }

    private fun planSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("checks") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("claim") { put("type", "string") }
                        putJsonObject("query") { put("type", "string") }
                    }
                    putJsonArray("required") { add("claim"); add("query") }
                }
            }
        }
        putJsonArray("required") { add("checks") }
    }

    private fun cleanJsonText(raw: String): String =
        raw.trim().trimStart('`').trim()
            .let { if (it.startsWith("json", ignoreCase = true)) it.substring(4).trim() else it }
            .let {
                val start = it.indexOf('{')
                val end = it.lastIndexOf('}')
                if (start >= 0 && end > start) it.substring(start, end + 1) else it
            }

    /* ------------------------- DuckDuckGo fetching -------------------------
     * Delegates to the shared WebSearch module (same logic as before, now
     * also used by the Groq pipeline).
     */

    private fun searchWeb(query: String): List<WebSearch.Snippet> = WebSearch.search(query)

    /* ------------------------- HTTP plumbing ------------------------- */

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private fun doGet(url: String, key: String): String {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("x-goog-api-key", key)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GeminiHttpException(resp.code, extractError(body, resp.code), body)
            return body
        }
    }

    private fun doPost(url: String, key: String, payload: JsonObject): String {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("x-goog-api-key", key)
            .header("Content-Type", "application/json")
            .post(okhttp3.RequestBody.create(null, payload.toString()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GeminiHttpException(resp.code, extractError(body, resp.code), body)
            return body
        }
    }

    private fun extractError(body: String, httpCode: Int): String {
        // Manual regex parse to avoid defining a DTO for the error shape.
        val m = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
        val msg = m?.groupValues?.get(1)?.trim()
        return if (!msg.isNullOrBlank()) msg
        else "HTTP $httpCode from Gemini API"
    }

    /** Retrying delay the server suggests (RetryInfo / "retry in Xs"); 0 = unknown. */
    private fun retryDelaySeconds(body: String): Int {
        val m = Regex("""retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s""").find(body)
            ?: Regex("""retry in\s+(\d+(?:\.\d+)?)\s*s""", RegexOption.IGNORE_CASE).find(body)
        return m?.groupValues?.get(1)?.toFloatOrNull()?.toInt() ?: 12
    }

    /**
     * Human-readable summary of a 429 body: which quota actually tripped
     * (QuotaFailure violations) and how long to wait (RetryInfo).
     */
    private fun quotaDetails(body: String): String? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val details = (root["error"]?.jsonObject?.get("details")) as? JsonArray ?: return null
        val out = details.mapNotNull { d ->
            val obj = d as? JsonObject ?: return@mapNotNull null
            val type = obj["@type"]?.jsonPrimitive?.content ?: ""
            when {
                type.contains("QuotaFailure") ->
                    obj["violations"]?.jsonArray?.joinToString("; ") { v ->
                        (v as? JsonObject)?.entries?.joinToString(", ") { (k, value) ->
                            k + "=" + (value.jsonPrimitive.contentOrNull ?: value.toString())
                        }.orEmpty()
                    }?.takeIf { it.isNotBlank() }
                type.contains("RetryInfo") ->
                    "wait " + (obj["retryDelay"]?.jsonPrimitive?.content ?: "a few seconds")
                else -> null
            }
        }
        out.takeIf { it.isNotEmpty() }?.joinToString(" | ")
    } catch (t: Throwable) {
        null
    }

    /* ------------------------- Request building ------------------------- */

    private fun checkPayload(
        b64: String,
        useSearch: Boolean,
        useSchema: Boolean
    ): JsonObject = buildJsonObject {
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject { put("text", PROMPT) }
                    addJsonObject {
                        putJsonObject("inlineData") {
                            put("mimeType", "image/jpeg")
                            put("data", b64)
                        }
                    }
                }
            }
        }
        putJsonObject("systemInstruction") {
            putJsonArray("parts") {
                addJsonObject {
                    put(
                        "text",
                        "Verify every news / current-events claim with the provided Google Search tool; never answer such claims from memory. Be concise and honest. Return answers exclusively through the given JSON schema."
                    )
                }
            }
        }
        // Sampling params deprecated on Gemini 3 — omit entirely.
        if (useSchema) {
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", factCheckSchema())
                // thinkingConfig intentionally omitted — Flash-Lite defaults to `minimal`.
            }
        }
        if (useSearch) {
            putJsonArray("tools") {
                // The Google Search grounding tool — this is what gives the
                // model live internet access. (Docs: {"googleSearch": {}}.)
                addJsonObject { putJsonObject("googleSearch") {} }
            }
        }
    }

    private fun factCheckSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("claims") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("claim") { put("type", "string") }
                        putJsonObject("verdict") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add("TRUE")
                                add("MOSTLY TRUE")
                                add("MISLEADING")
                                add("FALSE")
                                add("UNVERIFIED")
                            }
                        }
                        putJsonObject("explanation") { put("type", "string") }
                        putJsonObject("confidence") {
                            put("type", "string")
                            putJsonArray("enum") { add("HIGH"); add("MEDIUM"); add("LOW") }
                        }
                    }
                    putJsonArray("required") { add("claim"); add("verdict"); add("explanation") }
                }
            }
            putJsonObject("overall") { put("type", "string") }
            putJsonObject("uncertainty") { put("type", "string") }
        }
        putJsonArray("required") { add("claims"); add("overall") }
    }

    /* ------------------------- Response handling ------------------------- */

    private fun runCheck(
        b64: String,
        key: String,
        model: String,
        useSearch: Boolean,
        useSchema: Boolean,
        searchUnavailable: Boolean
    ): FactCheckResult {
        val note = if (searchUnavailable)
            "⚠ Live search was unavailable (Gemini quota) — this verdict may come from model memory and could be outdated."
        else null
        val url = "$BASE/$VERSION/models/$model:generateContent"
        Log.d(TAG, "POST $model search=$useSearch schema=$useSchema payloadBytes=${b64.length}")
        val bodyText = doPost(url, key, checkPayload(b64, useSearch, useSchema))
            .also { if (it.isBlank()) throw GeminiHttpException(500, "Empty response body") }

        val resp = json.decodeFromString<GenerateResponse>(bodyText)
        resp.blockedReason?.let { throw GeminiHttpException(400, "Blocked by Gemini safety policy: $it") }
        val cand = resp.candidates.firstOrNull()
            ?: throw GeminiHttpException(500, "Model returned no candidates")
        if (cand.finishReason in listOf("PROHIBITED_CONTENT", "BLOCKED", "OTHER")) {
            throw GeminiHttpException(400, "Gemini refused to answer (finishReason=${cand.finishReason})")
        }
        val rawText = cand.content?.parts?.mapNotNull { it.text }?.joinToString("")?.trim().orEmpty()

        val meta = cand.groundingMetadata
        val sources = buildList {
            meta?.chunks?.forEach { it.web?.let(::add) }
            meta?.searchEntryChunk?.forEach { it.web?.let(::add) }
        }.map { GemSource(it.uri, it.title) }
            .filter { it.uri != null || it.title != null }
            .distinctBy { it.domain }
        val queries = meta?.webSearchQueries ?: emptyList()
        Log.d(TAG, "grounding queries=$queries sources=${sources.size}")

        if (!useSchema) {
            // Plain-text fallback path: no JSON shape, show as-is.
            return FactCheckResult(
                claims = emptyList(),
                overall = if (note != null && rawText.isNotBlank()) note + "\n" + rawText else rawText,
                uncertainty = null,
                sources = sources,
                searched = queries
            )
        }

        val cleaned = rawText
            .trim()
            .trimStart('`')
            .trim()
            .let {
                if (it.startsWith("json", ignoreCase = true)) it.substring(4).trim() else it
            }
            .let { 
                val start = it.indexOf('{')
                val end = it.lastIndexOf('}')
                if (start >= 0 && end > start) it.substring(start, end + 1) else it
            }
        val parsed = try {
            json.decodeFromString<FactCheckResult>(cleaned)
        } catch (t: Throwable) {
            Log.w(TAG, "fact-check JSON parse failed — showing raw model text", t)
            return FactCheckResult(
                claims = emptyList(),
                overall = if (note != null && rawText.isNotBlank()) note + "\n" + rawText else rawText,
                uncertainty = null,
                sources = sources,
                searched = queries
            )
        }
        return parsed.copy(
            sources = (parsed.sources + sources).distinctBy { it.domain },
            searched = (parsed.searched + queries).distinct(),
            uncertainty = listOfNotNull(note, parsed.uncertainty)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" ")
        )
    }

    /* ------------------------- Bitmap helpers ------------------------- */

    private fun toBase64Jpeg(src: Bitmap): String {
        val maxEdge = maxOf(src.width, src.height)
        val scaled = if (maxEdge <= MAX_IMAGE_EDGE) src else {
            val scale = MAX_IMAGE_EDGE / maxEdge.toFloat()
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val bytes = out.toByteArray()
        if (scaled !== src) scaled.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
