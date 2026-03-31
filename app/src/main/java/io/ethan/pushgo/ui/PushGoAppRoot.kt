package io.ethan.pushgo.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import io.ethan.pushgo.R
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.AutomationSnapshot
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.ui.screens.ChannelListScreen
import io.ethan.pushgo.ui.screens.EventListScreen
import io.ethan.pushgo.ui.screens.MessageDetailScreen
import io.ethan.pushgo.ui.screens.MessageListScreen
import io.ethan.pushgo.ui.screens.SettingsScreen
import io.ethan.pushgo.ui.screens.ThingListScreen
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

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

    val initialRoute: Any = remember(items) { items.firstOrNull()?.route ?: ChannelsRoute }
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val showBottomBar = items.any { it.route::class.qualifiedName == currentRoute }
    val hideBottomBarForBatchMode = when (currentRoute) {
        MessagesRoute::class.qualifiedName -> messageBatchMode
        EventsRoute::class.qualifiedName -> eventBatchMode
        ThingsRoute::class.qualifiedName -> thingBatchMode
        else -> false
    }

    LaunchedEffect(startIntent) {
        val entityId = startIntent?.getStringExtra(NotificationHelper.EXTRA_ENTITY_ID)?.takeIf { it.isNotEmpty() }
        val entityType = startIntent?.getStringExtra(NotificationHelper.EXTRA_ENTITY_TYPE)?.lowercase()
        if (entityType == "event" && entityId != null) {
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
            PushGoAutomation.publishState(buildAutomationState(currentRoute ?: MessagesRoute::class.qualifiedName!!, currentRoute, selectedMessageId, pendingEventIdToOpen, pendingThingIdToOpen, snapshot, openedEntityType, openedEntityId))
            delay(500)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            val colorScheme = MaterialTheme.colorScheme
            AnimatedVisibility(
                // REMOVED selectedMessageId == null to keep TabBar visible when sheet is open
                visible = showBottomBar && !hideBottomBarForBatchMode,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                NavigationBar(
                    modifier = Modifier.testTag("nav.bottom").drawBehind {
                        drawLine(color = colorScheme.outlineVariant.copy(alpha = 0.3f), start = Offset(0f, 0f), end = Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx())
                    },
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars
                ) {
                    items.forEach { item ->
                        val selected = currentRoute == item.route::class.qualifiedName
                        NavigationBarItem(
                            selected = selected,
                            onClick = { if (!selected) navController.navigate(item.route) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = {
                                if (item.route is MessagesRoute && unreadCount > 0) {
                                    BadgedBox(badge = { Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) } }) { Icon(item.icon, contentDescription = item.label) }
                                } else { Icon(item.icon, contentDescription = item.label) }
                            },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
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
            eventCount = eventCount, eventRefreshToken = eventRefreshToken, onEventBatchModeChanged = { eventBatchMode = it },
            thingCount = thingCount, thingRefreshToken = thingRefreshToken, onThingBatchModeChanged = { thingBatchMode = it },
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
}

@Composable
private fun PushGoNavHost(
    navController: NavHostController, container: AppContainer, factory: PushGoViewModelFactory, settingsViewModel: SettingsViewModel,
    initialRoute: Any, padding: PaddingValues, onMessageClick: (String) -> Unit, onMessageBatchModeChanged: (Boolean) -> Unit,
    eventCount: Int, eventRefreshToken: Long, onEventBatchModeChanged: (Boolean) -> Unit,
    thingCount: Int, thingRefreshToken: Long, onThingBatchModeChanged: (Boolean) -> Unit,
    pendingEventIdToOpen: String?, onPendingEventOpened: () -> Unit, onEventDetailOpened: (String) -> Unit, onEventDetailClosed: () -> Unit,
    pendingThingIdToOpen: String?, onPendingThingOpened: () -> Unit, onThingDetailOpened: (String) -> Unit, onThingDetailClosed: () -> Unit
) {
    NavHost(
        navController = navController, startDestination = initialRoute, modifier = Modifier.padding(padding),
        enterTransition = { fadeIn(tween(300)) }, exitTransition = { fadeOut(tween(300)) }
    ) {
        composable<MessagesRoute> { MessageListScreen(navController, container, factory, onMessageClick, onMessageBatchModeChanged) }
        composable<ChannelsRoute> { ChannelListScreen(navController, settingsViewModel) }
        composable<EventsRoute> { EventListScreen(container, eventRefreshToken, pendingEventIdToOpen, onPendingEventOpened, onEventDetailOpened, onEventDetailClosed, onEventBatchModeChanged) }
        composable<ThingsRoute> { ThingListScreen(container, thingRefreshToken, pendingThingIdToOpen, onPendingThingOpened, onThingDetailOpened, onThingDetailClosed, onThingBatchModeChanged) }
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
