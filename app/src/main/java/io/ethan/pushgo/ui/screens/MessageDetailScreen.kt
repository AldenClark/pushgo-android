package io.ethan.pushgo.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import io.ethan.pushgo.R
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.markdown.MessageBodyResolver
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.ui.MessageDetailViewModelFactory
import io.ethan.pushgo.ui.MessagePayloadUtils
import io.ethan.pushgo.ui.markdown.MarkdownRenderer
import io.ethan.pushgo.ui.viewmodel.MessageDetailViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.os.Build
import android.text.format.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    messageId: String,
    repository: MessageRepository,
    stateCoordinator: MessageStateCoordinator,
    channelRepository: ChannelSubscriptionRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val viewModel: MessageDetailViewModel = viewModel(
        key = messageId,
        factory = MessageDetailViewModelFactory(repository, stateCoordinator, messageId),
    )
    val message by viewModel.message.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var channelNameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(messageId) {
        viewModel.load()
    }

    LaunchedEffect(messageId) {
        channelNameMap = channelRepository.loadSubscriptionLookup(includeDeleted = true)
    }

    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.label_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        return
    }

    val current = message
    if (current == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.label_no_messages),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
        }
        return
    }

    val timeText = remember(current.receivedAt, context.resources.configuration) {
        formatDetailTime(context, current.receivedAt, ZoneId.systemDefault())
    }
    val imageUrl = remember(current.rawPayloadJson) { MessagePayloadUtils.extractImageUrl(current.rawPayloadJson) }
    val prettyPayload = remember(current.rawPayloadJson) { prettyJson(current.rawPayloadJson) }
    val channelLabel = current.channel?.trim()?.let { channelNameMap[it] ?: it }
    val resolvedBody = remember(current.rawPayloadJson, current.body) {
        MessageBodyResolver.resolve(current.rawPayloadJson, current.body)
    }
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.4
    )

    var rawExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { onDismiss() },
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!current.isRead) {
                    IconButton(onClick = { viewModel.markRead() }) {
                        Icon(
                            imageVector = Icons.Outlined.MarkEmailRead,
                            contentDescription = stringResource(R.string.action_mark_read),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = {
                    val text = buildString {
                        append(current.title)
                        if (current.body.isNotBlank()) {
                            append("\n")
                            append(current.body)
                        }
                    }
                    scope.launch { clipboard.setText(AnnotatedString(text)) }
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    viewModel.delete()
                    onDismiss()
                }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.label_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column {
                            Text(
                                text = current.title,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (!channelLabel.isNullOrBlank()) {
                                    Text(
                                        text = "#$channelLabel",
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    text = timeText,
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        MarkdownRenderer(
                            text = resolvedBody.rawText,
                            textStyle = bodyStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (!imageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = imageUrl,
                                contentDescription = stringResource(R.string.label_image_attachment),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        }
                        if (!current.url.isNullOrBlank()) {
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, current.url.toUri())
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.label_open_url))
                            }
                        }
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.label_raw_payload),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { rawExpanded = !rawExpanded }) {
                                Text(
                                    if (rawExpanded) {
                                        stringResource(R.string.action_collapse)
                                    } else {
                                        stringResource(R.string.action_expand)
                                    }
                                )
                            }
                        }
                        if (rawExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SelectionContainer {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0F172A))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = prettyPayload,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFFE2E8F0)
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun formatDetailTime(
    context: Context,
    receivedAt: java.time.Instant,
    zoneId: ZoneId,
): String {
    val locale = localeFrom(context)
    val is24Hour = DateFormat.is24HourFormat(context)
    val skeleton = if (is24Hour) "yMMMdHms" else "yMMMd hms"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    val formatter = DateTimeFormatter.ofPattern(pattern, locale)
    return formatter.format(receivedAt.atZone(zoneId))
}

private fun localeFrom(context: Context): Locale {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.resources.configuration.locales.get(0)
    } else {
        @Suppress("DEPRECATION")
        context.resources.configuration.locale
    }
}

private fun prettyJson(rawPayload: String): String {
    return runCatching { JSONObject(rawPayload).toString(2) }.getOrDefault(rawPayload)
}
