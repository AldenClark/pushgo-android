package io.ethan.pushgo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.ethan.pushgo.ui.theme.PushGoThemeExtras

@Composable
internal fun PushGoSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholderText: String,
    modifier: Modifier = Modifier,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val uiColors = PushGoThemeExtras.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(uiColors.fieldContainer),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Spacer(modifier = Modifier.width(16.dp))
        androidx.compose.material3.Icon(Icons.Default.Search, null, tint = uiColors.iconMuted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = uiColors.textPrimary),
            singleLine = true,
            cursorBrush = SolidColor(uiColors.accentPrimary),
            decorationBox = { innerTextField ->
                TextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = innerTextField,
                    enabled = true,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = remember { MutableInteractionSource() },
                    placeholder = { Text(placeholderText, color = uiColors.placeholderText) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = uiColors.fieldContainer,
                        unfocusedContainerColor = uiColors.fieldContainer,
                        focusedIndicatorColor = uiColors.fieldContainer,
                        unfocusedIndicatorColor = uiColors.fieldContainer,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 0.dp),
                    container = {},
                )
            },
        )
        trailingContent()
    }
}
