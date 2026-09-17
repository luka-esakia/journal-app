package com.journal.app.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.journal.app.MainActivity
import com.journal.app.R
import com.journal.app.data.local.NotificationConfig
import com.journal.app.data.local.PreferenceManager
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Spaced-random notification engine plus all notification construction.
 *
 * ### Why chunking
 * Picking N uniformly random times inside one window regularly produces clusters (two prompts
 * three minutes apart, then a six hour silence). Splitting the window into N equal chunks and
 * drawing exactly one time per chunk guarantees an even spread while keeping each individual
 * arrival unpredictable.
 */
object NotificationHelper {

    // ---------------------------------------------------------------- constants

    const val CHANNEL_ID = "journal_prompts_v1"

    const val ACTION_SHOW_PROMPT = "com.journal.app.action.SHOW_PROMPT"
    const val ACTION_PLAN_DAY = "com.journal.app.action.PLAN_DAY"
    const val ACTION_INLINE_REPLY = "com.journal.app.action.INLINE_REPLY"
    const val ACTION_REROLL_PROMPT = "com.journal.app.action.REROLL_PROMPT"

    const val EXTRA_PROMPT = "com.journal.app.extra.PROMPT"
    const val EXTRA_NOTIFICATION_ID = "com.journal.app.extra.NOTIFICATION_ID"
    const val EXTRA_SLOT_INDEX = "com.journal.app.extra.SLOT_INDEX"

    /** RemoteInput result key — must stay stable, replies are keyed by it. */
    const val KEY_TEXT_REPLY = "com.journal.app.KEY_TEXT_REPLY"

    /** Prompts inside one chunk are never allowed closer than this to the previous prompt. */
    const val MIN_GAP_MINUTES = 10

    /** Fraction of a chunk kept free at each edge, so neighbouring chunks cannot touch. */
    private const val EDGE_PADDING_RATIO = 0.12

    private const val TAG = "NotificationHelper"
    private const val NOTIFICATION_ID_BASE = 2_000
    private const val PROMPT_REQUEST_BASE = 1_000
    private const val REROLL_REQUEST_BASE = 50_000
    private const val PLANNER_REQUEST_CODE = 900
    private const val PLANNER_HOUR = 0
    private const val PLANNER_MINUTE = 5
    private const val SCHEDULE_LEAD_MILLIS = 60_000L
    private const val INEXACT_WINDOW_MILLIS = 5 * 60 * 1000L
    private const val SAVED_TIMEOUT_MILLIS = 25_000L
    private const val PLAN_WORK_NAME = "mind_journal_daily_plan"

    /** The prompt pool. Lives in [PromptBank]; kept here as an alias for call-site brevity. */
    val PROMPTS: List<String> get() = PromptBank.allPrompts

    /** One planned notification: when it fires and which prompt it carries. */
    data class ScheduledSlot(
        val index: Int,
        val triggerAtMillis: Long,
        val prompt: String
    )

    // ------------------------------------------------- spaced-random algorithm

    /**
     * Divides `[startMinuteOfDay, endMinuteOfDay)` into [count] equal chunks and draws one random
     * minute inside each chunk.
     *
     * A window whose end is not after its start is treated as wrapping past midnight
     * (e.g. 22:00 → 02:00). Results are strictly increasing and expressed as epoch millis relative
     * to [dayStartMillis], which must be local midnight of the target day.
     *
     * @param minGapMinutes minimum spacing between consecutive prompts. When a chunk is shorter
     *   than this gap the spacing cannot be honoured and the time is clamped to the chunk end.
     */
    fun calculateSpacedRandomTimes(
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        count: Int,
        dayStartMillis: Long,
        random: Random = Random.Default,
        minGapMinutes: Int = MIN_GAP_MINUTES
    ): List<Long> = calculateSpacedRandomMinutes(
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
        count = count,
        random = random,
        minGapMinutes = minGapMinutes
    ).map { minuteOfDay -> dayStartMillis + minuteOfDay * 60_000L }

    /**
     * Pure form of the algorithm: returns minute-of-day offsets measured from midnight of the
     * start day. Values can exceed 1440 when the window wraps past midnight.
     */
    fun calculateSpacedRandomMinutes(
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        count: Int,
        random: Random = Random.Default,
        minGapMinutes: Int = MIN_GAP_MINUTES
    ): List<Int> {
        if (count <= 0) return emptyList()

        val rawWindow = endMinuteOfDay - startMinuteOfDay
        val window = if (rawWindow > 0) rawWindow else rawWindow + NotificationConfig.MINUTES_PER_DAY
        if (window <= 0) return emptyList()

        val chunk = window.toDouble() / count
        // Keep the padding below half a chunk, otherwise the draw range would invert.
        val padding = (chunk * EDGE_PADDING_RATIO).coerceIn(0.0, (chunk / 2.0 - 0.5).coerceAtLeast(0.0))

        val times = ArrayList<Int>(count)
        var previous: Int? = null

        for (i in 0 until count) {
            val chunkStart = i * chunk
            val chunkEnd = chunkStart + chunk
            val low = chunkStart + padding
            val high = (chunkEnd - padding).coerceAtLeast(low)

            val drawn = if (high > low) low + random.nextDouble() * (high - low) else low
            var minute = startMinuteOfDay + drawn.roundToInt()

            val last = previous
            if (last != null && minute - last < minGapMinutes) {
                val pushed = last + minGapMinutes
                val chunkCeiling = startMinuteOfDay + chunkEnd.roundToInt()
                minute = pushed.coerceAtMost(chunkCeiling)
                // Never go backwards, even in a degenerate (chunk < gap) configuration.
                if (minute <= last) minute = last + 1
            }

            previous = minute
            times += minute
        }
        return times
    }

    /**
     * Builds the concrete slots for a day without touching [AlarmManager] — used both by
     * [planDay] and by the configuration screen preview.
     */
    fun buildSlots(
        config: NotificationConfig,
        dayStartMillis: Long,
        random: Random = Random.Default
    ): List<ScheduledSlot> {
        val times = calculateSpacedRandomTimes(
            startMinuteOfDay = config.startMinuteOfDay,
            endMinuteOfDay = config.endMinuteOfDay,
            count = config.dailyCount,
            dayStartMillis = dayStartMillis,
            random = random
        )
        // Shuffle once per day so a given day never repeats the same prompt twice in a row.
        val bag = PROMPTS.shuffled(random)
        return times.mapIndexed { index, trigger ->
            ScheduledSlot(
                index = index,
                triggerAtMillis = trigger,
                prompt = bag[index % bag.size]
            )
        }
    }

    // ------------------------------------------------------------- scheduling

    /**
     * Cancels any pending prompt alarms and schedules today's remaining slots, then re-arms the
     * planner for just after tomorrow's midnight.
     *
     * Idempotent: slots already in the past are skipped, so calling this repeatedly (boot, config
     * change, WorkManager safety net) never produces duplicate notifications.
     */
    fun planDay(
        context: Context,
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default
    ): List<ScheduledSlot> {
        val appContext = context.applicationContext
        val config = PreferenceManager.getInstance(appContext).currentConfig()

        cancelPromptAlarms(appContext)

        if (!config.enabled) {
            cancelPlanner(appContext)
            WorkManager.getInstance(appContext).cancelUniqueWork(PLAN_WORK_NAME)
            Log.i(TAG, "Scheduling disabled — all alarms cleared")
            return emptyList()
        }

        createChannel(appContext)
        ensureDailySafetyNet(appContext)

        val zone = ZoneId.systemDefault()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val slots = buildSlots(config, dayStart, random)

        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val scheduled = mutableListOf<ScheduledSlot>()

        slots.forEach { slot ->
            if (slot.triggerAtMillis < now + SCHEDULE_LEAD_MILLIS) return@forEach
            scheduleExact(appContext, alarmManager, slot)
            scheduled += slot
        }

        schedulePlanner(appContext, alarmManager, zone)
        Log.i(TAG, "Planned ${scheduled.size}/${slots.size} prompts for today")
        return scheduled
    }

    private fun scheduleExact(
        context: Context,
        alarmManager: AlarmManager?,
        slot: ScheduledSlot
    ) {
        alarmManager ?: return
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            action = ACTION_SHOW_PROMPT
            // Extras are not part of PendingIntent equality — the data URI keeps slots distinct.
            data = Uri.parse("mindjournal://prompt/${slot.index}")
            putExtra(EXTRA_PROMPT, slot.prompt)
            putExtra(EXTRA_SLOT_INDEX, slot.index)
            putExtra(EXTRA_NOTIFICATION_ID, NOTIFICATION_ID_BASE + slot.index)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            PROMPT_REQUEST_BASE + slot.index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (canBeExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                slot.triggerAtMillis,
                pending
            )
        } else {
            // Without the exact-alarm permission, an inexact window still keeps the spread.
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                slot.triggerAtMillis,
                INEXACT_WINDOW_MILLIS,
                pending
            )
        }
    }

    private fun schedulePlanner(context: Context, alarmManager: AlarmManager?, zone: ZoneId) {
        alarmManager ?: return
        val trigger = LocalDate.now(zone)
            .plusDays(1)
            .atTime(LocalTime.of(PLANNER_HOUR, PLANNER_MINUTE))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        // FLAG_UPDATE_CURRENT always creates, so this is non-null in practice; the elvis is only
        // here because plannerIntent is shared with the FLAG_NO_CREATE cancellation path.
        val pending = plannerIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, trigger, INEXACT_WINDOW_MILLIS, pending)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    /** WorkManager net: survives the odd OEM that drops alarms, re-plans twice a day. */
    private fun ensureDailySafetyNet(context: Context) {
        val request = PeriodicWorkRequestBuilder<DailyPlanWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PLAN_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelAll(context: Context) {
        val appContext = context.applicationContext
        cancelPromptAlarms(appContext)
        cancelPlanner(appContext)
        WorkManager.getInstance(appContext).cancelUniqueWork(PLAN_WORK_NAME)
    }

    private fun cancelPromptAlarms(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        for (index in 0 until NotificationConfig.MAX_DAILY_COUNT) {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_SHOW_PROMPT
                data = Uri.parse("mindjournal://prompt/$index")
            }
            val pending = PendingIntent.getBroadcast(
                context,
                PROMPT_REQUEST_BASE + index,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                alarmManager.cancel(pending)
                pending.cancel()
            }
        }
    }

    private fun cancelPlanner(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = plannerIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    private fun plannerIntent(context: Context, extraFlag: Int): PendingIntent? {
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            action = ACTION_PLAN_DAY
            data = Uri.parse("mindjournal://planner")
        }
        return PendingIntent.getBroadcast(
            context,
            PLANNER_REQUEST_CODE,
            intent,
            extraFlag or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ----------------------------------------------------------- notifications

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Posts a prompt notification carrying a [RemoteInput] action, so the entry can be typed and
     * saved from the banner or the lockscreen without ever opening the app, plus a
     * "🔄 შეცვლა" action that swaps the prompt in place.
     */
    fun showPromptNotification(context: Context, prompt: String, notificationId: Int) {
        createChannel(context)

        val replyLabel = context.getString(R.string.notif_reply_hint)
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(replyLabel)
            .build()

        val replyIntent = Intent(context, InlineReplyReceiver::class.java).apply {
            action = ACTION_INLINE_REPLY
            data = Uri.parse("mindjournal://reply/$notificationId")
            putExtra(EXTRA_PROMPT, prompt)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        // RemoteInput requires a mutable PendingIntent: the system fills in the typed text.
        val replyPending = PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag()
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.notif_reply_action),
            replyPending
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()

        // Reroll: swaps the prompt without opening the app, so a question that doesn't land
        // costs one tap instead of a dismissal.
        val rerollIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = ACTION_REROLL_PROMPT
            data = Uri.parse("mindjournal://reroll/$notificationId")
            putExtra(EXTRA_PROMPT, prompt)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val rerollPending = PendingIntent.getBroadcast(
            context,
            REROLL_REQUEST_BASE + notificationId,
            rerollIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rerollAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.notif_reroll_action),
            rerollPending
        )
            .setAllowGeneratedReplies(false)
            .build()

        val notification = baseBuilder(context, notificationId)
            .setContentTitle(prompt)
            .setContentText(context.getString(R.string.notif_reply_hint))
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt))
            .addAction(replyAction)
            .addAction(rerollAction)
            .build()

        post(context, notificationId, notification)
    }

    /**
     * Replaces the visible notification with a fresh prompt. Cancels first so the re-post
     * re-alerts rather than silently updating a notification the user is already looking past.
     */
    fun rerollPrompt(context: Context, notificationId: Int, currentPrompt: String?) {
        cancel(context, notificationId)
        val next = PromptBank.randomOtherThan(currentPrompt)
        showPromptNotification(context, next, notificationId)
    }

    /**
     * Replaces the prompt notification with a quiet "შენახულია ✓" confirmation that carries the
     * saved text as reply history and dismisses itself shortly after.
     */
    fun showSaved(context: Context, notificationId: Int, prompt: String?, reply: String) {
        createChannel(context)
        val title = prompt?.takeIf { it.isNotBlank() } ?: context.getString(R.string.notif_title)

        val notification = baseBuilder(context, notificationId)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_saved))
            .setRemoteInputHistory(arrayOf(reply))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(reply)
                    .setSummaryText(context.getString(R.string.notif_saved))
            )
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setTimeoutAfter(SAVED_TIMEOUT_MILLIS)
            .build()

        post(context, notificationId, notification)
    }

    /** Same shell as [showSaved] but reports that persisting the reply failed. */
    fun showSaveError(context: Context, notificationId: Int, prompt: String?, reply: String) {
        createChannel(context)
        val notification = baseBuilder(context, notificationId)
            .setContentTitle(prompt?.takeIf { it.isNotBlank() } ?: context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_error))
            .setStyle(NotificationCompat.BigTextStyle().bigText(reply))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        post(context, notificationId, notification)
    }

    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun baseBuilder(context: Context, notificationId: Int): NotificationCompat.Builder {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Public visibility is what makes inline reply usable straight from the lockscreen.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
    }

    private fun post(context: Context, notificationId: Int, notification: android.app.Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are disabled — skipping post")
            return
        }
        try {
            manager.notify(notificationId, notification)
        } catch (security: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the post.
            Log.w(TAG, "Missing notification permission", security)
        }
    }

    private fun mutabilityFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }
}

/**
 * Twice-daily safety net. Alarms can be dropped by aggressive OEM battery managers; re-running
 * [NotificationHelper.planDay] costs nothing and restores the day's remaining slots.
 */
class DailyPlanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            NotificationHelper.planDay(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            Log.e("DailyPlanWorker", "Re-planning failed", t)
            Result.retry()
        }
    }
}
