package com.local.factcheck.groq

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.local.factcheck.gemini.ClaimsPlan
import com.local.factcheck.gemini.FactCheckResult
import com.local.factcheck.gemini.GemSource
import com.local.factcheck.util.WebSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Groq REST client (OpenAI-compatible `chat/completions`), purpose-built for
 * the fact-check flow — the twin of [com.local.factcheck.gemini.GeminiClient]'s
 * free web pipeline:
 *
 *  1) model reads the screenshot (vision) and extracts claims + one web query each
 *  2) the app itself fetches DuckDuckGo results (free, no API quota)
 *  3) model verifies the claims against the fetched snippets
 *
 * No native web-grounding exists on Groq, so this pipeline is the only path —
 * verdicts never come from memory alone without at least attempted search.
 * Default model: `qwen/qwen3.8-27b` (~450+ t/s, multimodal, JSON mode).
 */
class GroqClient {

    companion object {
        private const val TAG = "GroqClient"
        private const val BASE = "https://api.groq.com/openai/v1"
        private const val MAX_IMAGE_EDGE = 1280
        private const val JPEG_QUALITY = 82

        private val PLAN_PROMPT = """
Read the screenshot. List up to 5 factual claims visible as TEXT (headlines, statements, numbers, dates, prices, event reports). Ignore decorative images, ads, navigation UI. For each claim craft ONE precise web search query a news search engine can answer (include names, numbers, dates).

Respond ONLY with a JSON object of shape:
{"checks":[{"claim":"...","query":"..."}]}
        """.trimIndent()

        private val VERIFY_PROMPT = """
You are a rigorous fact-checker. The screenshot is attached. Below are claims and the web search results fetched for each. Verify each claim strictly against those results: give the verdict, a <=25-word explanation naming the matched fact, a source domain, and confidence (HIGH/MEDIUM/LOW). If the results do not cover a claim, use UNVERIFIED — never answer from memory. Verdict values exactly: TRUE, MOSTLY TRUE, MISLEADING, FALSE, UNVERIFIED.

Respond ONLY with a JSON object of shape:
{"claims":[{"claim":"...","verdict":"TRUE","explanation":"...","confidence":"HIGH"}],"overall":"...","uncertainty":"..."}
        """.trimIndent()
    }

    class GroqHttpException(val httpCode: Int, message: String, val body: String = "") :
        Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /* ------------------------- Public API ------------------------- */

    suspend fun factCheck(jpeg: Bitmap, key: String, model: String): FactCheckResult =
        withContext(Dispatchers.IO) {
            val b64 = toBase64Jpeg(jpeg)

            try {
                val plan = planQueries(b64, key, model)
                val checks = plan.checks.filter { it.query.isNotBlank() }.take(4)
                if (checks.isEmpty()) {
                    throw GroqHttpException(500, "could not extract fact-checkable claims from the screen")
                }
                val snippets = coroutineScope {
                    checks.map { async(Dispatchers.IO) { WebSearch.search(it.query) } }.awaitAll()
                }
                val total = snippets.sumOf { it.size }
                Log.d(TAG, "pipeline: model=$model, ${checks.size} queries fetched, $total snippets")
                if (total == 0) {
                    throw GroqHttpException(
                        503,
                        "web search failed — the device may be offline, or DuckDuckGo blocked this network. Check internet and try again."
                    )
                }
                verifyFromResults(b64, key, model, checks.zip(snippets))
            } catch (e: GroqHttpException) {
                throw e
            } catch (t: Throwable) {
                throw GroqHttpException(500, "search pipeline failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }

    /** Cheap key validation: GET /models (no generation cost). */
    suspend fun listModelIds(key: String): List<String> = withContext(Dispatchers.IO) {
        val req = okhttp3.Request.Builder()
            .url("$BASE/models")
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GroqHttpException(resp.code, extractError(body, resp.code), body)
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"] as? kotlinx.serialization.json.JsonArray ?: emptyList()
            data.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
        }
    }

    /* ------------------------- Pipeline stages ------------------------- */

    private suspend fun planQueries(b64: String, key: String, model: String): ClaimsPlan =
        withContext(Dispatchers.IO) {
            val payload = chatPayload(
                model = model,
                system = "You extract fact-checkable claims from screenshots. Respond with JSON only.",
                image = b64,
                text = PLAN_PROMPT,
                jsonMode = true
            )
            val raw = chat(key, model, payload)
            try {
                json.decodeFromString<ClaimsPlan>(cleanJson(raw))
            } catch (t: Throwable) {
                Log.w(TAG, "plan JSON parse failed", t)
                ClaimsPlan()
            }
        }

    private suspend fun verifyFromResults(
        b64: String,
        key: String,
        model: String,
        pairs: List<Pair<com.local.factcheck.gemini.ClaimPlan, List<WebSearch.Snippet>>>
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
        val payload = chatPayload(
            model = model,
            system = "You verify claims strictly against provided web search results. Respond with JSON only.",
            image = b64,
            text = VERIFY_PROMPT + "\n\nWEB SEARCH RESULTS:\n" + ctx,
            jsonMode = true
        )
        val raw = chat(key, model, payload)
        val sources = pairs.asSequence()
            .flatMap { it.second.asSequence() }
            .map { GemSource(it.link, it.domain) }
            .filter { it.uri != null }
            .distinctBy { it.domain }
            .toList()
        val queries = pairs.map { it.first.query }.distinct()
        try {
            json.decodeFromString<FactCheckResult>(cleanJson(raw))
                .copy(sources = sources, searched = queries)
        } catch (t: Throwable) {
            Log.w(TAG, "verify parse failed — raw text", t)
            FactCheckResult(claims = emptyList(), overall = raw, sources = sources, searched = queries)
        }
    }

    /* ------------------------- Request building ------------------------- */

    private fun chatPayload(
        model: String,
        system: String,
        image: String,
        text: String,
        jsonMode: Boolean
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("temperature", 0.3)
        put("top_p", 0.9)
        put("max_completion_tokens", 1400)
        if (jsonMode) {
            putJsonObject("response_format") { put("type", "json_object") }
        }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:image/jpeg;base64,$image")
                        }
                    }
                }
            }
        }
    }

    private suspend fun chat(key: String, model: String, payload: JsonObject): String =
        withContext(Dispatchers.IO) {
            val body = payload.toString()
            var retried429 = false
            for (attempt in 1..3) {
                val req = okhttp3.Request.Builder()
                    .url("$BASE/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .post(okhttp3.RequestBody.create(null, body))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    val code = resp.code
                    Log.d(TAG, "chat code=$code model=$model bytes=${body.length}")
                    when {
                        respBody.isBlank() && !code.toString().startsWith('2') ->
                            throw GroqHttpException(code, "HTTP $code from Groq API")
                        code.toString().startsWith('2') && respBody.isBlank() ->
                            throw GroqHttpException(500, "Empty response from Groq API")
                    }
                    if (code.toString().startsWith('2')) return@withContext extractContent(respBody, model)

                    val msg = extractError(respBody, code)
                    // Rate limited: ONE short server-guided retry, then fail visibly.
                    val is429 = code == 429
                    if (is429 && !retried429) {
                        retried429 = true
                        val wait = Regex("try again in ([\\d.]+)(m?s)").find(msg?.lowercase() ?: "")?.let { m ->
                            val num = m.groupValues[1].toFloatOrNull() ?: 0f
                            if (m.groupValues[2] == "ms") (num / 1000f).toInt() else num.toInt()
                        } ?: 12
                        Log.w(TAG, "429 — retrying in ${wait.coerceIn(2, 15)}s")
                        delay(wait.coerceIn(2, 15) * 1000L)
                        return@use
                    }
                    throw GroqHttpException(code, msg ?: "HTTP $code from Groq API", respBody)
                }
            }
            throw GroqHttpException(429, "rate limited — try again in a moment")
        }

    private fun extractContent(respBody: String, model: String): String {
        val root = json.parseToJsonElement(respBody).jsonObject
        val choices = root["choices"] as? kotlinx.serialization.json.JsonArray ?: emptyList()
        val first = choices.firstOrNull() as? JsonObject
            ?: throw GroqHttpException(500, "Groq returned no choices")
        val message = first["message"] as? JsonObject
            ?: throw GroqHttpException(500, "Groq returned no message")
        // Thinking content (if any) sits in "reasoning"; the final answer is "content".
        val content = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        if (content.isBlank()) {
            val reasoning = message["reasoning"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            if (reasoning.isBlank()) throw GroqHttpException(500, "Groq returned an empty answer ($model)")
        }
        return content
    }

    private fun extractError(body: String, httpCode: Int): String {
        val m = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
        val msg = m?.groupValues?.get(1)?.trim()
        return if (!msg.isNullOrBlank()) msg else "HTTP $httpCode from Groq API"
    }

    private fun cleanJson(raw: String): String {
        var s = raw.trim()
        if (s.contains("```")) {
            val start = s.indexOf('{')
            val end = s.lastIndexOf('}')
            if (start >= 0 && end > start) s = s.substring(start, end + 1)
        }
        val s0 = s.indexOf('{')
        val s1 = s.lastIndexOf('}')
        if (s0 >= 0 && s1 > s0) s = s.substring(s0, s1 + 1)
        return s
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
