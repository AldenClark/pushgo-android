package io.ethan.pushgo.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.ethan.pushgo.R
import io.ethan.pushgo.ui.screens.PushGoAlertDialog

/** Rendering-only foreground confirmation; cancellation never submits a delete. */
@Composable
fun DeleteConfirmationDialog(summary: String, onConfirm: () -> Unit, onCancel: () -> Unit, bodyText: String? = null) {
    PushGoAlertDialog(
        onDismissRequest = onCancel,
        paneTitle = stringResource(R.string.action_delete),
        title = { Text(stringResource(R.string.action_delete)) },
        text = {
            Text(bodyText ?: if (summary.isBlank()) {
                stringResource(R.string.delete_confirmation_untitled_body)
            } else {
                stringResource(R.string.delete_confirmation_body, summary)
            })
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("action.delete.confirm")) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("action.delete.cancel")) {
                Text(stringResource(R.string.label_cancel))
            }
        },
    )
}
