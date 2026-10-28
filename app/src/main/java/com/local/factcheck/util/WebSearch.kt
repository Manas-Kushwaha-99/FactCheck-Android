package com.local.factcheck.util

import android.util.Log

/**
 * App-owned web search so verdicts use the LIVE web without any AI-provider
 * search quota. Fetches DuckDuckGo result pages over plain HTTPS (free, no
 * API key) and regex-parses title/link/snippet.
 *
 * Shared by the Gemini and Groq pipelines so both providers verify against
 * identical, battle-tested search code.
 */
object WebSearch {

    private const val TAG = "FactCheckWeb"

    data class Snippet(val title: String, val link: String, val snippet: String) {
        val domain: String get() = runCatching {
            java.net.URI(link).host?.removePrefix("www.")
        }.getOrNull() ?: link
    }

    private val http: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** Neutral, desktop-ish UA (DDG serves results without JS to this one). */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    fun search(query: String): List<Snippet> {
        if (query.isBlank()) return emptyList()
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        for (url in listOf(
            "https://html.duckduckgo.com/html/?q=$q",
            "https://lite.duckduckgo.com/lite/?q=$q"
        )) {
            try {
                val results = parse(getHtml(url))
                if (results.isNotEmpty()) return results
            } catch (t: Throwable) {
                Log.w(TAG, "search fetch failed $url: ${t.message}")
            }
        }
        return emptyList()
    }

    private fun getHtml(url: String): String {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun parse(html: String): List<Snippet> {
        fun strip(s: String): String = s
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

        val anchors = Regex(
            """<a[^>]*class="[^"]*(?:result__a|result-link)[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html).toList()
        val snippets = Regex(
            """<[^>]*class="[^"]*(?:result__snippet|result-snippet)[^"]*"[^>]*>(.*?)</""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html).toList()

        return anchors.mapIndexedNotNull { i, m ->
            var href = m.groupValues[1]
            Regex("uddg=([^&]+)").find(href)?.groupValues?.get(1)?.let { uddg ->
                href = runCatching { java.net.URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(href)
            }
            if (href.startsWith("//")) href = "https:" + href
            val title = strip(m.groupValues[2]).take(180)
            if (title.isBlank()) return@mapIndexedNotNull null
            val snip = snippets.getOrNull(i)?.let { strip(it.groupValues[1]).take(320) } ?: ""
            Snippet(title, href, snip)
        }.take(5)
    }
}
