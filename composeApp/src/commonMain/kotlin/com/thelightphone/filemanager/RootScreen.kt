package com.thelightphone.filemanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onAlert: (FileManagerAlert) -> Unit = ::pushGlobalAlert,
) {
    var pages by remember(spec) { mutableStateOf<List<DataViewSpec>>(emptyList()) }
    var isLoading by remember(spec) { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    fun load() {
        coroutineScope.launch {
            isLoading = true
            val path = spec?.path ?: "."
            runCatching { client.get("${getBaseUrl()}/api/tree/$path").body<List<DataViewSpec>>() }
                .onSuccess { pages = it }
                .onFailure {
                    val alert = FileManagerAlert("Failed to load: ${it.message}")
                    onAlert(alert)
                }
            isLoading = false
        }
    }

    LaunchedEffect(spec) { load() }

    RootScreenContent(
        isLoading = isLoading,
        pages = pages,
        onPageClick = onPageClick,
    )
}

// Stateless rendering for RootScreen, with no HttpClient dependency, so it can be driven by
// fake data in a @Preview.
@Composable
fun RootScreenContent(
    isLoading: Boolean,
    pages: List<DataViewSpec>,
    onPageClick: (DataViewSpec) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp)
    ) {
        pages.forEach { page ->
            Text(
                text = page.label,
                textAlign = TextAlign.Center,
                fontSize = 40.sp,
                modifier = Modifier
                    .clickable { onPageClick(page) }
                    .padding(vertical = 14.dp, horizontal = 18.dp)
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
        RootScreenContent(isLoading = true, pages = emptyList(), onPageClick = {})
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun RootScreenNestedPagePreview() {
    AppTheme {
        RootScreenContent(
            isLoading = false,
            pages = listOf(
                FileBrowserSpec("Vacation Photos", "second/photos"),
                FileBrowserSpec("Documents", "second/docs"),
            ),
            onPageClick = {},
        )
    }
}
