package io.ethan.pushgo.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.ethan.pushgo.R
import io.ethan.pushgo.notifications.CustomRingtoneManager
import io.ethan.pushgo.notifications.RingtoneCatalog
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.ringtone.RingtonePreviewPlayer
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel
import io.ethan.pushgo.ui.announceForAccessibility
import android.widget.Toast

@Composable
fun RingtoneListScreen(
    navController: NavController,
    factory: PushGoViewModelFactory,
) {
    val viewModel: SettingsViewModel = viewModel(factory = factory)
    val context = LocalContext.current
    val ringtonePlayer = remember { RingtonePreviewPlayer(context) }

    LaunchedEffect(context) {
        viewModel.refreshCustomRingtones(context)
    }

    LaunchedEffect(viewModel.errorMessage) {
        val message = viewModel.errorMessage
        if (message != null) {
            val text = message.resolve(context)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            announceForAccessibility(context, text)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(viewModel.successMessage) {
        val message = viewModel.successMessage
        if (message != null) {
            val text = message.resolve(context)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            announceForAccessibility(context, text)
            viewModel.consumeSuccess()
        }
    }
    
    val currentRingtoneId = viewModel.ringtoneId
    val customRingtones = viewModel.customRingtones

    val launcher = rememberLauncherForActivityResult(
        contract = object : ActivityResultContracts.GetContent() {
            override fun createIntent(context: Context, input: String): Intent {
                val intent = super.createIntent(context, input)
                return Intent.createChooser(intent, context.getString(R.string.title_select_audio))
            }
        }
    ) { uri ->
        if (uri != null) {
            viewModel.addCustomRingtone(context, uri)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ringtonePlayer.stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.label_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.section_ringtone),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            IconButton(onClick = { launcher.launch("audio/*") }) {
                 Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.label_add_custom_ringtone),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
             item {
                Text(
                    text = "MY SOUNDS",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp)
                )
            }
            
            if (customRingtones.isEmpty()) {
                item {
                    Text(
                        text = "No custom sounds",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, bottom = 16.dp)
                    )
                }
            } else {
                items(customRingtones) { ringtone ->
                    val isSelected = ringtone.id == currentRingtoneId
                    RingtoneItem(
                        title = ringtone.name,
                        subtitle = null,
                        isSelected = isSelected,
                        onClick = {
                            viewModel.updateRingtoneId(ringtone.id)
                            val uri = CustomRingtoneManager.getRingtoneUri(context, ringtone.id)
                            if (uri != null) {
                                ringtonePlayer.play(ringtone.id, uri)
                            }
                        },
                        onDelete = {
                            viewModel.deleteCustomRingtone(context, ringtone.id)
                        }
                    )
                }
            }
            
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )
            }

            item {
                Text(
                    text = "PIXEL SOUNDS",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp)
                )
            }

            items(RingtoneCatalog.catalog) { ringtone ->
                val isSelected = ringtone.id == currentRingtoneId
                RingtoneItem(
                    title = stringResource(ringtone.displayNameRes),
                    subtitle = ringtone.filename,
                    isSelected = isSelected,
                    onClick = {
                        viewModel.updateRingtoneId(ringtone.id)
                        ringtonePlayer.play(ringtone)
                    },
                    onDelete = null
                )
            }
            
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun RingtoneItem(
    title: String,
    subtitle: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            if (onDelete != null) {
                Spacer(modifier = Modifier.width(16.dp))
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
            }
        }
    }
}
