package io.ethan.pushgo.ui.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ethan.pushgo.ui.theme.PushGoSheetContainerColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PushGoModalBottomSheet(
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit,
    sheetState: SheetState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedSheetState = sheetState ?: rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        sheetState = resolvedSheetState,
        containerColor = PushGoSheetContainerColor(),
        tonalElevation = 0.dp,
        contentWindowInsets = { WindowInsets(0) },
        content = content,
    )
}
