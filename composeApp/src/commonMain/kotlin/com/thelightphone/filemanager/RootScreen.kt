package com.thelightphone.filemanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.launch

// Renders whichever page `spec` points at: the true top-level root (spec == null) or a nested
// RootViewSpec page. Fetches its own children (one level) via GET /api/tree, keyed by `spec` so
// switching pages triggers a fresh load — mirrors the stateful/stateless split used by
// EntriesScreen/EntriesScreenContent, so both the true root and any nested branch page share the
// exact same fetch logic with no special-casing.
@Composable
fun RootScreen(
    client: HttpClient,
    spec: DataViewSpec?,
    onPageClick: (DataViewSpec) -> Unit,
    onBack: () -> Unit
) {
    var pages by remember(spec) { mutableStateOf<List<DataViewSpec>>(emptyList()) }
    var isLoading by remember(spec) { mutableStateOf(true) }
    var error by remember(spec) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun load() {
        coroutineScope.launch {
            isLoading = true
            error = null
            val path = spec?.path?.joinToString("/") ?: "."
            runCatching { client.get("${getBaseUrl()}/api/tree/$path").body<List<DataViewSpec>>() }
                .onSuccess { pages = it }
                .onFailure { error = "Failed to load: ${it.message}" }
            isLoading = false
        }
    }

    LaunchedEffect(spec) { load() }

    RootScreenContent(
        isLoading = isLoading,
        error = error,
        pages = pages,
        onRetry = { load() },
        onPageClick = onPageClick,
        // No parent to return to at the true top level.
        onBack = if (spec != null) onBack else null
    )
}

// Stateless rendering for RootScreen, with no HttpClient dependency, so it can be driven by
// fake data in a @Preview.
@Composable
fun RootScreenContent(
    isLoading: Boolean,
    error: String?,
    pages: List<DataViewSpec>,
    onRetry: () -> Unit,
    onPageClick: (DataViewSpec) -> Unit,
    onBack: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("Back")
                }
            }
        }

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
                }
            }
            error != null -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
            else -> {
                RootList(pages = pages, onPageClick = onPageClick)
            }
        }
    }
}

@Composable
fun RootList(
    pages: List<DataViewSpec>,
    onPageClick: (DataViewSpec) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        pages.forEach { page ->
            Text(
                text = page.label,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPageClick(page) }
                    .padding(vertical = 12.dp, horizontal = 8.dp)
            )
        }
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun RootListPreview() {
    AppTheme {
        RootList(
            pages = listOf(
                RootViewSpec("Music"),
                RootViewSpec("Photos"),
                RootViewSpec("Video"),
            ),
            onPageClick = {}
        )
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun RootScreenLoadingPreview() {
    AppTheme {
        RootScreenContent(isLoading = true, error = null, pages = emptyList(), onRetry = {}, onPageClick = {})
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun RootScreenErrorPreview() {
    AppTheme {
        RootScreenContent(
            isLoading = false,
            error = "Failed to load root: connection refused",
            pages = emptyList(),
            onRetry = {},
            onPageClick = {}
        )
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun RootScreenNestedPagePreview() {
    AppTheme {
        RootScreenContent(
            isLoading = false,
            error = null,
            pages = listOf(
                FileBrowserSpec("Vacation Photos", listOf("second", "photos")),
                FileBrowserSpec("Documents", listOf("second", "docs")),
            ),
            onRetry = {},
            onPageClick = {},
            onBack = {}
        )
    }
}
