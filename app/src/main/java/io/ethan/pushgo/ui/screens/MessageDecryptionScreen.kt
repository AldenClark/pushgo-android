package io.ethan.pushgo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import io.ethan.pushgo.R
import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.rememberBottomGestureInset
import io.ethan.pushgo.ui.theme.pushGoPrimaryButtonElevation
import io.ethan.pushgo.ui.theme.pushGoPrimaryButtonColors
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import io.ethan.pushgo.ui.theme.pushGoOutlinedTextFieldColors
import io.ethan.pushgo.ui.theme.pushGoSegmentedButtonColors
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel
import io.ethan.pushgo.ui.announceForAccessibility

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag

@Composable
fun MessageDecryptionScreen(
    navController: NavController,
    factory: PushGoViewModelFactory,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val uiColors = PushGoThemeExtras.colors
    val bottomGestureInset = rememberBottomGestureInset()

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("screen.settings.decryption")
            .background(uiColors.surfaceBase)
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
                    tint = uiColors.textPrimary
                )
            }
            Text(
                text = stringResource(R.string.section_decryption),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                color = uiColors.textPrimary
            )
        }
        
        PushGoDividerSubtle()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = bottomGestureInset + 24.dp)
        ) {
            DecryptionKeyForm(
                viewModel = viewModel,
                onSave = {
                    viewModel.saveDecryptionConfig()
                    navController.popBackStack()
                },
                fillRemaining = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun DecryptionKeyForm(
    viewModel: SettingsViewModel,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    fillRemaining: Boolean,
) {
    val uiColors = PushGoThemeExtras.colors
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            KeyEncoding.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == viewModel.keyEncoding,
                    onClick = { viewModel.updateKeyEncoding(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = KeyEncoding.entries.size),
                    colors = pushGoSegmentedButtonColors(),
                ) {
                    Text(stringResource(keyEncodingLabel(option)))
                }
            }
        }

        OutlinedTextField(
            value = viewModel.decryptionKeyInput,
            onValueChange = viewModel::updateDecryptionKeyInput,
            label = {
                Text(
                    text = stringResource(R.string.label_notification_key).uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = uiColors.stateInfo.foreground,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.0.sp,
                    ),
                )
            },
            placeholder = {
                Text(
                    text = stringResource(R.string.label_notification_key),
                    color = uiColors.placeholderText,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("field.settings.decryption.key"),
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(
                    modifier = Modifier.testTag("action.settings.decryption.toggle_visibility"),
                    onClick = { showKey = !showKey },
                ) {
                    Icon(
                        imageVector = if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (showKey) {
                            stringResource(R.string.label_hide_key)
                        } else {
                            stringResource(R.string.label_show_key)
                        },
                        tint = uiColors.textSecondary
                    )
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = pushGoOutlinedTextFieldColors()
        )

        Text(
            text = stringResource(R.string.label_decryption_hint),
            style = MaterialTheme.typography.bodySmall,
            color = uiColors.textSecondary,
            modifier = Modifier.fillMaxWidth(),
        )

        if (fillRemaining) {
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onSave,
            enabled = !viewModel.isSavingDecryption,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("action.settings.decryption.save")
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = pushGoPrimaryButtonColors(),
            elevation = pushGoPrimaryButtonElevation()
        ) {
            Icon(
                imageVector = Icons.Outlined.Save,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.label_save_decryption),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

private fun keyEncodingLabel(encoding: KeyEncoding): Int {
    return when (encoding) {
        KeyEncoding.BASE64 -> R.string.label_key_encoding_base64
        KeyEncoding.HEX -> R.string.label_key_encoding_hex
        KeyEncoding.PLAINTEXT -> R.string.label_key_encoding_plaintext
    }
}
