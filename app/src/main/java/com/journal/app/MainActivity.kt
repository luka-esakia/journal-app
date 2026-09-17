package com.journal.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.journal.app.ui.JournalViewModel
import com.journal.app.ui.UiMessage
import com.journal.app.ui.screens.HomeScreen
import com.journal.app.ui.screens.InsightsScreen
import com.journal.app.ui.screens.NotificationConfigScreen
import com.journal.app.ui.screens.SettingsScreen
import com.journal.app.ui.theme.ElevatedSurface
import com.journal.app.ui.theme.MatteBackground
import com.journal.app.ui.theme.MindJournalTheme
import com.journal.app.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: JournalViewModel = viewModel(factory = JournalViewModel.Factory)
            val accent by viewModel.accent.collectAsStateWithLifecycle()

            MindJournalTheme(accent = accent) {
                JournalApp(viewModel = viewModel)
            }
        }
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
private fun JournalApp(viewModel: JournalViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    val current = Destination.entries.firstOrNull { destination ->
        currentRoute?.hierarchy?.any { it.route == destination.route } == true
    } ?: Destination.HOME

    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()

    RequestNotificationPermission()

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
