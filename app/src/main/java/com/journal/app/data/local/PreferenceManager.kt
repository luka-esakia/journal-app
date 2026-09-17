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
    val dailyCount: Int = 5
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
    }
}

/** LLM settings. [apiKey] is only ever read from encrypted storage. */
data class AiSettings(
    val enabled: Boolean = true,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL
) {
    fun isUsable(): Boolean = enabled && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "anthropic/claude-3.5-haiku"
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

    private val _weeklyReflection = MutableStateFlow(readReflection())
    val weeklyReflection: StateFlow<WeeklyReflection?> = _weeklyReflection.asStateFlow()

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

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
        _aiSettings.value = _aiSettings.value.copy(apiKey = key.trim())
    }

    fun saveModel(model: String) {
        val value = model.trim().ifEmpty { AiSettings.DEFAULT_MODEL }
        prefs.edit().putString(KEY_MODEL, value).apply()
        _aiSettings.value = _aiSettings.value.copy(model = value)
    }

    fun setAiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_ENABLED, enabled).apply()
        _aiSettings.value = _aiSettings.value.copy(enabled = enabled)
    }

    // ----------------------------------------------------------- reflection

    fun saveWeeklyReflection(text: String, generatedAt: Long) {
        prefs.edit()
            .putString(KEY_REFLECTION, text)
            .putLong(KEY_REFLECTION_AT, generatedAt)
            .apply()
        _weeklyReflection.value = WeeklyReflection(text, generatedAt)
    }

    fun clearWeeklyReflection() {
        prefs.edit().remove(KEY_REFLECTION).remove(KEY_REFLECTION_AT).apply()
        _weeklyReflection.value = null
    }

    // --------------------------------------------------------------- reads

    private fun readConfig() = NotificationConfig(
        enabled = prefs.getBoolean(KEY_NOTIFS_ENABLED, true),
        startMinuteOfDay = prefs.getInt(KEY_START_MINUTE, 10 * 60),
        endMinuteOfDay = prefs.getInt(KEY_END_MINUTE, 22 * 60),
        dailyCount = prefs.getInt(KEY_DAILY_COUNT, 5)
    )

    private fun readAiSettings() = AiSettings(
        enabled = prefs.getBoolean(KEY_AI_ENABLED, true),
        apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
        model = prefs.getString(KEY_MODEL, null) ?: AiSettings.DEFAULT_MODEL
    )

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
        private const val KEY_MODEL = "openrouter_model"
        private const val KEY_AI_ENABLED = "ai_enabled"
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
