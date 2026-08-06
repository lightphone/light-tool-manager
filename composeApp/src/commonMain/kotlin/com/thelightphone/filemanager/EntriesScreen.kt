package com.thelightphone.filemanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

// Handles FileBrowserSpec/DropboxSpec/ConfiguratorSpec pages (any DataViewSpec that isn't a
// RootViewSpec) until those get their own dedicated screens. Owns subfolder drill-down
// internally: onBack only bubbles up once the user backs out of the page's own root.
@Composable
fun EntriesScreen(
    client: HttpClient,
    spec: DataViewSpec,
    onBack: () -> Unit
) {
    val rootPath = remember(spec) { spec.path.joinToString("/") }
    var currentPath by remember(spec) { mutableStateOf(rootPath) }

    var allEntries by remember(currentPath) { mutableStateOf<List<Entry>>(emptyList()) }
    var isLoading by remember(currentPath) { mutableStateOf(true) }
    var isLoadingMore by remember(currentPath) { mutableStateOf(false) }
    var error by remember(currentPath) { mutableStateOf<String?>(null) }
    var currentPage by remember(currentPath) { mutableStateOf(1) }
    var hasMorePages by remember(currentPath) { mutableStateOf(true) }
    var selectedPaths by remember(currentPath) { mutableStateOf<Set<String>>(emptySet()) }
    var isReadOnly by remember(currentPath) { mutableStateOf(true) }
    var isUploading by remember(currentPath) { mutableStateOf(false) }
    val pageSize = 20

    val coroutineScope = rememberCoroutineScope()

    fun loadEntries(page: Int, append: Boolean = false) {
        coroutineScope.launch {
            if (append) {
                isLoadingMore = true
            } else {
                isLoading = true
                error = null
            }

            try {
                val response: PaginatedResponse<Entry> =
                    client.get("${getBaseUrl()}/api/files/$currentPath") {
                        parameter("page", page)
                        parameter("size", pageSize)
                        parameter("sortBy", "NAME")
                        parameter("sortOrder", "ASC")
                    }.body()

                allEntries = if (append) {
                    allEntries + response.data
                } else {
                    response.data
                }

                currentPage = page
                hasMorePages = response.pagination.hasNext
            } catch (e: Throwable) {
                error = "Failed to load: ${e.message}"
            }
            isLoading = false
            isLoadingMore = false
        }
    }

    fun toggleSelection(path: String) {
        selectedPaths = if (selectedPaths.contains(path)) {
            selectedPaths - path
        } else {
            selectedPaths + path
        }
    }

    fun downloadSelected() {
        if (selectedPaths.isEmpty()) return
        coroutineScope.launch {
            runCatching {
                val response = client.post("${getBaseUrl()}/api/download") {
                    contentType(ContentType.Application.Json)
                    setBody(DownloadRequest(paths = selectedPaths.toList()))
                }
                if (response.status.isSuccess()) {
                    val tokenResponse = response.body<DownloadTokenResponse>()
                    val keyParam = getApiKey()?.let { "?key=$it" } ?: ""
                    triggerDownload("${getBaseUrl()}/api/download-zip/${tokenResponse.token}$keyParam")
                    selectedPaths = emptySet()
                }
            }
        }
    }

    fun uploadFile(fileName: String, bytes: ByteArray) {
        coroutineScope.launch {
            isUploading = true
            runCatching {
                val response = client.post("${getBaseUrl()}/api/upload/$currentPath/$fileName") {
                    contentType(ContentType.Application.OctetStream)
                    setBody(ByteReadChannel(bytes))
                    timeout {
                        requestTimeoutMillis = 5.minutes.inWholeMilliseconds
                    }
                }
                if (response.status.isSuccess()) {
                    client.post("${getBaseUrl()}/api/notify/$currentPath")
                    loadEntries(1)
                }
            }
            isUploading = false
        }
    }

    // Initial load + fetch meta
    LaunchedEffect(currentPath) {
        loadEntries(1)
        isReadOnly = runCatching {
            val meta: DirectoryMeta = client.get("${getBaseUrl()}/api/meta/$currentPath").body()
            meta.readOnly
        }.getOrElse { true }
    }

    fun navigateBack() {
        if (currentPath == rootPath) {
            onBack()
        } else {
            currentPath = currentPath.substringBeforeLast("/")
        }
    }

    EntriesScreenContent(
        currentPath = currentPath,
        entries = allEntries,
        isLoading = isLoading,
        isLoadingMore = isLoadingMore,
        error = error,
        hasMorePages = hasMorePages,
        selectedPaths = selectedPaths,
        isReadOnly = isReadOnly,
        isUploading = isUploading,
        onBack = ::navigateBack,
        onRetry = { loadEntries(currentPage) },
        onLoadMore = { loadEntries(currentPage + 1, append = true) },
        onEntryClick = { entry ->
            if (entry.type == EntryType.Directory) {
                // entry.path is already the full absolute path from the server, not relative
                // to currentPath, so it can be used directly as the new location.
                currentPath = entry.path
            } else {
                toggleSelection(entry.path)
            }
        },
        onClearSelection = { selectedPaths = emptySet() },
        onDownloadSelected = ::downloadSelected,
        onUpload = ::uploadFile
    )
}

// Renders EntriesScreen from plain state and callbacks, with no HttpClient dependency,
// so it can be driven by fake data in a @Preview.
@Composable
fun EntriesScreenContent(
    currentPath: String,
    entries: List<Entry>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    error: String?,
    hasMorePages: Boolean,
    selectedPaths: Set<String>,
    isReadOnly: Boolean,
    isUploading: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onEntryClick: (Entry) -> Unit,
    onClearSelection: () -> Unit,
    onDownloadSelected: () -> Unit,
    onUpload: (fileName: String, bytes: ByteArray) -> Unit
) {
    val gridState = rememberLazyGridState()

    // Infinite scroll
    LaunchedEffect(gridState, currentPath) {
        snapshotFlow {
            val layoutInfo = gridState.layoutInfo
            val totalItemsNumber = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleItemIndex >= totalItemsNumber - 3
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && !isLoadingMore && hasMorePages && !isLoading) {
                onLoadMore()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text("Back")
            }
            if (!isReadOnly) {
                Button(
                    onClick = { triggerFilePicker { name, bytes -> onUpload(name, bytes) } },
                    enabled = !isUploading
                ) {
                    Text(if (isUploading) "Uploading..." else "Upload")
                }
            }
        }

        // Selection bar
        if (selectedPaths.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${selectedPaths.size} selected",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onClearSelection) {
                        Text("Clear")
                    }
                    Button(onClick = onDownloadSelected) {
                        Text("Download ZIP")
                    }
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
                EntryList(
                    entries = entries,
                    gridState = gridState,
                    isLoadingMore = isLoadingMore,
                    hasMorePages = hasMorePages,
                    selectedPaths = selectedPaths,
                    onEntryClick = onEntryClick
                )
            }
        }
    }
}

@Composable
fun EntryList(
    entries: List<Entry>,
    gridState: LazyGridState,
    isLoadingMore: Boolean,
    hasMorePages: Boolean,
    selectedPaths: Set<String>,
    onEntryClick: (Entry) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize()
    ) {
        items(entries.size) { i ->
            EntryListItem(
                entry = entries[i],
                isSelected = selectedPaths.contains(entries[i].path),
                onClick = onEntryClick
            )
        }

        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            "Loading more...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (!hasMorePages && entries.isNotEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "End of list",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun EntryListItem(
    entry: Entry,
    isSelected: Boolean,
    onClick: (Entry) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .clickable { onClick(entry) },
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 8.dp else 4.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            if (entry.type == EntryType.Image || entry.type == EntryType.Video) {
                AsyncImage(
                    model = "${getBaseUrl()}/api/thumbnail/${entry.path}?type=${entry.type}",
                    contentDescription = entry.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    entry.type.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun EntryListPreview() {
    val gridState = rememberLazyGridState()
    AppTheme {
        Box(Modifier.fillMaxSize()) {
            EntryList(
                entries = listOf(
                    Entry(EntryType.Directory, "vacation", "photos/vacation", 0L, 0L),
                    Entry(EntryType.Image, "photo.jpg", "photos/photo.jpg", 0L, 1024L),
                    Entry(EntryType.Audio, "song.mp3", "music/song.mp3", 0L, 4096L),
                    Entry(EntryType.Text, "notes.txt", "docs/notes.txt", 0L, 256L),
                ),
                gridState = gridState,
                isLoadingMore = false,
                hasMorePages = false,
                selectedPaths = setOf("photos/photo.jpg"),
                onEntryClick = {}
            )
        }
    }
}

private val previewEntries = listOf(
    Entry(EntryType.Directory, "vacation", "photos/vacation", 0L, 0L),
    Entry(EntryType.Image, "photo.jpg", "photos/photo.jpg", 0L, 1024L),
    Entry(EntryType.Audio, "song.mp3", "photos/song.mp3", 0L, 4096L),
    Entry(EntryType.Text, "notes.txt", "photos/notes.txt", 0L, 256L),
)

@Preview(device = Devices.DESKTOP)
@Composable
fun EntriesScreenContentPreview() {
    AppTheme {
        EntriesScreenContent(
            currentPath = "photos",
            entries = previewEntries,
            isLoading = false,
            isLoadingMore = false,
            error = null,
            hasMorePages = true,
            selectedPaths = setOf("photos/photo.jpg"),
            isReadOnly = false,
            isUploading = false,
            onBack = {},
            onRetry = {},
            onLoadMore = {},
            onEntryClick = {},
            onClearSelection = {},
            onDownloadSelected = {},
            onUpload = { _, _ -> }
        )
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun EntriesScreenContentLoadingPreview() {
    AppTheme {
        EntriesScreenContent(
            currentPath = "photos",
            entries = emptyList(),
            isLoading = true,
            isLoadingMore = false,
            error = null,
            hasMorePages = false,
            selectedPaths = emptySet(),
            isReadOnly = true,
            isUploading = false,
            onBack = {},
            onRetry = {},
            onLoadMore = {},
            onEntryClick = {},
            onClearSelection = {},
            onDownloadSelected = {},
            onUpload = { _, _ -> }
        )
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun EntriesScreenContentErrorPreview() {
    AppTheme {
        EntriesScreenContent(
            currentPath = "photos",
            entries = emptyList(),
            isLoading = false,
            isLoadingMore = false,
            error = "Failed to load: connection refused",
            hasMorePages = false,
            selectedPaths = emptySet(),
            isReadOnly = true,
            isUploading = false,
            onBack = {},
            onRetry = {},
            onLoadMore = {},
            onEntryClick = {},
            onClearSelection = {},
            onDownloadSelected = {},
            onUpload = { _, _ -> }
        )
    }
}
