package com.journal.app.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Daily notification schedule configuration. Minutes are minute-of-day (0..1439). */
data class NotificationConfig(
    val enabled: Boolean = true,
    val startMinuteOfDay: Int = 10 * 60,
    val endMinuteOfDay: Int = 22 * 60,
    /** See [DEFAULT_DAILY_COUNT]. */
    val dailyCount: Int = DEFAULT_DAILY_COUNT
) {
    /** Window length in minutes; a window that ends before it starts wraps past midnight. */
    fun windowMinutes(): Int {
        val raw = endMinuteOfDay - startMinuteOfDay
        return if (raw > 0) raw else raw + MINUTES_PER_DAY
    }

    fun chunkMinutes(): Int =
        if (dailyCount <= 0) windowMinutes() else windowMinutes() / dailyCount

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        const val MIN_DAILY_COUNT = 1
        const val MAX_DAILY_COUNT = 12

        /**
         * Prompts per day on a fresh install (and after a preferences reset).
         *
         * Three, not five. A prompt the user ignores teaches them to ignore the next one, and the
         * 12-hour window divided three ways still leaves a four-hour gap — frequent enough to
         * catch a day's shape, sparse enough that each one is still worth reading.
         */
        const val DEFAULT_DAILY_COUNT = 3
    }
}

/** The three jobs the LLM is asked to do; each can run on its own model. */
enum class AiTask {
    /** Per-entry topic tags. Short, frequent, and cheap — this is the volume driver. */
    TAGS,

    /** The weekly reflection. Rare, long, and the one place model quality is visible. */
    REFLECTION,

    /** One fresh journaling question for the quick-add sheet. */
    PROMPT
}

/** One selectable model: an OpenRouter slug plus its list price, for display. */
data class ModelOption(
    val slug: String,
    val label: String,
    /** "$/Mtok in → out", shown so the cost tradeoff is visible at the point of choosing. */
    val price: String,
    val note: String? = null
)

/**
 * LLM settings. [apiKey] is only ever read from encrypted storage.
 *
 * ### Why two model slots
 * The two AI jobs have opposite cost profiles. Tagging runs on **every** entry — several times a
 * day, for a 20-token answer — so it is where essentially all the spend happens, and where a
 * cheap model is barely distinguishable from an expensive one. The weekly reflection runs **once
 * a week** for a few hundred tokens of prose the user actually reads, so it is the one call where
 * paying for a stronger model is both noticeable and nearly free. One shared slug forced a single
 * compromise on both; two slots let the cheap job be cheap and the visible job be good.
 */
data class AiSettings(
    val enabled: Boolean = true,
    val apiKey: String = "",
    /** Fast and cheap: per-entry tags, and the quick-add prompt. */
    val tagModel: String = DEFAULT_TAG_MODEL,
    /** Heavier: the weekly reflection. */
    val reflectionModel: String = DEFAULT_REFLECTION_MODEL,
    /**
     * Routes requests at the cheapest/flex service tier (`:floor` + `provider.sort = price`).
     * On by default: journal tagging is never latency-critical.
     */
    val lowPriority: Boolean = true,
    /** Extra slugs the user typed in, persisted so they stay in the picker. */
    val customModels: List<String> = emptyList()
) {
    fun isUsable(): Boolean = enabled && apiKey.isNotBlank()

    /**
     * The slug to send for [task].
     *
     * Prompt generation rides the tag model: it is the same shape of work — one short sentence,
     * many times over — and splitting it into a third user-visible setting would be three
     * dropdowns to explain a difference nobody can see.
     */
    fun modelFor(task: AiTask): String = when (task) {
        AiTask.TAGS, AiTask.PROMPT -> tagModel
        AiTask.REFLECTION -> reflectionModel
    }

    /** Curated list plus whatever the user added, de-duplicated, curated first. */
    fun availableModels(): List<ModelOption> {
        val curatedSlugs = CURATED_MODELS.map { it.slug }.toSet()
        val extras = customModels
            .filter { it.isNotBlank() && it !in curatedSlugs }
            .map { ModelOption(slug = it, label = it, price = "—") }
        return CURATED_MODELS + extras
    }

    companion object {
        /**
         * Tagging default. The cheapest slug on the list that still handles Mkhedruli reliably —
         * tags are one or two nouns, which is the least model-sensitive thing the app asks for.
         */
        const val DEFAULT_TAG_MODEL = "openai/gpt-4o-mini"

        /**
         * Reflection default. Verified against OpenRouter's live catalogue — the originally
         * shipped `anthropic/claude-3.5-haiku` is not a valid slug there, which is why every AI
         * action once failed with a 404.
         */
        const val DEFAULT_REFLECTION_MODEL = "anthropic/claude-haiku-4.5"

        /**
         * Short, inexpensive, and verified present in OpenRouter's catalogue. Ordering is by
         * Georgian-language quality rather than by price: Mkhedruli is low-resource, and the
         * cheapest open models degrade noticeably on it.
         */
        val CURATED_MODELS: List<ModelOption> = listOf(
            ModelOption(
                slug = "anthropic/claude-haiku-4.5",
                label = "Claude Haiku 4.5",
                price = "$1.00 → $5.00",
                note = "ქართულისთვის საუკეთესო ბალანსი"
            ),
            ModelOption(
                slug = "google/gemini-2.5-flash",
                label = "Gemini 2.5 Flash",
                price = "$0.30 → $2.50"
            ),
            ModelOption(
                slug = "openai/gpt-5-mini",
                label = "GPT-5 mini",
                price = "$0.25 → $2.00"
            ),
            ModelOption(
                slug = "openai/gpt-4o-mini",
                label = "GPT-4o mini",
                price = "$0.15 → $0.60",
                note = "ყველაზე იაფი სანდო ვარიანტი"
            ),
            // Replaces `mistralai/mistral-small-3.2-24b-instruct`: Mistral Small 4 (released
            // 2026-03-16) is the current small model and costs the same as GPT-4o mini.
            ModelOption(
                slug = "mistralai/mistral-small-2603",
                label = "Mistral Small 4",
                price = "$0.15 → $0.60",
                note = "იაფი და მრავალმოდალური; ქართული საშუალო"
            )
        )
    }
}

/** A generated weekly reflection plus the moment it was produced. */
data class WeeklyReflection(
    val text: String,
    val generatedAt: Long
)

/**
 * All persisted preferences, backed by [EncryptedSharedPreferences] so the OpenRouter API key is
 * encrypted at rest with a hardware-backed master key.
 *
 * Reads are exposed as [StateFlow]s so Compose and the scheduling code observe the same source of
 * truth; writes update the flow synchronously after committing to disk.
 */
class PreferenceManager private constructor(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    private val _accentKey = MutableStateFlow(prefs.getString(KEY_ACCENT, null) ?: DEFAULT_ACCENT)
    val accentKey: StateFlow<String> = _accentKey.asStateFlow()

    private val _notificationConfig = MutableStateFlow(readConfig())
    val notificationConfig: StateFlow<NotificationConfig> = _notificationConfig.asStateFlow()

    private val _aiSettings = MutableStateFlow(readAiSettings())
    val aiSettings: StateFlow<AiSettings> = _aiSettings.asStateFlow()


    private val _appLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_APP_LOCK, false))
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK, enabled).apply()
        _appLockEnabled.value = enabled
    }

    // ---------------------------------------------------------------- accent

    fun setAccentKey(key: String) {
        prefs.edit().putString(KEY_ACCENT, key).apply()
        _accentKey.value = key
    }

    // -------------------------------------------------------------- schedule

    fun currentConfig(): NotificationConfig = _notificationConfig.value

    fun saveConfig(config: NotificationConfig) {
        val sanitized = config.copy(
            dailyCount = config.dailyCount.coerceIn(
                NotificationConfig.MIN_DAILY_COUNT,
                NotificationConfig.MAX_DAILY_COUNT
            ),
            startMinuteOfDay = config.startMinuteOfDay.coerceIn(0, NotificationConfig.MINUTES_PER_DAY - 1),
            endMinuteOfDay = config.endMinuteOfDay.coerceIn(0, NotificationConfig.MINUTES_PER_DAY - 1)
        )
        prefs.edit()
            .putBoolean(KEY_NOTIFS_ENABLED, sanitized.enabled)
            .putInt(KEY_START_MINUTE, sanitized.startMinuteOfDay)
            .putInt(KEY_END_MINUTE, sanitized.endMinuteOfDay)
            .putInt(KEY_DAILY_COUNT, sanitized.dailyCount)
            .apply()
        _notificationConfig.value = sanitized
    }

    // ------------------------------------------------------------------- ai

    fun currentAiSettings(): AiSettings = _aiSettings.value

    /**
     * Commits the whole AI block in one write — the key, both models, and the routing flag.
     *
     * The previous per-field setters were only reachable from small inline affordances that were
     * easy to miss, so edits looked applied but were never persisted. One explicit save avoids
     * that entire class of bug.
     */
    fun saveAiSettings(
        apiKey: String,
        tagModel: String,
        reflectionModel: String,
        lowPriority: Boolean
    ) {
        val trimmedKey = apiKey.trim()
        val tag = tagModel.trim().ifEmpty { AiSettings.DEFAULT_TAG_MODEL }
        val reflection = reflectionModel.trim().ifEmpty { AiSettings.DEFAULT_REFLECTION_MODEL }
        prefs.edit()
            .putString(KEY_API_KEY, trimmedKey)
            .putString(KEY_TAG_MODEL, tag)
            .putString(KEY_REFLECTION_MODEL, reflection)
            // The single-slug key is now only read for migration; clear it so a later downgrade
            // cannot resurrect a stale choice over the two real ones.
            .remove(KEY_LEGACY_MODEL)
            .putBoolean(KEY_LOW_PRIORITY, lowPriority)
            .apply()
        _aiSettings.value = _aiSettings.value.copy(
            apiKey = trimmedKey,
            tagModel = tag,
            reflectionModel = reflection,
            lowPriority = lowPriority
        )
    }

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
        _aiSettings.value = _aiSettings.value.copy(apiKey = key.trim())
    }

    fun setAiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_ENABLED, enabled).apply()
        _aiSettings.value = _aiSettings.value.copy(enabled = enabled)
    }

    fun setLowPriority(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOW_PRIORITY, enabled).apply()
        _aiSettings.value = _aiSettings.value.copy(lowPriority = enabled)
    }

    /** Adds a hand-typed slug to the picker and persists it. No-op for duplicates. */
    fun addCustomModel(slug: String) {
        val value = slug.trim()
        if (value.isEmpty()) return
        val current = _aiSettings.value
        if (value in current.customModels ||
            AiSettings.CURATED_MODELS.any { it.slug == value }
        ) {
            return
        }
        val updated = current.customModels + value
        prefs.edit().putStringSet(KEY_CUSTOM_MODELS, updated.toSet()).apply()
        _aiSettings.value = current.copy(customModels = updated)
    }

    fun removeCustomModel(slug: String) {
        val current = _aiSettings.value
        val updated = current.customModels.filterNot { it == slug }
        if (updated.size == current.customModels.size) return

        // Never leave either picker pointing at a slug that no longer exists.
        val tag = if (current.tagModel == slug) AiSettings.DEFAULT_TAG_MODEL else current.tagModel
        val reflection = if (current.reflectionModel == slug) {
            AiSettings.DEFAULT_REFLECTION_MODEL
        } else {
            current.reflectionModel
        }
        prefs.edit()
            .putStringSet(KEY_CUSTOM_MODELS, updated.toSet())
            .putString(KEY_TAG_MODEL, tag)
            .putString(KEY_REFLECTION_MODEL, reflection)
            .apply()
        _aiSettings.value = current.copy(
            customModels = updated,
            tagModel = tag,
            reflectionModel = reflection
        )
    }

    // ----------------------------------------------------------- reflection

    /**
     * Hands over the pre-v3 reflection exactly once and forgets it.
     *
     * Reflections now live in the `reflections` Room table so each one keeps its own row and its
     * own source-entry links. This key is all that survives of the old single-slot storage; the
     * repository moves it into the table on first run and it never comes back.
     */
    fun consumeLegacyReflection(): WeeklyReflection? {
        val legacy = readReflection() ?: return null
        prefs.edit().remove(KEY_REFLECTION).remove(KEY_REFLECTION_AT).apply()
        return legacy
    }

    // --------------------------------------------------------------- reads

    private fun readConfig() = NotificationConfig(
        enabled = prefs.getBoolean(KEY_NOTIFS_ENABLED, true),
        startMinuteOfDay = prefs.getInt(KEY_START_MINUTE, 10 * 60),
        endMinuteOfDay = prefs.getInt(KEY_END_MINUTE, 22 * 60),
        dailyCount = prefs.getInt(KEY_DAILY_COUNT, NotificationConfig.DEFAULT_DAILY_COUNT)
    )

    /**
     * Reads both model slots, migrating the single pre-split `openrouter_model` key.
     *
     * A slug that was stored under the old key was an explicit choice, so it is carried into
     * *both* slots rather than being replaced by the new per-task defaults — silently moving
     * someone off the model they picked would be worse than not splitting at all. Only a user
     * who never touched the setting gets the new defaults.
     */
    private fun readAiSettings(): AiSettings {
        fun slug(key: String): String? =
            prefs.getString(key, null)
                ?.takeIf { it.isNotBlank() }
                // Retired slugs (notably the old `anthropic/claude-3.5-haiku`) would otherwise
                // persist forever and 404 on every request.
                ?.takeUnless { it in RETIRED_MODELS }

        val legacy = slug(KEY_LEGACY_MODEL)
        return AiSettings(
            enabled = prefs.getBoolean(KEY_AI_ENABLED, true),
            apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
            tagModel = slug(KEY_TAG_MODEL) ?: legacy ?: AiSettings.DEFAULT_TAG_MODEL,
            reflectionModel = slug(KEY_REFLECTION_MODEL)
                ?: legacy
                ?: AiSettings.DEFAULT_REFLECTION_MODEL,
            lowPriority = prefs.getBoolean(KEY_LOW_PRIORITY, true),
            customModels = prefs.getStringSet(KEY_CUSTOM_MODELS, null)
                ?.filter { it.isNotBlank() }
                ?.sorted()
                .orEmpty()
        )
    }

    private fun readReflection(): WeeklyReflection? {
        val text = prefs.getString(KEY_REFLECTION, null) ?: return null
        return WeeklyReflection(text, prefs.getLong(KEY_REFLECTION_AT, 0L))
    }

    companion object {
        private const val TAG = "PreferenceManager"
        private const val ENCRYPTED_FILE = "mind_journal_secure_prefs"
        private const val PLAIN_FILE = "mind_journal_prefs"

        private const val KEY_ACCENT = "accent_color"
        private const val KEY_NOTIFS_ENABLED = "notifications_enabled"
        private const val KEY_START_MINUTE = "window_start_minute"
        private const val KEY_END_MINUTE = "window_end_minute"
        private const val KEY_DAILY_COUNT = "daily_count"
        private const val KEY_API_KEY = "openrouter_api_key"

        /** Pre-split single model slug; read once for migration, then removed. */
        private const val KEY_LEGACY_MODEL = "openrouter_model"
        private const val KEY_TAG_MODEL = "openrouter_model_tags"
        private const val KEY_REFLECTION_MODEL = "openrouter_model_reflection"
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_LOW_PRIORITY = "openrouter_low_priority"
        private const val KEY_CUSTOM_MODELS = "openrouter_custom_models"

        /** Slugs that no longer resolve on OpenRouter; migrated to the default on read. */
        private val RETIRED_MODELS = setOf(
            "anthropic/claude-3.5-haiku",
            "anthropic/claude-3-5-haiku"
        )
        private const val KEY_APP_LOCK = "app_lock_enabled"
        private const val KEY_REFLECTION = "weekly_reflection"
        private const val KEY_REFLECTION_AT = "weekly_reflection_at"

        const val DEFAULT_ACCENT = "CYAN"

        @Volatile
        private var instance: PreferenceManager? = null

        fun getInstance(context: Context): PreferenceManager =
            instance ?: synchronized(this) {
                instance ?: PreferenceManager(context).also { instance = it }
            }

        private fun createPrefs(appContext: Context): SharedPreferences = try {
            val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // Keystore can be unavailable on a handful of OEM builds; degrade to app-private
            // storage rather than crashing on launch.
            Log.e(TAG, "Encrypted preferences unavailable, falling back to private prefs", t)
            appContext.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
        }
    }
}
