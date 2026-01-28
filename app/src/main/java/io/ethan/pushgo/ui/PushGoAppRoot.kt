package io.ethan.pushgo.ui

import android.content.Intent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SpeakerPhone
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.ui.screens.MessageDetailScreen
import io.ethan.pushgo.ui.screens.MessageListScreen
import io.ethan.pushgo.ui.screens.PushScreen
import io.ethan.pushgo.ui.screens.SettingsScreen
import io.ethan.pushgo.ui.theme.DarkNavigationBar
import io.ethan.pushgo.ui.theme.LightNavigationBar

private data class BottomItem(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

@Composable
fun PushGoAppRoot(
    container: AppContainer,
    startIntent: Intent?,
    useDarkTheme: Boolean,
) {
    val navController = rememberNavController()
    val factory = remember(container) { PushGoViewModelFactory(container) }
    var selectedMessageId by remember { mutableStateOf<String?>(null) }
    val items = listOf(
        BottomItem("messages", R.string.tab_messages, Icons.AutoMirrored.Filled.Chat),
        BottomItem("push", R.string.tab_push, Icons.Outlined.Notifications),
        BottomItem("settings", R.string.tab_settings, Icons.Outlined.Settings),
    )
    val unreadCount by container.messageRepository.observeUnreadCount().collectAsState(initial = 0)
    val badgeText = remember(unreadCount) { if (unreadCount > 99) "99+" else unreadCount.toString() }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val showBottomBar = items.any { it.route == currentRoute }
    val navBarColor = if (useDarkTheme) DarkNavigationBar else LightNavigationBar

    LaunchedEffect(startIntent) {
        val messageId = startIntent?.getStringExtra(NotificationHelper.EXTRA_MESSAGE_ID)
        if (!messageId.isNullOrBlank()) {
            selectedMessageId = messageId
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = navBarColor,
                ) {
                    items.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateTo(navController, item.route) },
                            icon = {
                                if (item.route == "messages" && unreadCount > 0) {
                                    BadgedBox(badge = { Badge { Text(badgeText) } }) {
                                        androidx.compose.material3.Icon(
                                            item.icon,
                                            contentDescription = stringResource(item.labelRes)
                                        )
                                    }
                                } else {
                                    androidx.compose.material3.Icon(
                                        item.icon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                }
                            },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        PushGoNavHost(
            navController = navController,
            container = container,
            factory = factory,
            padding = padding,
            onMessageClick = { selectedMessageId = it }
        )
        
        if (selectedMessageId != null) {
            MessageDetailScreen(
                messageId = selectedMessageId!!,
                repository = container.messageRepository,
                stateCoordinator = container.messageStateCoordinator,
                channelRepository = container.channelRepository,
                onDismiss = { selectedMessageId = null }
            )
        }
    }
}

@Composable
private fun PushGoNavHost(
    navController: NavHostController,
    container: AppContainer,
    factory: PushGoViewModelFactory,
    padding: PaddingValues,
    onMessageClick: (String) -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = "messages",
        modifier = Modifier.padding(padding),
    ) {
        composable("messages") {
            MessageListScreen(
                navController = navController,
                container = container,
                factory = factory,
                onMessageClick = onMessageClick,
            )
        }
        composable("push") {
            PushScreen(container = container, navController = navController)
        }
        composable("settings") {
            SettingsScreen(
                navController = navController,
                factory = factory
            )
        }
        composable("settings/ringtone") {
            io.ethan.pushgo.ui.screens.RingtoneListScreen(
                navController = navController,
                factory = factory
            )
        }
        composable("settings/decryption") {
            val settingsViewModel: io.ethan.pushgo.ui.viewmodel.SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
            io.ethan.pushgo.ui.screens.MessageDecryptionScreen(
                navController = navController,
                factory = factory,
                viewModel = settingsViewModel
            )
        }
        composable("settings/channels") {
            val settingsViewModel: io.ethan.pushgo.ui.viewmodel.SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
            io.ethan.pushgo.ui.screens.ChannelListScreen(
                navController = navController,
                factory = factory,
                viewModel = settingsViewModel
            )
        }
    }
}

private fun navigateTo(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
