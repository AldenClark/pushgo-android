package io.ethan.pushgo

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.ui.PushGoAppRoot
import io.ethan.pushgo.ui.theme.PushGoTheme
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {
    companion object {
        private const val STARTUP_PERMISSION_PREFS = "pushgo_notification_permission"
        private const val KEY_POST_NOTIFICATIONS_REQUESTED = "post_notifications_requested"
    }

    private var latestIntent by mutableStateOf<Intent?>(null)
    private var showNotificationPermissionDialog by mutableStateOf(false)
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showNotificationPermissionDialog = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        latestIntent = intent
        PushGoAutomation.configureFromIntent(intent, filesDir)
        NotificationHelper.stopAlertPlaybackForLaunchIntent(this, intent)
        evaluateNotificationPermissionOnStartup()
        val app = application as PushGoApp
        val container = app.containerOrNull()
        val storageError = app.startupStorageErrorMessage()
        val canRebuild = app.startupStorageCanRebuild()
        setContent {
            val useDarkTheme = isSystemInDarkTheme()
            var rebuildError by remember { mutableStateOf<String?>(null) }

            PushGoTheme(useDarkTheme = useDarkTheme) {
                if (container == null) {
                    StorageUnavailableScreen(
                        reason = storageError ?: "Local persistent storage is unavailable.",
                        canRebuild = canRebuild,
                        rebuildError = rebuildError,
                        onRebuild = {
                            val result = runCatching {
                                app.rebuildPersistentStorageForRecovery()
                            }
                            result.onSuccess {
                                finishAffinity()
                                exitProcess(0)
                            }.onFailure { error ->
                                rebuildError = "重建失败：${error.message.orEmpty()}".trim()
                            }
                        },
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
                        AlertDialog(
                            onDismissRequest = { showNotificationPermissionDialog = false },
                            title = { Text(getString(R.string.label_enable_notifications)) },
                            text = { Text(getString(R.string.label_enable_notifications_hint)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showNotificationPermissionDialog = false
                                        openNotificationSettings()
                                    },
                                ) {
                                    Text(getString(R.string.label_turn_on))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNotificationPermissionDialog = false }) {
                                    Text(getString(R.string.label_cancel))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        latestIntent = intent
        PushGoAutomation.configureFromIntent(intent, filesDir)
        NotificationHelper.stopAlertPlaybackForLaunchIntent(this, intent)
    }

    private fun evaluateNotificationPermissionOnStartup() {
        if (PushGoAutomation.isSessionConfigured()) {
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
                if (!alreadyRequested) {
                    prefs.edit().putBoolean(KEY_POST_NOTIFICATIONS_REQUESTED, true).apply()
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    showNotificationPermissionDialog = true
                }
                return
            }
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            showNotificationPermissionDialog = true
        }
    }

    private fun openNotificationSettings() {
        val notificationSettingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val appDetailsIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(notificationSettingsIntent) }
            .onFailure { startActivity(appDetailsIntent) }
    }

    @androidx.compose.runtime.Composable
    private fun StorageUnavailableScreen(
        reason: String,
        canRebuild: Boolean,
        rebuildError: String?,
        onRebuild: () -> Unit,
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
            if (!rebuildError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(rebuildError)
            }
            Spacer(modifier = Modifier.height(20.dp))
            if (canRebuild) {
                Button(onClick = onRebuild) {
                    Text("重建数据库并退出")
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
            Button(onClick = onExit) {
                Text("退出应用")
            }
        }
    }
}
