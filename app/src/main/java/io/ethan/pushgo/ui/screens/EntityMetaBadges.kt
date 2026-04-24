package io.ethan.pushgo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ethan.pushgo.R
import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.ui.theme.PushGoThemeExtras

@Composable
fun PushGoChannelMetaChip(
    channelDisplayName: String,
    modifier: Modifier = Modifier,
) {
    val uiColors = PushGoThemeExtras.colors
    val normalized = channelDisplayName.trim()
    if (normalized.isEmpty()) return
    PushGoMetaChip(
        text = "#${normalized}",
        modifier = modifier,
        backgroundColor = uiColors.surfaceSunken,
        foregroundColor = uiColors.textSecondary,
    )
}

@Composable
fun PushGoDecryptionMetaChip(
    decryptionState: DecryptionState,
    modifier: Modifier = Modifier,
) {
    val uiColors = PushGoThemeExtras.colors
    val (labelRes, backgroundColor, foregroundColor) = when (decryptionState) {
        DecryptionState.DECRYPT_OK -> Triple(
            R.string.label_decryption_state_ok_short,
            uiColors.stateInfo.background,
            uiColors.stateInfo.foreground,
        )

        DecryptionState.DECRYPT_FAILED -> Triple(
            R.string.label_decryption_state_failed_short,
            uiColors.stateDanger.background,
            uiColors.stateDanger.foreground,
        )

        DecryptionState.NOT_CONFIGURED -> Triple(
            R.string.label_decryption_state_not_configured_short,
            uiColors.stateWarning.background,
            uiColors.stateWarning.foreground,
        )

        DecryptionState.ALG_MISMATCH -> Triple(
            R.string.label_decryption_state_alg_mismatch_short,
            uiColors.stateWarning.background,
            uiColors.stateWarning.foreground,
        )
    }
    PushGoMetaChip(
        text = androidx.compose.ui.res.stringResource(labelRes),
        modifier = modifier,
        backgroundColor = backgroundColor,
        foregroundColor = foregroundColor,
    )
}

@Composable
fun PushGoMetaChip(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: androidx.compose.ui.graphics.Color,
    foregroundColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = foregroundColor,
            maxLines = 1,
        )
    }
}
