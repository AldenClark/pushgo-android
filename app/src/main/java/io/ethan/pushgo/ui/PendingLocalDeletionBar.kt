package io.ethan.pushgo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ethan.pushgo.R
import io.ethan.pushgo.ui.accessibility.pushGoLiveRegion
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import kotlinx.coroutines.delay
import kotlin.math.ceil

@Composable
fun PendingLocalDeletionBar(
    pendingDeletion: PendingLocalDeletionCoordinator.PendingDeletion?,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = pendingDeletion != null,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
    ) {
        val entry = pendingDeletion ?: return@AnimatedVisibility
        val uiColors = PushGoThemeExtras.colors
        val remainingSeconds by produceState(
            initialValue = remainingSecondsFor(entry),
            key1 = entry.id,
            key2 = entry.deadlineElapsedRealtimeMillis,
        ) {
            while (true) {
                value = remainingSecondsFor(entry)
                if (value <= 0) {
                    break
                }
                delay(200)
            }
        }

        Surface(
            modifier = Modifier
                .pushGoLiveRegion()
                .semantics(mergeDescendants = true) {
                    contentDescription = context.getString(
                        R.string.a11y_pending_deletion_summary,
                        entry.summary,
                        remainingSeconds,
                    )
                },
            color = uiColors.surfaceRaised,
            contentColor = uiColors.textPrimary,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, uiColors.dividerSubtle),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "· ${remainingSeconds}s",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.2.sp),
                    color = uiColors.textSecondary,
                )
                TextButton(onClick = onUndo) {
                    Text(text = stringResource(R.string.label_undo))
                }
            }
        }
    }
}

private fun remainingSecondsFor(
    pendingDeletion: PendingLocalDeletionCoordinator.PendingDeletion,
): Int {
    val remainingMillis = (pendingDeletion.deadlineElapsedRealtimeMillis - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    return ceil(remainingMillis / 1_000.0).toInt()
}
