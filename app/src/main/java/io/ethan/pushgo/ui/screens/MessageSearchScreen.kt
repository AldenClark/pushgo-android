package io.ethan.pushgo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import io.ethan.pushgo.R
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MessageBodyResolver
import io.ethan.pushgo.markdown.MessagePreviewExtractor
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.rememberBottomGestureInset
import io.ethan.pushgo.ui.viewmodel.MessageSearchViewModel
import java.time.ZoneId

@Composable
fun MessageSearchScreen(
    navController: NavHostController,
    factory: PushGoViewModelFactory,
) {
    val viewModel: MessageSearchViewModel = viewModel(factory = factory)
    val query by viewModel.queryState.collectAsState()
    val results by viewModel.results.collectAsState()
    val bottomGestureInset = rememberBottomGestureInset()

    LaunchedEffect(query, results.size) {
        PushGoAutomation.writeEvent(
            type = "search.results_updated",
            command = null,
            details = org.json.JSONObject()
                .put("search_query", query)
                .put("result_count", results.size),
        )
    }

    Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::updateQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.label_search)) },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomGestureInset + 16.dp),
        ) {
            items(results, key = { it.id }) { message ->
                SearchResultRow(
                    message = message,
                    onClick = { navController.navigate("detail/${message.id}") },
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(message: PushMessage, onClick: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val timeText = remember(message.receivedAt, configuration) {
        formatMessageTime(context, message.receivedAt, ZoneId.systemDefault())
    }
    val resolvedBody = remember(message.rawPayloadJson, message.body) {
        MessageBodyResolver.resolve(message.rawPayloadJson, message.body)
    }
    val bodyPreview = remember(resolvedBody.rawText) {
        MessagePreviewExtractor.listPreview(resolvedBody.rawText)
    }
    val hasBodyText = bodyPreview.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = message.title.ifBlank { stringResource(R.string.label_no_title) },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasBodyText) {
            Text(
                text = bodyPreview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
