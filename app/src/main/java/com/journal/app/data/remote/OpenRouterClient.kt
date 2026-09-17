package com.journal.app.data.remote

import android.util.Log
import com.journal.app.data.local.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown for every non-success answer from OpenRouter so callers can surface one message type. */
class OpenRouterException(message: String, val statusCode: Int = -1) : IOException(message)

/**
 * Minimal, privacy-first OpenRouter chat client.
 *
 * Every request carries `provider.data_collection = "deny"`, which instructs OpenRouter to route
 * only to providers that operate under zero-data-retention terms. Combined with `X-Title` and
 * `HTTP-Referer` for app attribution, this is the strictest posture the API exposes.
 */
class OpenRouterClient private constructor() {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ------------------------------------------------------------- public API

    /**
     * Extracts up to [MAX_TAGS] Georgian topic tags (e.g. `#მუშაობა`) for a single entry.
     */
    suspend fun extractTags(
        settings: AiSettings,
        content: String,
        prompt: String?
    ): Result<List<String>> {
        if (content.isBlank()) return Result.success(emptyList())
        val user = buildString {
            if (!prompt.isNullOrBlank()) {
                append("შეკითხვა: ")
                append(prompt)
                append('\n')
            }
            append("ჩანაწერი: ")
            append(content.take(MAX_INPUT_CHARS))
        }
        return chat(
            settings = settings,
            systemPrompt = TAG_SYSTEM_PROMPT,
            userPrompt = user,
            maxTokens = 120,
            temperature = 0.2
        ).map { parseTags(it) }
    }

    /**
     * Produces a short, warm weekly reflection over the supplied entries.
     *
     * @param dated pairs of (human readable date, entry text) in chronological order.
     */
    suspend fun weeklyReflection(
        settings: AiSettings,
        dated: List<Pair<String, String>>
    ): Result<String> {
        if (dated.isEmpty()) return Result.failure(OpenRouterException("no entries"))
        val user = buildString {
            append("ბოლო კვირის ჩანაწერები:\n\n")
            dated.forEach { (date, text) ->
                append("— [")
                append(date)
                append("] ")
                append(text.take(MAX_INPUT_CHARS))
                append('\n')
            }
        }
        return chat(
            settings = settings,
            systemPrompt = REFLECTION_SYSTEM_PROMPT,
            userPrompt = user,
            maxTokens = 700,
            temperature = 0.6
        ).map { it.trim() }
    }

    // --------------------------------------------------------------- internals

    private suspend fun chat(
        settings: AiSettings,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Double
    ): Result<String> = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank()) {
            return@withContext Result.failure(OpenRouterException("missing api key"))
        }

        val payload = JSONObject().apply {
            put("model", settings.model.ifBlank { AiSettings.DEFAULT_MODEL })
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put(
                "messages",
                JSONArray().apply {
                    put(message("system", systemPrompt))
                    put(message("user", userPrompt))
                }
            )
            // Zero-data-retention: refuse any provider that would log or train on the prompt.
            put(
                "provider",
                JSONObject().apply {
                    put("data_collection", "deny")
                    put("allow_fallbacks", true)
                }
            )
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", APP_REFERER)
            .addHeader("X-Title", APP_TITLE)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = errorMessage(body) ?: "HTTP ${response.code}"
                    return@withContext Result.failure(
                        OpenRouterException(detail, response.code)
                    )
                }
                val content = parseContent(body)
                if (content.isNullOrBlank()) {
                    Result.failure(OpenRouterException("empty completion"))
                } else {
                    Result.success(content)
                }
            }
        } catch (io: IOException) {
            Log.w(TAG, "OpenRouter request failed", io)
            Result.failure(OpenRouterException(io.message ?: "network error"))
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected OpenRouter failure", t)
            Result.failure(OpenRouterException(t.message ?: "unexpected error"))
        }
    }

    private fun message(role: String, content: String) = JSONObject().apply {
        put("role", role)
        put("content", content)
    }

    private fun parseContent(body: String): String? = try {
        JSONObject(body)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
    } catch (t: Throwable) {
        Log.w(TAG, "Malformed OpenRouter response", t)
        null
    }

    private fun errorMessage(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    /** Accepts `#a, #b` / newline separated / bare words and normalizes to `#tag`. */
    private fun parseTags(raw: String): List<String> = raw
        .split(',', '\n', ';')
        .map { it.trim().trim('.', '"', '\'', '-', '•', '*') }
        .filter { it.isNotEmpty() }
        .map { if (it.startsWith("#")) it else "#$it" }
        .map { it.replace(" ", "_") }
        .filter { it.length in 2..40 }
        .distinct()
        .take(MAX_TAGS)

    companion object {
        private const val TAG = "OpenRouterClient"
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private const val APP_REFERER = "https://github.com/mind-journal/android"
        private const val APP_TITLE = "Mind Journal"
        private const val MAX_TAGS = 5
        private const val MAX_INPUT_CHARS = 2000

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val TAG_SYSTEM_PROMPT = """
            შენ ხარ ქართული დღიურის სემანტიკური ანალიზატორი.
            მიღებული ჩანაწერიდან გამოყავი 2-დან 5-მდე მოკლე თემატური თეგი ქართულ ენაზე.
            წესები:
            — თეგი იწყება # სიმბოლოთი და შედგება 1-2 სიტყვისგან (მაგ. #მუშაობა, #დაღლილობა, #ოჯახი).
            — თეგები აღწერს თემას ან ემოციას, არა კონკრეტულ სახელებს.
            — პასუხში დააბრუნე მხოლოდ თეგები, გამოყოფილი მძიმით. სხვა ტექსტი აკრძალულია.
        """.trimIndent()

        private val REFLECTION_SYSTEM_PROMPT = """
            შენ ხარ მშვიდი და ყურადღებიანი ქართული დღიურის თანამგზავრი.
            მიღებული კვირის ჩანაწერებზე დაყრდნობით დაწერე მოკლე რეფლექსია ქართულ ენაზე (150-220 სიტყვა):
            1. მთავარი თემები, რომლებიც კვირაში განმეორდა.
            2. ენერგიის მატებისა და კლების შესამჩნევი პატერნები.
            3. ერთი ნაზი, კონკრეტული შენიშვნა მომავალი კვირისთვის.
            წესები: არ დაასვამ დიაგნოზს, არ მოუწოდებ ცვლილებას მკაცრი ტონით, არ იმეორებ ჩანაწერებს პირდაპირ.
            ტონი: თბილი, პატივისცემით, მეორე პირში.
        """.trimIndent()

        @Volatile
        private var instance: OpenRouterClient? = null

        fun getInstance(): OpenRouterClient =
            instance ?: synchronized(this) {
                instance ?: OpenRouterClient().also { instance = it }
            }
    }
}
