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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
     * Bumped every time the activity is (re)launched from a notification, so the UI can reset the
     * bottom-nav selection back to the timeline. A counter rather than a boolean: tapping a
     * second notification while already on Home must still register as a fresh request.
     */
    private val homeRequests = mutableIntStateOf(0)

    /** Locked state lives in the activity so it survives recomposition but not process death. */
    private var locked by mutableStateOf(false)
    private var authInFlight = false
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val preferences = PreferenceManager.getInstance(this)
        locked = preferences.appLockEnabled.value && AppLock.canLock(this)
        consumeHomeRequest(intent)

        setContent {
            val viewModel: JournalViewModel = viewModel(factory = JournalViewModel.Factory)
            val accent by viewModel.accent.collectAsStateWithLifecycle()

            MindJournalTheme(accent = accent) {
                if (locked) {
                    LockScreen(onUnlock = ::promptForUnlock)
                } else {
                    JournalApp(
                        viewModel = viewModel,
                        homeRequest = homeRequests.intValue
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
        consumeHomeRequest(intent)
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

    private fun consumeHomeRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(NotificationHelper.EXTRA_OPEN_HOME, false) == true) {
            homeRequests.intValue += 1
            // Clear it so a configuration change does not replay the navigation.
            intent.removeExtra(NotificationHelper.EXTRA_OPEN_HOME)
        }
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
private fun JournalApp(viewModel: JournalViewModel, homeRequest: Int) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    val current = Destination.entries.firstOrNull { destination ->
        currentRoute?.hierarchy?.any { it.route == destination.route } == true
    } ?: Destination.HOME

    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()

    RequestNotificationPermission()

    // Opening the app from a notification always lands on the timeline — the entry you just
    // wrote is the thing you want to see, not whichever tab you left open days ago.
    LaunchedEffect(homeRequest) {
        if (homeRequest > 0 && current != Destination.HOME) {
            navController.navigate(Destination.HOME.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
                restoreState = false
            }
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
                        label = {
                            Text(
                                text = stringResource(destination.labelRes),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
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
                composable(Destination.INSIGHTS.route) { InsightsScreen(viewModel = viewModel) }
                composable(Destination.SCHEDULE.route) {
                    NotificationConfigScreen(viewModel = viewModel)
                }
                composable(Destination.SETTINGS.route) { SettingsScreen(viewModel = viewModel) }
            }
        }
    }
}

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
