package io.ethan.pushgo.ui

import android.content.Intent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Memory
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import io.ethan.pushgo.ui.screens.EventListScreen
import io.ethan.pushgo.ui.screens.ChannelListScreen
import io.ethan.pushgo.ui.screens.MessageDetailScreen
import io.ethan.pushgo.ui.screens.MessageListScreen
import io.ethan.pushgo.ui.screens.SettingsScreen
import io.ethan.pushgo.ui.screens.ThingListScreen
import io.ethan.pushgo.ui.theme.DarkNavigationBar
import io.ethan.pushgo.ui.theme.LightNavigationBar
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay

private data class BottomItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TopLevelRoutes = setOf("messages", "events", "things", "channels")

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
    val unreadCount by container.messageRepository.observeUnreadCount().collectAsState(initial = 0)
    val eventCount by container.entityRepository.observeEventCount().collectAsState(initial = 0)
    val eventRefreshToken by container.entityRepository.observeEventRefreshToken().collectAsState(initial = 0L)
    val thingCount by container.entityRepository.observeThingCount().collectAsState(initial = 0)
    val thingRefreshToken by container.entityRepository.observeThingRefreshToken().collectAsState(initial = 0L)
    val isMessagePageEnabled by container.settingsRepository.messagePageEnabledFlow
        .collectAsState(initial = true)
    val isEventPageEnabled by container.settingsRepository.eventPageEnabledFlow
        .collectAsState(initial = true)
    val isThingPageEnabled by container.settingsRepository.thingPageEnabledFlow
        .collectAsState(initial = true)
    val items = buildList {
        if (isMessagePageEnabled) {
            add(
                BottomItem(
                    "messages",
                    stringResource(R.string.tab_messages),
                    Icons.AutoMirrored.Filled.Chat
                )
            )
        }
        if (isEventPageEnabled) {
            add(
                BottomItem(
                    "events",
                    stringResource(R.string.label_send_type_event),
                    Icons.AutoMirrored.Outlined.EventNote
                )
            )
        }
        if (isThingPageEnabled) {
            add(BottomItem("things", stringResource(R.string.label_send_type_thing), Icons.Outlined.Memory))
        }
        add(BottomItem("channels", stringResource(R.string.section_channels), Icons.Outlined.Group))
    }
    val badgeText = remember(unreadCount) { if (unreadCount > 99) "99+" else unreadCount.toString() }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val currentRootRoute = currentRoute.rootRoute()
    val automationRequestVersion = PushGoAutomation.requestVersion
    val showBottomBar = items.any { it.route == currentRootRoute }
    val navBarColor = if (useDarkTheme) DarkNavigationBar else LightNavigationBar

    LaunchedEffect(startIntent) {
        val entityType = startIntent
            ?.getStringExtra(NotificationHelper.EXTRA_ENTITY_TYPE)
            ?.trim()
            ?.lowercase()
        val entityId = startIntent
            ?.getStringExtra(NotificationHelper.EXTRA_ENTITY_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (entityType == "event" && entityId != null) {
            navigateTo(navController, "events")
            pendingEventIdToOpen = entityId
            return@LaunchedEffect
        }
        if (entityType == "thing" && entityId != null) {
            navigateTo(navController, "things")
            pendingThingIdToOpen = entityId
            return@LaunchedEffect
        }
        val messageId = startIntent?.getStringExtra(NotificationHelper.EXTRA_MESSAGE_ID)
        if (!messageId.isNullOrBlank()) {
            selectedMessageId = messageId
        }
    }

    LaunchedEffect(currentRootRoute, selectedMessageId, pendingEventIdToOpen, pendingThingIdToOpen) {
        while (true) {
            val snapshot = automationController.snapshotForOpenedMessage(selectedMessageId)
            PushGoAutomation.publishState(
                buildAutomationState(
                    activeTab = currentRootRoute ?: items.firstOrNull()?.route ?: "messages",
                    currentRoute = currentRoute,
                    selectedMessageId = selectedMessageId,
                    pendingEventId = pendingEventIdToOpen,
                    pendingThingId = pendingThingIdToOpen,
                    snapshot = snapshot,
                    openedEntityType = openedEntityType,
                    openedEntityId = openedEntityId,
                )
            )
            delay(500)
        }
    }

    LaunchedEffect(automationRequestVersion) {
        val request = PushGoAutomation.consumePendingRequest() ?: return@LaunchedEffect
        PushGoAutomation.startCommandTrace(request)
        PushGoAutomation.writeEvent(
            type = "command.received",
            command = request.name,
        )
        var activeTab = currentRootRoute ?: items.firstOrNull()?.route ?: "messages"
        var openedMessageId = selectedMessageId
        var responseOpenedEntityType = openedEntityType
        var responseOpenedEntityId = openedEntityId
        var pendingEventId = pendingEventIdToOpen
        var pendingThingId = pendingThingIdToOpen
        var error: String? = null

        when (request.name) {
            "snapshot.get", "debug.dump_state" -> Unit
            "nav.switch_tab" -> {
                val tab = request.stringArg("tab")?.rootRoute()
                if (tab == null || tab !in setOf("messages", "events", "things", "channels", "settings")) {
                    error = "Unsupported tab: ${request.stringArg("tab").orEmpty()}"
                } else {
                    navigateTo(navController, tab)
                    activeTab = tab
                    if (tab != "messages") {
                        openedMessageId = null
                    }
                    if (tab != "events" && tab != "things") {
                        openedEntityType = null
                        openedEntityId = null
                        responseOpenedEntityType = null
                        responseOpenedEntityId = null
                    }
                }
            }
            "message.open" -> {
                val messageId = request.stringArg("message_id")
                if (messageId.isNullOrBlank()) {
                    error = "Missing automation argument: message_id"
                } else {
                    navigateTo(navController, "messages")
                    selectedMessageId = messageId
                    activeTab = "messages"
                    openedMessageId = messageId
                }
            }
            "entity.open" -> {
                val entityType = request.stringArg("entity_type")?.trim()?.lowercase()
                val entityId = request.stringArg("entity_id")?.trim()
                if (entityType.isNullOrBlank()) {
                    error = "Missing automation argument: entity_type"
                } else if (entityId.isNullOrBlank()) {
                    error = "Missing automation argument: entity_id"
                } else if (entityType == "event") {
                    navigateTo(navController, "events")
                    pendingEventIdToOpen = entityId
                    pendingEventId = entityId
                    activeTab = "events"
                } else if (entityType == "thing") {
                    navigateTo(navController, "things")
                    pendingThingIdToOpen = entityId
                    pendingThingId = entityId
                    activeTab = "things"
                } else {
                    error = "Unsupported entity type: $entityType"
                }
            }
            "settings.set_page_visibility" -> {
                val page = request.stringArg("page")
                val enabled = request.booleanArg("enabled")
                if (page.isNullOrBlank()) {
                    error = "Missing automation argument: page"
                } else if (enabled == null) {
                    error = "Missing automation argument: enabled"
                } else {
                    runCatching { automationController.setPageVisibility(page, enabled) }
                        .onFailure { error = it.message ?: "Failed to set page visibility" }
                }
            }
            "settings.open_decryption" -> {
                navigateTo(navController, "settings")
                navController.navigate("settings/decryption")
                activeTab = "settings"
            }
            "settings.set_decryption_key" -> {
                runCatching {
                    automationController.setDecryptionKey(
                        key = request.stringArg("key"),
                        encoding = request.stringArg("encoding"),
                    )
                }.onFailure { error = it.message ?: "Failed to update decryption key" }
            }
            "gateway.set_server" -> {
                runCatching {
                    automationController.setGatewayServer(
                        baseUrl = request.stringArg("base_url"),
                        token = request.stringArg("token"),
                    )
                }.onFailure { error = it.message ?: "Failed to set gateway config" }
            }
            "private.trigger_wakeup" -> {
                automationController.triggerPrivateWakeup()
            }
            "private.drain_acks" -> {
                runCatching { automationController.drainPrivateAcks() }
                    .onFailure { error = it.message ?: "Failed to drain ack outbox" }
            }
            "fixture.import" -> {
                val path = request.stringArg("path") ?: request.stringArg("fixture_path")
                if (path.isNullOrBlank()) {
                    error = "Missing automation argument: path"
                } else {
                    runCatching { automationController.importFixture(path) }
                        .onFailure { error = it.message ?: "Failed to import fixture" }
                }
            }
            "fixture.seed_messages" -> {
                val path = request.stringArg("path") ?: request.stringArg("fixture_path")
                if (path.isNullOrBlank()) {
                    error = "Missing automation argument: path"
                } else {
                    runCatching { automationController.seedFixtureMessages(path) }
                        .onFailure { error = it.message ?: "Failed to seed message fixtures" }
                }
            }
            "fixture.seed_entity_records" -> {
                val path = request.stringArg("path") ?: request.stringArg("fixture_path")
                if (path.isNullOrBlank()) {
                    error = "Missing automation argument: path"
                } else {
                    runCatching { automationController.seedFixtureEntityRecords(path) }
                        .onFailure { error = it.message ?: "Failed to seed entity fixtures" }
                }
            }
            "fixture.seed_subscriptions" -> {
                val path = request.stringArg("path") ?: request.stringArg("fixture_path")
                if (path.isNullOrBlank()) {
                    error = "Missing automation argument: path"
                } else {
                    runCatching { automationController.seedFixtureSubscriptions(path) }
                        .onFailure { error = it.message ?: "Failed to seed subscription fixtures" }
                }
            }
            "notification.open" -> {
                val notificationRequestId = request.stringArg("notification_request_id")
                val messageId = request.stringArg("message_id")
                val entityType = request.stringArg("entity_type")?.trim()?.lowercase()
                val entityId = request.stringArg("entity_id")?.trim()
                when {
                    !notificationRequestId.isNullOrBlank() -> {
                        val target = runCatching {
                            automationController.resolveNotificationTarget(notificationRequestId)
                        }.getOrElse {
                            error = it.message ?: "Failed to resolve notification target"
                            null
                        }
                        when {
                            target?.messageId != null -> {
                                navigateTo(navController, "messages")
                                selectedMessageId = target.messageId
                                openedMessageId = target.messageId
                                activeTab = "messages"
                            }
                            target == null && error != null -> Unit
                            else -> error = "Notification target not found: $notificationRequestId"
                        }
                    }
                    !messageId.isNullOrBlank() -> {
                        navigateTo(navController, "messages")
                        selectedMessageId = messageId
                        openedMessageId = messageId
                        activeTab = "messages"
                    }
                    entityType == "event" && !entityId.isNullOrBlank() -> {
                        navigateTo(navController, "events")
                        pendingEventIdToOpen = entityId
                        pendingEventId = entityId
                        activeTab = "events"
                    }
                    entityType == "thing" && !entityId.isNullOrBlank() -> {
                        navigateTo(navController, "things")
                        pendingThingIdToOpen = entityId
                        pendingThingId = entityId
                        activeTab = "things"
                    }
                    else -> error = "Missing automation notification target"
                }
            }
            "notification.mark_read" -> {
                val updatedMessageId = runCatching {
                    automationController.markNotificationRead(
                        notificationRequestId = request.stringArg("notification_request_id"),
                        messageId = request.stringArg("message_id"),
                    )
                }.getOrElse {
                    error = it.message ?: "Failed to mark notification as read"
                    null
                }
                if (updatedMessageId == null && error == null) {
                    error = "Notification target not found"
                }
            }
            "notification.delete" -> {
                val deletedMessageId = runCatching {
                    automationController.deleteNotification(
                        notificationRequestId = request.stringArg("notification_request_id"),
                        messageId = request.stringArg("message_id"),
                    )
                }.getOrElse {
                    error = it.message ?: "Failed to delete notification target"
                    null
                }
                if (deletedMessageId == null && error == null) {
                    error = "Notification target not found"
                } else if (deletedMessageId == selectedMessageId) {
                    selectedMessageId = null
                    openedMessageId = null
                }
            }
            "notification.copy" -> {
                val copiedMessageId = runCatching {
                    automationController.copyNotification(
                        notificationRequestId = request.stringArg("notification_request_id"),
                        messageId = request.stringArg("message_id"),
                    )
                }.getOrElse {
                    error = it.message ?: "Failed to copy notification payload"
                    null
                }
                if (copiedMessageId == null && error == null) {
                    error = "Notification target not found"
                }
            }
            "debug.reset_local_state", "fixture.reset_local_state" -> {
                runCatching { automationController.resetLocalState() }
                    .onFailure { error = it.message ?: "Failed to reset local state" }
                if (error == null) {
                    selectedMessageId = null
                    openedMessageId = null
                    pendingEventIdToOpen = null
                    pendingThingIdToOpen = null
                    pendingEventId = null
                    pendingThingId = null
                    openedEntityType = null
                    openedEntityId = null
                    responseOpenedEntityType = null
                    responseOpenedEntityId = null
                    navigateTo(navController, "messages")
                    activeTab = "messages"
                }
            }
            else -> error = "Unsupported automation command: ${request.name}"
        }

        val snapshot = automationController.snapshotForOpenedMessage(openedMessageId)
        val state = buildAutomationState(
            activeTab = activeTab,
            currentRoute = navController.currentDestination?.route,
            selectedMessageId = openedMessageId,
            pendingEventId = pendingEventId,
            pendingThingId = pendingThingId,
            snapshot = snapshot,
            openedEntityType = responseOpenedEntityType,
            openedEntityId = responseOpenedEntityId,
        )
        if (state.activeTab != "messages" && selectedMessageId != null) {
            selectedMessageId = null
        }
        if (state.activeTab != currentRootRoute.rootRoute()) {
            navigateTo(navController, state.activeTab)
        }
        PushGoAutomation.writeResponse(
            request = request,
            ok = error == null,
            state = state,
            error = error,
        )
        PushGoAutomation.writeEvent(
            type = if (error == null) "command.completed" else "command.failed",
            command = request.name,
            details = org.json.JSONObject().apply {
                if (error != null) {
                    put("error", error)
                }
            },
        )
        PushGoAutomation.finishCommandTrace(error)
    }

    LaunchedEffect(items, currentRootRoute) {
        if (items.isEmpty()) return@LaunchedEffect
        val rootRoute = currentRootRoute ?: return@LaunchedEffect
        val isPrimaryTabRoute = rootRoute in TopLevelRoutes
        if (isPrimaryTabRoute && items.none { it.route == rootRoute }) {
            navigateTo(navController, items.first().route)
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    modifier = Modifier.testTag("nav.bottom"),
                    containerColor = navBarColor,
                ) {
                    items.forEach { item ->
                        val selected = currentRootRoute == item.route
                        NavigationBarItem(
                            modifier = Modifier.testTag("tab.${item.route}"),
                            selected = selected,
                            onClick = { navigateTo(navController, item.route) },
                            icon = {
                                if (item.route == "messages" && unreadCount > 0) {
                                    BadgedBox(badge = { Badge { Text(badgeText) } }) {
                                        androidx.compose.material3.Icon(
                                            item.icon,
                                            contentDescription = item.label
                                        )
                                    }
                                } else {
                                    androidx.compose.material3.Icon(
                                        item.icon,
                                        contentDescription = item.label
                                    )
                                }
                            },
                            label = { Text(item.label) },
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
            settingsViewModel = settingsViewModel,
            padding = padding,
            onMessageClick = { selectedMessageId = it },
            eventCount = eventCount,
            eventRefreshToken = eventRefreshToken,
            thingCount = thingCount,
            thingRefreshToken = thingRefreshToken,
            pendingEventIdToOpen = pendingEventIdToOpen,
            onPendingEventOpened = { pendingEventIdToOpen = null },
            onEventDetailOpened = { eventId ->
                openedEntityType = "event"
                openedEntityId = eventId
            },
            onEventDetailClosed = {
                if (openedEntityType == "event") {
                    openedEntityType = null
                    openedEntityId = null
                }
            },
            pendingThingIdToOpen = pendingThingIdToOpen,
            onPendingThingOpened = { pendingThingIdToOpen = null },
            onThingDetailOpened = { thingId ->
                openedEntityType = "thing"
                openedEntityId = thingId
            },
            onThingDetailClosed = {
                if (openedEntityType == "thing") {
                    openedEntityType = null
                    openedEntityId = null
                }
            },
        )
        
        if (selectedMessageId != null) {
            Box(modifier = Modifier.fillMaxSize().testTag("screen.message.detail")) {
                MessageDetailScreen(
                    messageId = selectedMessageId!!,
                    repository = container.messageRepository,
                    stateCoordinator = container.messageStateCoordinator,
                    channelRepository = container.channelRepository,
                    imageStore = container.messageImageStore,
                    onDismiss = { selectedMessageId = null }
                )
            }
        }
    }
}

@Composable
private fun PushGoNavHost(
    navController: NavHostController,
    container: AppContainer,
    factory: PushGoViewModelFactory,
    settingsViewModel: SettingsViewModel,
    padding: PaddingValues,
    onMessageClick: (String) -> Unit,
    eventCount: Int,
    eventRefreshToken: Long,
    thingCount: Int,
    thingRefreshToken: Long,
    pendingEventIdToOpen: String?,
    onPendingEventOpened: () -> Unit,
    onEventDetailOpened: (String) -> Unit,
    onEventDetailClosed: () -> Unit,
    pendingThingIdToOpen: String?,
    onPendingThingOpened: () -> Unit,
    onThingDetailOpened: (String) -> Unit,
    onThingDetailClosed: () -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = "messages",
        modifier = Modifier.padding(padding),
    ) {
        composable("messages") {
            Box(modifier = Modifier.fillMaxSize().testTag("screen.messages.list")) {
                MessageListScreen(
                    navController = navController,
                    container = container,
                    factory = factory,
                    onMessageClick = onMessageClick,
                )
            }
        }
        composable("channels") {
            Box(modifier = Modifier.fillMaxSize().testTag("screen.channels")) {
                ChannelListScreen(
                    navController = navController,
                    viewModel = settingsViewModel
                )
            }
        }
        composable("events") {
            Box(modifier = Modifier.fillMaxSize().testTag("screen.events.list")) {
                EventListScreen(
                    container = container,
                    refreshToken = eventRefreshToken,
                    openEventId = pendingEventIdToOpen,
                    onOpenEventHandled = onPendingEventOpened,
                    onEventDetailOpened = onEventDetailOpened,
                    onEventDetailClosed = onEventDetailClosed,
                )
            }
        }
        composable("things") {
            Box(modifier = Modifier.fillMaxSize().testTag("screen.things.list")) {
                ThingListScreen(
                    container = container,
                    refreshToken = thingRefreshToken,
                    openThingId = pendingThingIdToOpen,
                    onOpenThingHandled = onPendingThingOpened,
                    onThingDetailOpened = onThingDetailOpened,
                    onThingDetailClosed = onThingDetailClosed,
                )
            }
        }
        composable("settings") {
            Box(modifier = Modifier.fillMaxSize().testTag("screen.settings")) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onBackClick = { navController.navigateUp() },
                )
            }
        }
        composable("settings/decryption") {
            io.ethan.pushgo.ui.screens.MessageDecryptionScreen(
                navController = navController,
                factory = factory,
                viewModel = settingsViewModel
            )
        }
    }
}

private fun String?.rootRoute(): String? {
    return this
        ?.substringBefore('?')
        ?.substringBefore('/')
        ?.takeIf { it.isNotBlank() }
}

private fun navigateTo(navController: NavHostController, route: String) {
    if (navController.currentDestination?.route.rootRoute() == route) return
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = false
        }
        launchSingleTop = true
        restoreState = false
    }
}

private fun buildAutomationState(
    activeTab: String,
    currentRoute: String?,
    selectedMessageId: String?,
    pendingEventId: String?,
    pendingThingId: String?,
    snapshot: AutomationSnapshot,
    openedEntityType: String? = null,
    openedEntityId: String? = null,
): PushGoAutomation.AutomationState {
    val normalizedTab = resolveAutomationTab(activeTab.rootRoute() ?: "messages", snapshot)
    val latestRuntimeError = PushGoAutomation.latestRuntimeErrorSnapshot()
    val visibleScreen = when {
        currentRoute == "settings/decryption" -> "screen.settings.decryption"
        normalizedTab == "messages" && !selectedMessageId.isNullOrBlank() -> "screen.message.detail"
        normalizedTab == "events" && openedEntityType == "event" && !openedEntityId.isNullOrBlank() -> "screen.events.detail"
        normalizedTab == "events" -> "screen.events.list"
        normalizedTab == "things" && openedEntityType == "thing" && !openedEntityId.isNullOrBlank() -> "screen.thing.detail"
        normalizedTab == "things" -> "screen.things.list"
        normalizedTab == "channels" -> "screen.channels"
        normalizedTab == "settings" -> "screen.settings"
        else -> "screen.messages.list"
    }
    return PushGoAutomation.AutomationState(
        activeTab = normalizedTab,
        visibleScreen = visibleScreen,
        openedMessageId = selectedMessageId?.takeIf { normalizedTab == "messages" },
        openedMessageDecryptionState = snapshot.openedMessageDecryptionState,
        openedEntityType = openedEntityType,
        openedEntityId = openedEntityId,
        pendingEventId = pendingEventId,
        pendingThingId = pendingThingId,
        unreadMessageCount = snapshot.unreadMessageCount,
        totalMessageCount = snapshot.totalMessageCount,
        eventCount = snapshot.eventCount,
        thingCount = snapshot.thingCount,
        messagePageEnabled = snapshot.messagePageEnabled,
        eventPageEnabled = snapshot.eventPageEnabled,
        thingPageEnabled = snapshot.thingPageEnabled,
        notificationKeyConfigured = snapshot.notificationKeyConfigured,
        notificationKeyEncoding = snapshot.notificationKeyEncoding,
        gatewayBaseUrl = snapshot.gatewayBaseUrl,
        gatewayTokenPresent = snapshot.gatewayTokenPresent,
        useFcmChannel = snapshot.useFcmChannel,
        providerMode = snapshot.providerMode,
        providerDeviceKeyPresent = snapshot.providerDeviceKeyPresent,
        privateRoute = snapshot.privateRoute,
        privateTransport = snapshot.privateTransport,
        privateStage = snapshot.privateStage,
        privateDetail = snapshot.privateDetail,
        ackPendingCount = snapshot.ackPendingCount,
        channelCount = snapshot.channelCount,
        lastNotificationAction = snapshot.lastNotificationAction,
        lastNotificationTarget = snapshot.lastNotificationTarget,
        lastFixtureImportPath = snapshot.lastFixtureImportPath,
        lastFixtureImportMessageCount = snapshot.lastFixtureImportMessageCount,
        lastFixtureImportEntityRecordCount = snapshot.lastFixtureImportEntityRecordCount,
        lastFixtureImportSubscriptionCount = snapshot.lastFixtureImportSubscriptionCount,
        runtimeErrorCount = PushGoAutomation.currentRuntimeErrorCount(),
        latestRuntimeErrorSource = latestRuntimeError?.source,
        latestRuntimeErrorCategory = latestRuntimeError?.category,
        latestRuntimeErrorCode = latestRuntimeError?.code,
        latestRuntimeErrorMessage = latestRuntimeError?.message,
        latestRuntimeErrorTimestamp = latestRuntimeError?.timestamp,
    )
}

private fun resolveAutomationTab(activeTab: String, snapshot: AutomationSnapshot): String {
    if (activeTab == "messages" && !snapshot.messagePageEnabled) {
        return fallbackAutomationTab(snapshot)
    }
    if (activeTab == "events" && !snapshot.eventPageEnabled) {
        return fallbackAutomationTab(snapshot)
    }
    if (activeTab == "things" && !snapshot.thingPageEnabled) {
        return fallbackAutomationTab(snapshot)
    }
    return activeTab
}

private fun fallbackAutomationTab(snapshot: AutomationSnapshot): String {
    return when {
        snapshot.messagePageEnabled -> "messages"
        snapshot.eventPageEnabled -> "events"
        snapshot.thingPageEnabled -> "things"
        else -> "channels"
    }
}
