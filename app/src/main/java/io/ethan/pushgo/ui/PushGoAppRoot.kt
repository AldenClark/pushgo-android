package io.ethan.pushgo.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.ethan.pushgo.R
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.AutomationSnapshot
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.update.UpdateNotifier
import io.ethan.pushgo.ui.screens.ChannelListScreen
import io.ethan.pushgo.ui.screens.EventListScreen
import io.ethan.pushgo.ui.screens.MessageDetailScreen
import io.ethan.pushgo.ui.screens.MessageListScreen
import io.ethan.pushgo.ui.screens.PushGoAlertDialog
import io.ethan.pushgo.ui.screens.SettingsScreen
import io.ethan.pushgo.ui.screens.ThingListScreen
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.io.File

@Serializable object MessagesRoute
@Serializable object EventsRoute
@Serializable object ThingsRoute
@Serializable object ChannelsRoute
@Serializable object SettingsRoute
@Serializable object DecryptionRoute
@Serializable object ConnectionDiagnosisRoute

private data class BottomItem(
    val route: Any,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun PushGoAppRoot(
    container: AppContainer,
    startIntent: Intent?,
    useDarkTheme: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()
    val factory = remember(container) { PushGoViewModelFactory(container) }
    val automationController = remember(container) { container.automationController }
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
    
    var selectedMessageId by remember { mutableStateOf<String?>(null) }
    var pendingEventIdToOpen by remember { mutableStateOf<String?>(null) }
    var pendingThingIdToOpen by remember { mutableStateOf<String?>(null) }
    var openedEntityType by remember { mutableStateOf<String?>(null) }
    var openedEntityId by remember { mutableStateOf<String?>(null) }
    var messageBatchMode by remember { mutableStateOf(false) }
    var eventBatchMode by remember { mutableStateOf(false) }
    var thingBatchMode by remember { mutableStateOf(false) }
    var messageScrollToUnreadToken by remember { mutableLongStateOf(0L) }
    var messageScrollToTopToken by remember { mutableLongStateOf(0L) }
    var eventScrollToTopToken by remember { mutableLongStateOf(0L) }
    var thingScrollToTopToken by remember { mutableLongStateOf(0L) }
    var bottomBarVisible by remember { mutableStateOf(true) }
    var pendingMessageReselectJob by remember { mutableStateOf<Job?>(null) }
    var lastReselectedRoute by remember { mutableStateOf<TopLevelRoute?>(null) }
    var lastReselectTimestampMs by remember { mutableLongStateOf(0L) }
    var autoUpdateDialogVisible by rememberSaveable { mutableStateOf(false) }
    var autoUpdatePromptedVersionCode by rememberSaveable { mutableIntStateOf(-1) }
    var autoUpdateWasInstalling by remember { mutableStateOf(false) }
    val appScope = rememberCoroutineScope()

    val unreadCount by container.messageRepository.observeUnreadCount().collectAsStateWithLifecycle(initialValue = 0)
    val eventCount by container.entityRepository.observeEventCount().collectAsStateWithLifecycle(initialValue = 0)
    val eventRefreshToken by container.entityRepository.observeEventRefreshToken().collectAsStateWithLifecycle(initialValue = 0L)
    val thingCount by container.entityRepository.observeThingCount().collectAsStateWithLifecycle(initialValue = 0)
    val thingRefreshToken by container.entityRepository.observeThingRefreshToken().collectAsStateWithLifecycle(initialValue = 0L)

    val isMessagePageEnabled by container.settingsRepository.messagePageEnabledFlow
        .collectAsStateWithLifecycle(initialValue = container.settingsRepository.getCachedMessagePageEnabled())
    val isEventPageEnabled by container.settingsRepository.eventPageEnabledFlow
        .collectAsStateWithLifecycle(initialValue = container.settingsRepository.getCachedEventPageEnabled())
    val isThingPageEnabled by container.settingsRepository.thingPageEnabledFlow
        .collectAsStateWithLifecycle(initialValue = container.settingsRepository.getCachedThingPageEnabled())

    val items = buildList {
        if (isMessagePageEnabled) add(BottomItem(MessagesRoute, stringResource(R.string.tab_messages), Icons.AutoMirrored.Filled.Chat))
        if (isEventPageEnabled) add(BottomItem(EventsRoute, stringResource(R.string.label_send_type_event), Icons.AutoMirrored.Outlined.EventNote))
        if (isThingPageEnabled) add(BottomItem(ThingsRoute, stringResource(R.string.label_send_type_thing), Icons.Outlined.Memory))
        add(BottomItem(ChannelsRoute, stringResource(R.string.section_channels), Icons.Outlined.Group))
    }

    val initialRoute: Any = remember { items.firstOrNull()?.route ?: ChannelsRoute }
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    val isOnSettingsRoute = currentRoute.matchesSettingsRoute()

    val currentTopLevelRoute = currentRoute.topLevelRoute()
    val showBottomBar = currentTopLevelRoute != null
    val hideBottomBarForBatchMode = when (currentTopLevelRoute) {
        TopLevelRoute.MESSAGES -> messageBatchMode
        TopLevelRoute.EVENTS -> eventBatchMode
        TopLevelRoute.THINGS -> thingBatchMode
        else -> false
    }

    fun handleTopLevelReselection(route: TopLevelRoute) {
        val now = SystemClock.elapsedRealtime()
        val isDoubleTap = lastReselectedRoute == route && now - lastReselectTimestampMs <= 320L

        if (isDoubleTap) {
            pendingMessageReselectJob?.cancel()
            pendingMessageReselectJob = null
            lastReselectedRoute = null
            lastReselectTimestampMs = 0L
            when (route) {
                TopLevelRoute.MESSAGES -> messageScrollToTopToken += 1
                TopLevelRoute.EVENTS -> eventScrollToTopToken += 1
                TopLevelRoute.THINGS -> thingScrollToTopToken += 1
                TopLevelRoute.CHANNELS -> Unit
            }
            return
        }

        pendingMessageReselectJob?.cancel()
        pendingMessageReselectJob = null
        lastReselectedRoute = route
        lastReselectTimestampMs = now

        if (route == TopLevelRoute.MESSAGES) {
            val tokenAtSchedule = messageScrollToUnreadToken
            pendingMessageReselectJob = appScope.launch {
                delay(280L)
                if (lastReselectedRoute == TopLevelRoute.MESSAGES && lastReselectTimestampMs == now && messageScrollToUnreadToken == tokenAtSchedule) {
                    messageScrollToUnreadToken += 1
                    lastReselectedRoute = null
                    lastReselectTimestampMs = 0L
                }
            }
        }
    }

    LaunchedEffect(currentTopLevelRoute) {
        bottomBarVisible = true
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                settingsViewModel.refreshUpdateState(manual = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        settingsViewModel.refreshUpdateState(manual = false)
    }

    LaunchedEffect(settingsViewModel.availableUpdate?.versionCode, isOnSettingsRoute) {
        val candidate = settingsViewModel.availableUpdate ?: run {
            autoUpdateDialogVisible = false
            return@LaunchedEffect
        }
        if (!isOnSettingsRoute && autoUpdatePromptedVersionCode != candidate.versionCode) {
            autoUpdatePromptedVersionCode = candidate.versionCode
            autoUpdateDialogVisible = true
        }
    }

    LaunchedEffect(settingsViewModel.isInstallingUpdate) {
        if (autoUpdateWasInstalling && !settingsViewModel.isInstallingUpdate) {
            autoUpdateDialogVisible = false
        }
        autoUpdateWasInstalling = settingsViewModel.isInstallingUpdate
    }

    LaunchedEffect(startIntent) {
        val openSettings = startIntent?.getBooleanExtra(UpdateNotifier.EXTRA_OPEN_SETTINGS, false) == true
        val entityId = startIntent?.getStringExtra(NotificationHelper.EXTRA_ENTITY_ID)?.takeIf { it.isNotEmpty() }
        val entityType = startIntent?.getStringExtra(NotificationHelper.EXTRA_ENTITY_TYPE)?.lowercase()
        if (openSettings) {
            navController.navigate(SettingsRoute) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
        } else if (entityType == "event" && entityId != null) {
            navController.navigate(EventsRoute) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
            pendingEventIdToOpen = entityId
        } else if (entityType == "thing" && entityId != null) {
            navController.navigate(ThingsRoute) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
            pendingThingIdToOpen = entityId
        } else {
            selectedMessageId = startIntent?.getStringExtra(NotificationHelper.EXTRA_MESSAGE_ID)
        }
    }

    LaunchedEffect(currentRoute, selectedMessageId, pendingEventIdToOpen, pendingThingIdToOpen) {
        while (true) {
            val snapshot = automationController.snapshotForOpenedMessage(selectedMessageId)
            val activeTab = currentTopLevelRoute?.spec?.wireValue
                ?: currentRoute
                ?: TopLevelRoute.MESSAGES.spec.wireValue
            PushGoAutomation.publishState(buildAutomationState(activeTab, currentRoute, selectedMessageId, pendingEventIdToOpen, pendingThingIdToOpen, snapshot, openedEntityType, openedEntityId))
            delay(500)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            val uiColors = PushGoThemeExtras.colors
            AnimatedVisibility(
                // REMOVED selectedMessageId == null to keep TabBar visible when sheet is open
                visible = showBottomBar && !hideBottomBarForBatchMode && bottomBarVisible,
                enter = expandVertically(
                    expandFrom = Alignment.Bottom,
                    clip = false,
                    animationSpec = tween(
                        durationMillis = 320,
                        easing = LinearOutSlowInEasing,
                    )
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = 260,
                        easing = LinearOutSlowInEasing,
                    )
                ),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Bottom,
                    clip = false,
                    animationSpec = tween(
                        durationMillis = 280,
                        easing = FastOutSlowInEasing,
                    )
                ) + fadeOut(
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing,
                    )
                )
            ) {
                NavigationBar(
                    modifier = Modifier.testTag("nav.bottom").drawBehind {
                        drawLine(color = uiColors.dividerStrong, start = Offset(0f, 0f), end = Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx())
                    },
                    containerColor = uiColors.navigationBarBackground,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars
                ) {
                    items.forEach { item ->
                        val selected = currentRoute.matches(item)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                val topLevelRoute = item.topLevelRoute()
                                if (selected && topLevelRoute != null) {
                                    handleTopLevelReselection(topLevelRoute)
                                } else if (!selected) {
                                    pendingMessageReselectJob?.cancel()
                                    pendingMessageReselectJob = null
                                    lastReselectedRoute = null
                                    lastReselectTimestampMs = 0L
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                if (item.route is MessagesRoute && unreadCount > 0) {
                                    BadgedBox(
                                        badge = {
                                            PushGoUnreadBadge(
                                                text = if (unreadCount > 99) "99+" else unreadCount.toString()
                                            )
                                        }
                                    ) {
                                        Icon(item.icon, contentDescription = item.label)
                                    }
                                } else {
                                    Icon(item.icon, contentDescription = item.label)
                                }
                            },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = uiColors.accentPrimary,
                                selectedTextColor = uiColors.accentPrimary,
                                indicatorColor = uiColors.selectionFill,
                                unselectedIconColor = uiColors.iconMuted,
                                unselectedTextColor = uiColors.textSecondary,
                                disabledIconColor = uiColors.iconMuted,
                                disabledTextColor = uiColors.textSecondary
                            )
                        )
                    }
                }
            }
        },
    ) { padding ->
        PushGoNavHost(
            navController = navController, container = container, factory = factory, settingsViewModel = settingsViewModel,
            initialRoute = initialRoute, padding = padding,
            onMessageClick = { selectedMessageId = it }, onMessageBatchModeChanged = { messageBatchMode = it },
            onMessageBottomBarVisibilityChanged = { bottomBarVisible = it },
            messageDetailVisible = selectedMessageId != null,
            messageScrollToUnreadToken = messageScrollToUnreadToken,
            messageScrollToTopToken = messageScrollToTopToken,
            eventCount = eventCount, eventRefreshToken = eventRefreshToken, onEventBatchModeChanged = { eventBatchMode = it },
            onEventBottomBarVisibilityChanged = { bottomBarVisible = it },
            eventScrollToTopToken = eventScrollToTopToken,
            thingCount = thingCount, thingRefreshToken = thingRefreshToken, onThingBatchModeChanged = { thingBatchMode = it },
            onThingBottomBarVisibilityChanged = { bottomBarVisible = it },
            thingScrollToTopToken = thingScrollToTopToken,
            onChannelBottomBarVisibilityChanged = { bottomBarVisible = it },
            pendingEventIdToOpen = pendingEventIdToOpen, onPendingEventOpened = { pendingEventIdToOpen = null },
            onEventDetailOpened = { openedEntityType = "event"; openedEntityId = it }, onEventDetailClosed = { openedEntityType = null; openedEntityId = null },
            pendingThingIdToOpen = pendingThingIdToOpen, onPendingThingOpened = { pendingThingIdToOpen = null },
            onThingDetailOpened = { openedEntityType = "thing"; openedEntityId = it }, onThingDetailClosed = { openedEntityType = null; openedEntityId = null }
        )
        if (selectedMessageId != null) {
            MessageDetailScreen(
                messageId = selectedMessageId!!, repository = container.messageRepository,
                stateCoordinator = container.messageStateCoordinator, channelRepository = container.channelRepository,
                imageStore = container.messageImageStore, onDismiss = { selectedMessageId = null }
            )
        }
    }

    val availableUpdate = settingsViewModel.availableUpdate
    if (autoUpdateDialogVisible && availableUpdate != null) {
        val updateBody = availableUpdate.notes?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.label_update_available_body)
        val isInstallingUpdate = settingsViewModel.isInstallingUpdate
        val installProgressText = settingsViewModel.updateInstallProgressMessage?.resolve(context)
        PushGoAlertDialog(
            onDismissRequest = {
                if (!isInstallingUpdate) {
                    settingsViewModel.remindLaterForAvailableUpdate()
                    autoUpdateDialogVisible = false
                }
            },
            title = {
                Text(
                    text = stringResource(
                        R.string.label_update_available_title,
                        availableUpdate.versionName,
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = updateBody)
                    if (isInstallingUpdate) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("progress.dialog.update.install"),
                        )
                        Text(
                            text = installProgressText
                                ?: stringResource(R.string.label_update_install_status_preparing),
                            style = MaterialTheme.typography.bodySmall,
                            color = PushGoThemeExtras.colors.textSecondary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isInstallingUpdate,
                    onClick = {
                        settingsViewModel.installAvailableUpdate()
                    },
                ) {
                    Text(text = stringResource(R.string.label_update_install_now))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isInstallingUpdate,
                    onClick = {
                        settingsViewModel.remindLaterForAvailableUpdate()
                        autoUpdateDialogVisible = false
                    },
                ) {
                    Text(text = stringResource(R.string.label_update_remind_later))
                }
            },
        )
    }

    if (!isOnSettingsRoute && settingsViewModel.shouldShowInstallPermissionDialog) {
        PushGoAlertDialog(
            onDismissRequest = settingsViewModel::consumeInstallPermissionDialog,
            title = { Text(text = stringResource(R.string.label_update_install_permission_title)) },
            text = { Text(text = stringResource(R.string.label_update_install_permission_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.consumeInstallPermissionDialog()
                        openUnknownAppSourcesSettings(context)
                    },
                ) {
                    Text(text = stringResource(R.string.label_turn_on))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val launched = openManualApkInstall(
                                context = context,
                                apkPath = settingsViewModel.pendingManualInstallApkPath,
                            )
                            if (launched) {
                                settingsViewModel.consumeInstallPermissionDialog()
                                settingsViewModel.consumePendingManualInstallApkPath()
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.label_update_install_manual_continue))
                    }
                    TextButton(onClick = settingsViewModel::consumeInstallPermissionDialog) {
                        Text(text = stringResource(R.string.label_cancel))
                    }
                }
            },
        )
    }
}

@Composable
private fun PushGoUnreadBadge(text: String) {
    val uiColors = PushGoThemeExtras.colors
    Badge(
        containerColor = uiColors.overlayForeground,
        contentColor = uiColors.accentPrimary
    ) {
        Text(text)
    }
}

@Composable
private fun PushGoNavHost(
    navController: NavHostController, container: AppContainer, factory: PushGoViewModelFactory, settingsViewModel: SettingsViewModel,
    initialRoute: Any, padding: PaddingValues, onMessageClick: (String) -> Unit, onMessageBatchModeChanged: (Boolean) -> Unit,
    onMessageBottomBarVisibilityChanged: (Boolean) -> Unit, messageDetailVisible: Boolean, messageScrollToUnreadToken: Long, messageScrollToTopToken: Long,
    eventCount: Int, eventRefreshToken: Long, onEventBatchModeChanged: (Boolean) -> Unit, onEventBottomBarVisibilityChanged: (Boolean) -> Unit, eventScrollToTopToken: Long,
    thingCount: Int, thingRefreshToken: Long, onThingBatchModeChanged: (Boolean) -> Unit, onThingBottomBarVisibilityChanged: (Boolean) -> Unit, thingScrollToTopToken: Long,
    onChannelBottomBarVisibilityChanged: (Boolean) -> Unit,
    pendingEventIdToOpen: String?, onPendingEventOpened: () -> Unit, onEventDetailOpened: (String) -> Unit, onEventDetailClosed: () -> Unit,
    pendingThingIdToOpen: String?, onPendingThingOpened: () -> Unit, onThingDetailOpened: (String) -> Unit, onThingDetailClosed: () -> Unit
) {
    NavHost(
        navController = navController, startDestination = initialRoute, modifier = Modifier.padding(padding),
        enterTransition = { fadeIn(tween(300)) }, exitTransition = { fadeOut(tween(300)) }
    ) {
        composable<MessagesRoute> {
            MessageListScreen(
                navController,
                container,
                factory,
                onMessageClick,
                onMessageBatchModeChanged,
                onMessageBottomBarVisibilityChanged,
                !messageDetailVisible,
                messageScrollToUnreadToken,
                messageScrollToTopToken
            )
        }
        composable<ChannelsRoute> { ChannelListScreen(navController, settingsViewModel, onChannelBottomBarVisibilityChanged) }
        composable<EventsRoute> {
            EventListScreen(
                container,
                eventRefreshToken,
                pendingEventIdToOpen,
                onPendingEventOpened,
                onEventDetailOpened,
                onEventDetailClosed,
                onEventBatchModeChanged,
                onEventBottomBarVisibilityChanged,
                eventScrollToTopToken
            )
        }
        composable<ThingsRoute> {
            ThingListScreen(
                container,
                thingRefreshToken,
                pendingThingIdToOpen,
                onPendingThingOpened,
                onThingDetailOpened,
                onThingDetailClosed,
                onThingBatchModeChanged,
                onThingBottomBarVisibilityChanged,
                thingScrollToTopToken
            )
        }
        composable<SettingsRoute> { SettingsScreen(settingsViewModel, { navController.navigate(ConnectionDiagnosisRoute) }, { navController.navigateUp() }) }
        composable<DecryptionRoute> { io.ethan.pushgo.ui.screens.MessageDecryptionScreen(navController, factory, settingsViewModel) }
        composable<ConnectionDiagnosisRoute> { io.ethan.pushgo.ui.screens.ConnectionDiagnosisScreen(navController, factory) }
    }
}

private fun buildAutomationState(
    activeTab: String, currentRoute: String?, selectedMessageId: String?, pendingEventId: String?, pendingThingId: String?,
    snapshot: AutomationSnapshot, openedEntityType: String?, openedEntityId: String?
): PushGoAutomation.AutomationState {
    val tab = activeTab.substringBefore('?').substringBefore('/')
    val error = PushGoAutomation.latestRuntimeErrorSnapshot()
    return PushGoAutomation.AutomationState(
        activeTab = tab, visibleScreen = if (selectedMessageId != null) "screen.message.detail" else "screen.messages.list",
        openedMessageId = selectedMessageId, openedMessageDecryptionState = snapshot.openedMessageDecryptionState,
        openedEntityType = openedEntityType, openedEntityId = openedEntityId, pendingEventId = pendingEventId, pendingThingId = pendingThingId,
        unreadMessageCount = snapshot.unreadMessageCount, totalMessageCount = snapshot.totalMessageCount,
        eventCount = snapshot.eventCount, thingCount = snapshot.thingCount,
        messagePageEnabled = snapshot.messagePageEnabled, eventPageEnabled = snapshot.eventPageEnabled, thingPageEnabled = snapshot.thingPageEnabled,
        notificationKeyConfigured = snapshot.notificationKeyConfigured, notificationKeyEncoding = snapshot.notificationKeyEncoding,
        gatewayBaseUrl = snapshot.gatewayBaseUrl, gatewayTokenPresent = snapshot.gatewayTokenPresent,
        useFcmChannel = snapshot.useFcmChannel, providerMode = snapshot.providerMode, providerDeviceKeyPresent = snapshot.providerDeviceKeyPresent,
        privateRoute = snapshot.privateRoute, privateTransport = snapshot.privateTransport, privateStage = snapshot.privateStage, privateDetail = snapshot.privateDetail,
        ackPendingCount = snapshot.ackPendingCount, channelCount = snapshot.channelCount,
        lastNotificationAction = snapshot.lastNotificationAction, lastNotificationTarget = snapshot.lastNotificationTarget,
        lastFixtureImportPath = snapshot.lastFixtureImportPath, lastFixtureImportMessageCount = snapshot.lastFixtureImportMessageCount,
        lastFixtureImportEntityRecordCount = snapshot.lastFixtureImportEntityRecordCount, lastFixtureImportSubscriptionCount = snapshot.lastFixtureImportSubscriptionCount,
        runtimeErrorCount = PushGoAutomation.currentRuntimeErrorCount(),
        latestRuntimeErrorSource = error?.source, latestRuntimeErrorCategory = error?.category,
        latestRuntimeErrorCode = error?.code, latestRuntimeErrorMessage = error?.message, latestRuntimeErrorTimestamp = error?.timestamp,
    )
}

private data class RouteMatchSpec(
    val wireValue: String,
    val serialRoute: String,
    val legacyRoute: String,
)

private enum class TopLevelRoute(val spec: RouteMatchSpec) {
    MESSAGES(RouteMatchSpec("messages", MessagesRoute.serializer().descriptor.serialName, "messages")),
    EVENTS(RouteMatchSpec("events", EventsRoute.serializer().descriptor.serialName, "events")),
    THINGS(RouteMatchSpec("things", ThingsRoute.serializer().descriptor.serialName, "things")),
    CHANNELS(RouteMatchSpec("channels", ChannelsRoute.serializer().descriptor.serialName, "channels")),
}

private fun String?.matches(spec: RouteMatchSpec): Boolean {
    val route = this ?: return false
    return route == spec.serialRoute || route == spec.legacyRoute
}

private fun String?.matchesSettingsRoute(): Boolean {
    val route = this ?: return false
    return route == SettingsRoute.serializer().descriptor.serialName || route == "settings"
}

private fun String?.topLevelRoute(): TopLevelRoute? {
    return TopLevelRoute.entries.firstOrNull { this.matches(it.spec) }
}

private fun String?.matches(item: BottomItem): Boolean {
    return when (item.route) {
        is MessagesRoute -> this.matches(TopLevelRoute.MESSAGES.spec)
        is EventsRoute -> this.matches(TopLevelRoute.EVENTS.spec)
        is ThingsRoute -> this.matches(TopLevelRoute.THINGS.spec)
        is ChannelsRoute -> this.matches(TopLevelRoute.CHANNELS.spec)
        else -> false
    }
}

private fun BottomItem.topLevelRoute(): TopLevelRoute? {
    return when (route) {
        is MessagesRoute -> TopLevelRoute.MESSAGES
        is EventsRoute -> TopLevelRoute.EVENTS
        is ThingsRoute -> TopLevelRoute.THINGS
        is ChannelsRoute -> TopLevelRoute.CHANNELS
        else -> null
    }
}

private fun openUnknownAppSourcesSettings(context: android.content.Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(fallback) }
}

private fun openManualApkInstall(context: android.content.Context, apkPath: String?): Boolean {
    val path = apkPath?.trim().orEmpty()
    if (path.isEmpty()) {
        return false
    }
    val apkFile = File(path)
    if (!apkFile.exists()) {
        return false
    }
    val apkUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile,
    )
    val installIntent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(apkUri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching {
        context.startActivity(installIntent)
    }.isSuccess
}
