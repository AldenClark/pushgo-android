package io.ethan.pushgo

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.ui.PushGoAppRoot
import io.ethan.pushgo.ui.screens.PushGoAlertDialog
import io.ethan.pushgo.ui.theme.PushGoTheme
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import io.ethan.pushgo.ui.theme.pushGoDangerButtonColors
import io.ethan.pushgo.util.isDozeReminderSnoozed
import io.ethan.pushgo.util.isAppSubjectToBatteryOptimization
import io.ethan.pushgo.util.openAppNotificationSettings
import io.ethan.pushgo.util.openBatteryOptimizationSettings
import io.ethan.pushgo.util.snoozeDozeReminderForOneMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {
    companion object {
        private const val STARTUP_PERMISSION_PREFS = "pushgo_notification_permission"
        private const val KEY_POST_NOTIFICATIONS_REQUESTED = "post_notifications_requested"
    }

    private var latestIntent by mutableStateOf<Intent?>(null)
    private var showNotificationPermissionDialog by mutableStateOf(false)
    private var showDozeModeDialog by mutableStateOf(false)
    private val guardRefreshScope = CoroutineScope(Dispatchers.Main.immediate)
    private var delayedGuardRefreshJob: Job? = null
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showNotificationPermissionDialog = true
                showDozeModeDialog = false
            } else {
                evaluateStartupDeliveryGuards(requestNotificationRuntimePermission = false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        latestIntent = intent
        PushGoAutomation.configureFromIntent(intent, filesDir)
        NotificationHelper.stopAlertPlaybackForLaunchIntent(this, intent)
        evaluateStartupDeliveryGuards(requestNotificationRuntimePermission = true)
        val app = application as PushGoApp
        val container = app.containerOrNull()
        val storageError = app.startupStorageErrorMessage()
        setContent {
            val useDarkTheme = isSystemInDarkTheme()

            PushGoTheme(useDarkTheme = useDarkTheme, dynamicColor = false) {
                if (container == null) {
                    StorageUnavailableScreen(
                        reason = storageError ?: "Local persistent storage is unavailable.",
                        onExit = {
                            finishAffinity()
                            exitProcess(0)
                        },
                    )
                } else {
                    PushGoAppRoot(
                        container = container,
                        startIntent = latestIntent,
                        useDarkTheme = useDarkTheme,
                    )
                    if (showNotificationPermissionDialog) {
                        UrgentPermissionDialog(
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.NotificationsActive,
                                    contentDescription = null,
                                    tint = PushGoThemeExtras.colors.stateDanger.foreground,
                                )
                            },
                            title = stringResource(R.string.label_enable_notifications),
                            message = stringResource(R.string.label_enable_notifications_hint),
                            urgencyHint = stringResource(R.string.label_notification_permission_urgent_hint),
                            confirmText = stringResource(R.string.label_turn_on_now),
                            onConfirm = {
                                showNotificationPermissionDialog = false
                                openAppNotificationSettings()
                            },
                            onDismiss = { showNotificationPermissionDialog = false },
                        )
                    } else if (showDozeModeDialog) {
                        UrgentPermissionDialog(
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Memory,
                                    contentDescription = null,
                                    tint = PushGoThemeExtras.colors.stateDanger.foreground,
                                )
                            },
                            title = stringResource(R.string.label_disable_doze_title),
                            message = stringResource(R.string.label_disable_doze_hint),
                            urgencyHint = stringResource(R.string.label_disable_doze_urgent_hint),
                            confirmText = stringResource(R.string.label_set_unrestricted),
                            onConfirm = {
                                showDozeModeDialog = false
                                openBatteryOptimizationSettings()
                            },
                            skipLabel = stringResource(R.string.label_skip_for_one_month),
                            onSkip = {
                                showDozeModeDialog = false
                                snoozeDozeReminderForOneMonth()
                            },
                            onDismiss = { showDozeModeDialog = false },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        evaluateStartupDeliveryGuards(requestNotificationRuntimePermission = false)
        scheduleDelayedStartupGuardRefresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        latestIntent = intent
        PushGoAutomation.configureFromIntent(intent, filesDir)
        NotificationHelper.stopAlertPlaybackForLaunchIntent(this, intent)
    }

    private fun evaluateStartupDeliveryGuards(requestNotificationRuntimePermission: Boolean) {
        if (PushGoAutomation.isSessionConfigured()) {
            showNotificationPermissionDialog = false
            showDozeModeDialog = false
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                val prefs = getSharedPreferences(STARTUP_PERMISSION_PREFS, MODE_PRIVATE)
                val alreadyRequested = prefs.getBoolean(KEY_POST_NOTIFICATIONS_REQUESTED, false)
                if (requestNotificationRuntimePermission && !alreadyRequested) {
                    prefs.edit().putBoolean(KEY_POST_NOTIFICATIONS_REQUESTED, true).apply()
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    showNotificationPermissionDialog = true
                }
                showDozeModeDialog = false
                return
            }
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            showNotificationPermissionDialog = true
            showDozeModeDialog = false
            return
        }
        showNotificationPermissionDialog = false
        showDozeModeDialog = isAppSubjectToBatteryOptimization() && !isDozeReminderSnoozed()
    }

    private fun scheduleDelayedStartupGuardRefresh() {
        delayedGuardRefreshJob?.cancel()
        delayedGuardRefreshJob = guardRefreshScope.launch {
            // Battery optimization exemption can apply asynchronously after returning from system settings.
            delay(450)
            evaluateStartupDeliveryGuards(requestNotificationRuntimePermission = false)
        }
    }

    override fun onDestroy() {
        delayedGuardRefreshJob?.cancel()
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun UrgentPermissionDialog(
        icon: @androidx.compose.runtime.Composable () -> Unit,
        title: String,
        message: String,
        urgencyHint: String,
        confirmText: String,
        skipLabel: String? = null,
        onConfirm: () -> Unit,
        onSkip: (() -> Unit)? = null,
        onDismiss: () -> Unit,
    ) {
        val uiColors = PushGoThemeExtras.colors
        PushGoAlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    icon()
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.label_urgent_attention),
                            style = MaterialTheme.typography.labelSmall,
                            color = uiColors.stateDanger.foreground,
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = uiColors.textPrimary,
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = uiColors.textPrimary,
                    )
                    Text(
                        text = urgencyHint,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = uiColors.stateDanger.foreground,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirm,
                    colors = pushGoDangerButtonColors(),
                ) {
                    Text(confirmText)
                }
            },
            dismissButton = if (skipLabel != null && onSkip != null) {
                {
                    TextButton(onClick = onSkip) {
                        Text(text = skipLabel)
                    }
                }
            } else {
                null
            },
        )
    }

    @androidx.compose.runtime.Composable
    private fun StorageUnavailableScreen(
        reason: String,
        onExit: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("本地数据库不可用")
            Spacer(modifier = Modifier.height(12.dp))
            Text("为防止数据被破坏，应用已停止读写。")
            Spacer(modifier = Modifier.height(8.dp))
            Text(reason)
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onExit) {
                Text("退出应用")
            }
        }
    }
}
