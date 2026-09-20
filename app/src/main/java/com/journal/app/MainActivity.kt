package com.journal.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.journal.app.data.local.PreferenceManager
import com.journal.app.notification.NotificationHelper
import com.journal.app.ui.JournalViewModel
import com.journal.app.ui.UiMessage
import com.journal.app.ui.lock.AppLock
import com.journal.app.ui.lock.LockScreen
import com.journal.app.ui.screens.HomeScreen
import com.journal.app.ui.screens.InsightsScreen
import com.journal.app.ui.screens.NotificationConfigScreen
import com.journal.app.ui.screens.SettingsScreen
import com.journal.app.ui.theme.ElevatedSurface
import com.journal.app.ui.theme.MatteBackground
import com.journal.app.ui.theme.MindJournalTheme
import com.journal.app.ui.theme.TextSecondary

/**
 * A [FragmentActivity] rather than a bare `ComponentActivity` because `BiometricPrompt` requires
 * one; Compose is unaffected.
 */
class MainActivity : FragmentActivity() {

    /**
     * What the notification that launched (or re-launched) this activity wants to happen.
     *
     * [LaunchRequest.sequence] is bumped on every arrival rather than the request being a plain
     * value, because tapping a second notification while already on the requested screen must
     * still register — otherwise the second tap does nothing at all.
     */
    private var launchRequest by mutableStateOf(LaunchRequest())

    /** Locked state lives in the activity so it survives recomposition but not process death. */
    private var locked by mutableStateOf(false)
    private var authInFlight = false
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val preferences = PreferenceManager.getInstance(this)
        locked = preferences.appLockEnabled.value && AppLock.canLock(this)
        consumeLaunchRequest(intent)

        setContent {
            val viewModel: JournalViewModel = viewModel(factory = JournalViewModel.Factory)
            val accent by viewModel.accent.collectAsStateWithLifecycle()

            MindJournalTheme(accent = accent) {
                if (locked) {
                    LockScreen(onUnlock = ::promptForUnlock)
                } else {
                    JournalApp(
                        viewModel = viewModel,
                        launchRequest = launchRequest
                    )
                }
            }
        }

        if (locked) promptForUnlock()
    }

    /**
     * The activity is `singleTop`, so a notification tap on a running app arrives here rather
     * than through [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchRequest(intent)
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val preferences = PreferenceManager.getInstance(this)
        if (!preferences.appLockEnabled.value || !AppLock.canLock(this)) {
            locked = false
            return
        }
        // Re-lock after a real absence, not when returning from the file picker or the
        // biometric sheet itself — otherwise export/import becomes unusable.
        val away = SystemClock.elapsedRealtime() - backgroundedAt
        if (backgroundedAt != 0L && away > RELOCK_GRACE_MILLIS && !authInFlight) {
            locked = true
            promptForUnlock()
        }
    }

    /**
     * Turns a notification's extras into a one-shot navigation request.
     *
     * Every extra is removed once read, so a configuration change — which re-delivers the same
     * intent — does not replay the navigation and yank the user off whatever they moved to.
     */
    private fun consumeLaunchRequest(intent: Intent?) {
        intent ?: return
        val openComposer = intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_COMPOSER, false)
        val openInsights = intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_INSIGHTS, false)
        val openHome = intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_HOME, false)
        if (!openComposer && !openInsights && !openHome) return

        val prompt = intent.getStringExtra(NotificationHelper.EXTRA_PROMPT)
        intent.removeExtra(NotificationHelper.EXTRA_OPEN_COMPOSER)
        intent.removeExtra(NotificationHelper.EXTRA_OPEN_INSIGHTS)
        intent.removeExtra(NotificationHelper.EXTRA_OPEN_HOME)
        intent.removeExtra(NotificationHelper.EXTRA_PROMPT)

        launchRequest = LaunchRequest(
            sequence = launchRequest.sequence + 1,
            target = when {
                openComposer -> LaunchTarget.COMPOSER
                openInsights -> LaunchTarget.INSIGHTS
                else -> LaunchTarget.HOME
            },
            prompt = prompt
        )
    }

    private fun promptForUnlock() {
        if (authInFlight) return
        authInFlight = true
        AppLock.authenticate(
            activity = this,
            onSuccess = {
                authInFlight = false
                locked = false
            },
            onFailure = {
                authInFlight = false
                // Stay locked; the lock screen offers a retry button.
            }
        )
    }

    private companion object {
        /** Short trips out of the app (pickers, the system sheet) should not re-lock. */
        const val RELOCK_GRACE_MILLIS = 2_000L
    }
}

/** Where a notification tap should land. */
private enum class LaunchTarget { HOME, COMPOSER, INSIGHTS }

/**
 * A notification's navigation request. [sequence] `0` means "launched normally, do nothing".
 */
private data class LaunchRequest(
    val sequence: Int = 0,
    val target: LaunchTarget = LaunchTarget.HOME,
    val prompt: String? = null
)

/** The four destinations, each with its Georgian label and icon. */
private enum class Destination(
    val route: String,
    val labelRes: Int,
    val titleRes: Int,
    val icon: ImageVector
) {
    HOME("home", R.string.nav_home, R.string.home_title, Icons.Outlined.Subject),
    INSIGHTS("insights", R.string.nav_insights, R.string.insights_title, Icons.Outlined.AutoGraph),
    SCHEDULE("schedule", R.string.nav_schedule, R.string.config_title, Icons.Outlined.Notifications),
    SETTINGS("settings", R.string.nav_settings, R.string.settings_title, Icons.Outlined.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalApp(viewModel: JournalViewModel, launchRequest: LaunchRequest) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    val current = Destination.entries.firstOrNull { destination ->
        currentRoute?.hierarchy?.any { it.route == destination.route } == true
    } ?: Destination.HOME

    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()

    RequestNotificationPermission()

    // Opening the app from a notification lands where that notification pointed, never on
    // whichever tab was left open days ago. Keyed on the sequence so a repeat tap still fires.
    LaunchedEffect(launchRequest.sequence) {
        if (launchRequest.sequence == 0) return@LaunchedEffect

        val destination = when (launchRequest.target) {
            LaunchTarget.INSIGHTS -> Destination.INSIGHTS
            LaunchTarget.HOME, LaunchTarget.COMPOSER -> Destination.HOME
        }
        if (destination != current) {
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
                restoreState = false
            }
        }
        // The sheet lives in the view model precisely so it can be opened from out here, after
        // the tab switch, with the question the notification was asking already in place.
        if (launchRequest.target == LaunchTarget.COMPOSER) {
            viewModel.openComposer(launchRequest.prompt)
        }
    }

    // One-shot messages from the view model, resolved to Georgian strings here.
    val context = LocalContext.current
    LaunchedEffect(message) {
        val pending = message ?: return@LaunchedEffect
        val text = when (pending) {
            is UiMessage.Raw -> pending.text
            is UiMessage.Res ->
                if (pending.arg != null) {
                    context.getString(pending.id, pending.arg)
                } else {
                    context.getString(pending.id)
                }
        }
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Scaffold(
        containerColor = MatteBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(current.titleRes),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MatteBackground,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = ElevatedSurface,
                contentColor = TextSecondary
            ) {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == current,
                        onClick = {
                            if (destination != current) {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = stringResource(destination.labelRes)
                            )
                        },
                        label = { NavigationLabel(text = stringResource(destination.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = Destination.HOME.route,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Destination.HOME.route) { HomeScreen(viewModel = viewModel) }
                composable(Destination.INSIGHTS.route) {
                    InsightsScreen(
                        viewModel = viewModel,
                        // A tag in the tag cloud is a shortcut into the timeline, so tapping it
                        // has to take the user there — filtering a list they cannot see would
                        // look like the tap did nothing.
                        onOpenTag = { tag ->
                            viewModel.setTagFilter(tag)
                            navController.navigate(Destination.HOME.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
                composable(Destination.SCHEDULE.route) {
                    NotificationConfigScreen(viewModel = viewModel)
                }
                composable(Destination.SETTINGS.route) { SettingsScreen(viewModel = viewModel) }
            }
        }
    }
}

/**
 * A bottom-nav label that shrinks to fit instead of wrapping.
 *
 * `პარამეტრები` is eleven Mkhedruli glyphs in a cell that is a quarter of the screen minus the
 * bar's own padding. On a 1440px-wide S24 Ultra that fits at 11sp; on a narrower or
 * higher-density phone (the report was a OnePlus) it is a few pixels too wide, and Compose wraps
 * it — pushing the trailing `ი` onto a second line and making that one item taller than the
 * other three.
 *
 * Neither obvious fix is acceptable on its own: `maxLines = 1` with ellipsis turns the label into
 * `პარამეტრებ…`, and `softWrap = false` lets it run under the neighbouring item. Georgian has no
 * abbreviation convention that would let the string be shortened either.
 *
 * So the text is measured against the cell it was actually given and the largest size that fits
 * on one line is used, stepping down 0.5sp at a time to a floor of 8.5sp. Every other label is
 * short enough that it never leaves 11sp, so only the offending one changes — and it changes by
 * the smallest amount that device needs rather than by a constant guess. Compose 1.7 has no
 * `autoSize` parameter, which is why this is measured by hand.
 */
@Composable
private fun NavigationLabel(text: String) {
    val base = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val availablePx = constraints.maxWidth
        val style = remember(text, availablePx, base) {
            if (availablePx <= 0) {
                base
            } else {
                generateSequence(base.fontSize.value) { it - LABEL_STEP_SP }
                    .takeWhile { it >= LABEL_MIN_SP }
                    .map { base.copy(fontSize = it.sp) }
                    .firstOrNull { candidate ->
                        measurer.measure(
                            text = text,
                            style = candidate,
                            maxLines = 1,
                            softWrap = false
                        ).size.width <= availablePx
                    }
                    ?: base.copy(fontSize = LABEL_MIN_SP.sp)
            }
        }
        Text(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible
        )
    }
}

/** Shrink granularity — finer than this is invisible, coarser overshoots. */
private const val LABEL_STEP_SP = 0.5f

/** Below this the label stops being readable, so overflow is preferable to shrinking further. */
private const val LABEL_MIN_SP = 8.5f

/**
 * Asks for POST_NOTIFICATIONS once on first composition (Android 13+). Without it the whole
 * inline-reply flow is invisible, so it is requested up front rather than buried in settings.
 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Denial is handled by the warning card on the schedule screen. */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
