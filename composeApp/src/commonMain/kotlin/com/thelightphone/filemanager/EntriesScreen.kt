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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.launch

@Composable
fun EntriesScreen(
    client: HttpClient,
    currentPath: String,
    onNavigateTo: (String) -> Unit,
    onBack: () -> Unit
) {
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

    val gridState = rememberLazyGridState()
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
                }
                if (response.status.isSuccess()) {
                    client.post("${getBaseUrl()}/api/notify/$currentPath")
                    loadEntries(1)
                }
            }
            isUploading = false
        }
    }

    // Infinite scroll
    LaunchedEffect(gridState, currentPath) {
        snapshotFlow {
            val layoutInfo = gridState.layoutInfo
            val totalItemsNumber = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleItemIndex >= totalItemsNumber - 3
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && !isLoadingMore && hasMorePages && !isLoading) {
                loadEntries(currentPage + 1, append = true)
            }
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
                    onClick = { triggerFilePicker { name, bytes -> uploadFile(name, bytes) } },
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
                    Button(onClick = { selectedPaths = emptySet() }) {
                        Text("Clear")
                    }
                    Button(onClick = ::downloadSelected) {
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
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = { loadEntries(currentPage) }) {
                        Text("Retry")
                    }
                }
            }
            else -> {
                EntryList(
                    entries = allEntries,
                    gridState = gridState,
                    isLoadingMore = isLoadingMore,
                    hasMorePages = hasMorePages,
                    selectedPaths = selectedPaths,
                    onEntryClick = { entry ->
                        if (entry.type == EntryType.Directory) {
                            onNavigateTo(entry.path)
                        } else {
                            toggleSelection(entry.path)
                        }
                    }
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
