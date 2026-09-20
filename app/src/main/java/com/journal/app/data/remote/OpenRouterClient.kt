package com.journal.app.data.remote

import android.util.Log
import com.journal.app.data.local.AiSettings
import com.journal.app.data.local.AiTask
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
            task = AiTask.TAGS,
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
            task = AiTask.REFLECTION,
            systemPrompt = REFLECTION_SYSTEM_PROMPT,
            userPrompt = user,
            maxTokens = 700,
            temperature = 0.6
        ).map { it.trim() }
    }

    /**
     * Generates one fresh Georgian journaling question, optionally slanted toward the topics the
     * user has actually been writing about.
     */
    suspend fun generatePrompt(
        settings: AiSettings,
        recentTags: List<String>
    ): Result<String> {
        val user = if (recentTags.isEmpty()) {
            "შექმენი ერთი ახალი შეკითხვა."
        } else {
            "ბოლო პერიოდის თემები: ${recentTags.take(8).joinToString(", ")}.\n" +
                "შექმენი ერთი ახალი შეკითხვა, რომელიც ამ თემებს ნაზად ეხება."
        }
        return chat(
            settings = settings,
            task = AiTask.PROMPT,
            systemPrompt = PROMPT_SYSTEM_PROMPT,
            userPrompt = user,
            maxTokens = 120,
            temperature = 1.0
        ).map { raw ->
            // Models like to wrap the question in quotes or prefix it with a bullet.
            raw.trim()
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .trim()
                .trim('"', '“', '”', '„', '-', '•', '*', ' ')
        }
    }

    // --------------------------------------------------------------- internals

    private suspend fun chat(
        settings: AiSettings,
        task: AiTask,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Double
    ): Result<String> = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank()) {
            return@withContext Result.failure(OpenRouterException("missing api key"))
        }

        val payload = JSONObject().apply {
            put("model", resolveModel(settings, task))
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put(
                "messages",
                JSONArray().apply {
                    put(message("system", systemPrompt))
                    put(message("user", userPrompt))
                }
            )
            put(
                "provider",
                JSONObject().apply {
                    // Zero-data-retention: refuse any provider that would log or train on this.
                    put("data_collection", "deny")
                    put("allow_fallbacks", true)
                    // Cheapest eligible provider rather than the fastest. Paired with the
                    // `:floor` slug below this is the documented low-priority/low-cost posture.
                    if (settings.lowPriority) put("sort", "price")
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
                    // Surface the provider's own message plus the status — a bad model slug
                    // returns 404 and is otherwise indistinguishable from a network failure.
                    val detail = buildString {
                        append(errorMessage(body) ?: "HTTP ${response.code}")
                        append(" (HTTP ")
                        append(response.code)
                        append(')')
                    }
                    Log.w(TAG, "OpenRouter rejected the request: $detail")
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

    /**
     * Picks the slug configured for [task] and applies the `:floor` variant suffix when
     * low-priority mode is on.
     *
     * Per OpenRouter's provider-routing docs, `:floor` is "a superset of setting `provider.sort`
     * to `price`" and additionally "makes flex service tier endpoints eligible" — i.e. the request
     * is served at lower priority for materially less money, while staying synchronous. (The
     * `:batch` variants are exactly half price but deliver asynchronously, which is why they are
     * not used here — a journal prompt cannot wait hours for its tags.)
     */
    internal fun resolveModel(settings: AiSettings, task: AiTask): String {
        val base = settings.modelFor(task).trim().ifBlank {
            when (task) {
                AiTask.REFLECTION -> AiSettings.DEFAULT_REFLECTION_MODEL
                AiTask.TAGS, AiTask.PROMPT -> AiSettings.DEFAULT_TAG_MODEL
            }
        }
        if (!settings.lowPriority) return base
        // Never stack variants: a slug the user typed with its own suffix is left alone.
        return if (base.contains(':')) base else "$base$FLOOR_SUFFIX"
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

    /**
     * Accepts `#a, #b` / newline separated / bare words and normalizes to `#tag`.
     *
     * The stoplist is a second line of defence: models still reach for filler tags that fit every
     * entry, and one useless tag on every card is more damaging than a missing one.
     */
    private fun parseTags(raw: String): List<String> = raw
        .split(',', '\n', ';')
        .map { it.trim().trim('.', '"', '\'', '-', '•', '*', '„', '“') }
        .filter { it.isNotEmpty() }
        .map { if (it.startsWith("#")) it else "#$it" }
        .map { it.replace(" ", "_") }
        .filter { it.length in 3..32 }
        // Reject multi-word tags that slipped past the prompt.
        .filter { it.count { ch -> ch == '_' } <= 1 }
        .filterNot { it.lowercase() in GENERIC_TAGS }
        .distinct()
        .take(MAX_TAGS)

    companion object {
        private const val TAG = "OpenRouterClient"
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private const val APP_REFERER = "https://github.com/mind-journal/android"
        private const val APP_TITLE = "Mind Journal"
        private const val MAX_TAGS = 5
        private const val MAX_INPUT_CHARS = 2000

        /** OpenRouter variant suffix: cheapest eligible route, flex service tier allowed. */
        private const val FLOOR_SUFFIX = ":floor"

        /** Tags so generic they carry no signal — dropped even if the model returns them. */
        private val GENERIC_TAGS = setOf(
            "#ცხოვრება", "#დღე", "#დღეს", "#ფიქრი", "#ფიქრები", "#გრძნობა", "#გრძნობები",
            "#საქმე", "#რამ", "#თემა", "#ჩანაწერი", "#დღიური", "#მომენტი", "#დრო",
            "#ემოცია", "#ემოციები", "#აზრი", "#აზრები", "#ზოგადი", "#სხვა"
        )

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Tagging is the part users see most, so the prompt is strict: one-word nouns, a
         * worked example of both a good and a bad answer, an explicit ban on restating the
         * question, and permission to return fewer tags (or none) rather than padding with
         * nonsense — which is what produced the junk tags in the first version.
         */
        private val TAG_SYSTEM_PROMPT = """
            შენ ხარ ქართული დღიურის თემატური ანალიზატორი.
            დაადგინე, რაზეა ჩანაწერი, და დააბრუნე 2-4 თეგი ქართულ ენაზე.

            თეგის ფორმა:
            — იწყება # სიმბოლოთი, ერთი სიტყვა, სახელობით ბრუნვაში (#მუშაობა, არა #ვმუშაობდი).
            — არსებითი სახელი ან მკაფიო ემოცია: #ძილი, #ოჯახი, #შფოთვა, #სპორტი, #ფული, #მეგობრები.

            აკრძალულია:
            — ზოგადი თეგები, რომლებიც ყველა ჩანაწერს მოერგება: #ცხოვრება, #დღე, #ფიქრი, #გრძნობა, #საქმე.
            — შეკითხვის გადათქმა ან ჩანაწერიდან სიტყვების პირდაპირ კოპირება.
            — საკუთარი სახელები, ადგილები, ბრენდები.
            — ზმნები, ზედსართავები, ფრაზები ორ სიტყვაზე მეტით.

            თუ ჩანაწერი ძალიან მოკლეა ან აზრობრივად ბუნდოვანია, დააბრუნე 1 თეგი ან ცარიელი პასუხი.
            სჯობს ნაკლები თეგი, ვიდრე გამოგონილი.

            მაგალითი 1:
            ჩანაწერი: „დღეს ისევ 3 საათზე დავიძინე და მთელი დღე გატეხილი ვიყავი. ხვალ უნდა შევცვალო.“
            პასუხი: #ძილი, #დაღლილობა

            მაგალითი 2 (ცუდი პასუხი): #დღეს, #ვიყავი, #ცხოვრება — ასე არ გააკეთო.

            პასუხში დააბრუნე მხოლოდ თეგები, მძიმით გამოყოფილი. სხვა ტექსტი აკრძალულია.
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

        private val PROMPT_SYSTEM_PROMPT = """
            შენ ქმნი დღიურის შეკითხვებს ქართულ ენაზე.
            წესები:
            — დააბრუნე ზუსტად ერთი შეკითხვა, ერთ სტრიქონზე, ბრჭყალების გარეშე.
            — შეკითხვა უნდა იყოს მოკლე (მაქსიმუმ 15 სიტყვა), ცოცხალი და სასაუბრო ტონით.
            — მიმართე მეორე პირში ან პირველ პირში, როგორც შინაგანი კითხვა.
            — არ გაიმეორო ბანალური ფორმულირებები („როგორ გრძნობ თავს?“).
            — არ დაამატო შესავალი, ნუმერაცია ან განმარტება.
        """.trimIndent()

        @Volatile
        private var instance: OpenRouterClient? = null

        fun getInstance(): OpenRouterClient =
            instance ?: synchronized(this) {
                instance ?: OpenRouterClient().also { instance = it }
            }
    }
}
