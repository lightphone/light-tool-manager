package com.thelightphone.filemanager

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.thelightphone.filemanager.composeapp.generated.resources.Res
import com.thelightphone.filemanager.composeapp.generated.resources.ic_reverse_order_white
import com.thelightphone.filemanager.composeapp.generated.resources.ic_trash
import com.thelightphone.filemanager.composeapp.generated.resources.ic_text_file
import com.thelightphone.filemanager.composeapp.generated.resources.ic_audio_waveform
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.launch
import kotlinx.datetime.format
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.vectorResource
import kotlin.time.Duration.Companion.minutes

// Handles FileBrowserSpec/DropboxSpec/ConfiguratorSpec pages (any DataViewSpec that isn't a
// RootViewSpec) until those get their own dedicated screens. Owns subfolder drill-down
// internally: onBack only bubbles up once the user backs out of the page's own root.
@Composable
fun EntriesScreen(
    client: HttpClient,
    spec: FileBrowserSpec,
    onAlert: (FileManagerAlert) -> Unit = ::pushGlobalAlert
) {
    val rootPath = remember(spec) { spec.asPathString }
    var currentPath by remember(spec) { mutableStateOf(rootPath) }
    var allEntries by remember(currentPath) { mutableStateOf<List<Entry>>(emptyList()) }
    var isLoading by remember(currentPath) { mutableStateOf(true) }
    var isLoadingMore by remember(currentPath) { mutableStateOf(false) }
    var sort by remember(currentPath) { mutableStateOf(Sort()) }
    var currentPage by remember(currentPath) { mutableStateOf(1) }
    var hasMorePages by remember(currentPath) { mutableStateOf(true) }
    var selectedPaths by remember(currentPath) { mutableStateOf<Set<String>>(emptySet()) }
    var isReadOnly by remember(currentPath) { mutableStateOf(true) }
    var isUploading by remember(currentPath) { mutableStateOf(false) }
    val pageSize = 20

    val coroutineScope = rememberCoroutineScope()

    fun loadEntries(newSort: Sort, page: Int, append: Boolean = false) {
        sort = newSort
        coroutineScope.launch {
            if (append) {
                isLoadingMore = true
            } else {
                isLoading = true
            }

            try {
                val response: PaginatedResponse<Entry> =
                    client.get("${getBaseUrl()}/api/files/$currentPath") {
                        parameter("page", page)
                        parameter("size", pageSize)
                        parameter("sortBy", sort.sortBy)
                        parameter("sortOrder", sort.sortOrder)
                    }.body()

                allEntries = if (append) {
                    allEntries + response.data
                } else {
                    response.data
                }

                currentPage = page
                hasMorePages = response.pagination.hasNext
            } catch (e: Throwable) {
                onAlert(FileManagerAlert("Failed to load: ${e.message}"))
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

    // Initial load + fetch meta
    LaunchedEffect(currentPath) {
        loadEntries(sort, 1)
        isReadOnly = runCatching {
            val meta: DirectoryMeta = client.get("${getBaseUrl()}/api/meta/$currentPath").body()
            meta.readOnly
        }.getOrElse { true }
    }

    fun navigateBack(): Boolean {
        return if (currentPath == rootPath) {
            false
        } else {
            currentPath = currentPath.substringBeforeLast("/")
            true
        }
    }

    BackClickInterceptor { navigateBack() }

    fun onUpload(fileName: String, bytes: ByteArray) {
        coroutineScope.launch {
            uploadFile(
                fileName,
                bytes,
                currentPath,
                client,
                onIsUploading = { isUploading = it },
                onSuccess = { loadEntries(sort, 1) }
            )
        }
    }

    EntriesScreenContent(
        spec = spec,
        sort = sort,
        currentPath = currentPath,
        entries = allEntries,
        isLoading = isLoading,
        isLoadingMore = isLoadingMore,
        hasMorePages = hasMorePages,
        selectedPaths = selectedPaths,
        isReadOnly = isReadOnly,
        isUploading = isUploading,
        onRetry = { loadEntries(it, 1) },
        onLoadMore = { loadEntries(sort, currentPage + 1, append = true) },
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
        onUpload = ::onUpload
    )
}

@Composable
private fun EntriesScreenContent(
    spec: FileBrowserSpec,
    currentPath: String,
    entries: List<Entry>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMorePages: Boolean,
    selectedPaths: Set<String>,
    isReadOnly: Boolean,
    isUploading: Boolean,
    sort: Sort,
    onRetry: (Sort) -> Unit,
    onLoadMore: () -> Unit,
    onEntryClick: (Entry) -> Unit,
    onClearSelection: () -> Unit,
    onDownloadSelected: () -> Unit,
    onUpload: (fileName: String, bytes: ByteArray) -> Unit
) {
    val gridState = rememberLazyGridState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Infinite scroll. isLoadingMore/hasMorePages/isLoading/onLoadMore are read through
    // rememberUpdatedState rather than captured directly: this effect is keyed only on
    // (gridState, currentPath) so its coroutine is launched once per folder and never
    // restarts, which means a directly-captured plain parameter would stay frozen at
    // whatever value it held when the coroutine launched (isLoading, e.g., starts out
    // true) instead of tracking later recompositions.
    val latestIsLoadingMore by rememberUpdatedState(isLoadingMore)
    val latestHasMorePages by rememberUpdatedState(hasMorePages)
    val latestIsLoading by rememberUpdatedState(isLoading)
    val latestOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState, currentPath) {
        snapshotFlow {
            val layoutInfo = gridState.layoutInfo
            val totalItemsNumber = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleItemIndex >= totalItemsNumber - 3
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && !latestIsLoadingMore && latestHasMorePages && !latestIsLoading) {
                latestOnLoadMore()
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = InnerColumnWidth)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClearSelection()
                    true
                } else {
                    false
                }
            }
    ) {
        when {
            isLoading -> {
                SpecHeaderText(spec)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
                }
            }

            else -> {
                EntryList(
                    entries = entries,
                    gridState = gridState,
                    isLoadingMore = isLoadingMore,
                    selectedPaths = selectedPaths,
                    onEntryClick = onEntryClick,
                    scrollingHeader = { SpecHeaderText(spec) }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = InnerColumnWidth)
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        val optionsSelected = selectedPaths.isNotEmpty()
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.weight(1f)) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_reverse_order_white),
                                    contentDescription = "Reverse Sort Order",
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable {
                                            val newOrder =
                                                if (sort.sortOrder == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC
                                            onRetry(sort.copy(sortOrder = newOrder))
                                        }
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_trash),
                                    contentDescription = "Delete",
                                    tint = if (optionsSelected) EnabledColor else DisabledColor,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable(enabled = optionsSelected) {

                                        }
                                )
                                TextButton(
                                    text = "download",
                                    onClick = onDownloadSelected,
                                    enabled = optionsSelected
                                )
                                if (!isReadOnly) {
                                    TextButton(
                                        text = "upload",
                                        onClick = {
                                            triggerFilePicker(onFileSelected = { name, bytes ->
                                                onUpload(
                                                    name,
                                                    bytes
                                                )
                                            })
                                        },
                                        enabled = !isUploading
                                    )
                                }
                            }

                        }
                        Spacer(Modifier.height(30.dp))
                    }
                }
            }
        }
    }
}

suspend fun uploadFile(
    fileName: String,
    bytes: ByteArray,
    currentPath: String,
    client: HttpClient,
    onAlert: (FileManagerAlert) -> Unit = ::pushGlobalAlert,
    onIsUploading: (Boolean) -> Unit,
    onSuccess: () -> Unit,
) {
    onIsUploading(true)
    runCatching {
        val status = uploadOctetStream(
            client,
            "${getBaseUrl()}/api/upload/$currentPath/$fileName",
            bytes,
            5.minutes.inWholeMilliseconds,
        )
        if (status.isSuccess()) {
            client.post("${getBaseUrl()}/api/notify/$currentPath")
            onSuccess()
        } else {
            onAlert(FileManagerAlert("Failed to upload file: $status"))
        }
    }.onFailure { e ->
        // Without this, a timeout/dropped connection/etc. during the upload just vanishes —
        // isUploading flips back to false with no indication anything went wrong.
        onAlert(FileManagerAlert("Failed to upload file: ${e.message}"))
    }
    onIsUploading(false)
}

@Composable
fun EntryList(
    entries: List<Entry>,
    gridState: LazyGridState,
    isLoadingMore: Boolean,
    selectedPaths: Set<String>,
    onEntryClick: (Entry) -> Unit,
    scrollingHeader: @Composable () -> Unit,
    header: @Composable () -> Unit
) {
    val gridItemSize = 180.dp
    val gridSpacing = 8.dp
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Mirrors GridCells.Adaptive's own column-count formula so this always matches what the
        // grid actually renders this frame. The previous approach derived column count from
        // gridState.layoutInfo.visibleItemsInfo, which lags a frame behind the real layout: when
        // resizing across a column-count boundary, the header's span could end up wider than the
        // grid's real column count for that frame. A span that never fits any line permanently
        // stalls layout of every item after it, so the whole grid renders blank.
        val columnCount = remember(maxWidth) {
            (((maxWidth + gridSpacing) / (gridItemSize + gridSpacing)).toInt()).coerceAtLeast(1)
        }
        val oddColumns = columnCount.and(1) == 1
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = gridItemSize),
            horizontalArrangement = Arrangement.spacedBy(gridSpacing),
            verticalArrangement = Arrangement.spacedBy(gridSpacing),
            state = gridState,
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                scrollingHeader()
            }
            stickyHeader { header() }
            fun variant(idx: Int): Boolean {
                return if (oddColumns || columnCount == 0) {
                    idx.and(1) == 1
                } else {
                    val col = idx / columnCount
                    idx.and(1) != col.and(1)
                }
            }
            itemsIndexed(entries) { idx, item ->
                EntryListItem(
                    entry = item,
                    isSelected = selectedPaths.contains(item.path),
                    variant = variant(idx),
                    gridItemSize = gridItemSize,
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
                            Text("Loading more...")
                        }
                    }
                }
            }
        }
    }
}

private val LastModifiedDateFormat = LocalDate.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    chars(". ")
    day(Padding.NONE)
    chars(", ")
    year()
}

// entry.lastModified is epoch millis in UTC; rendered in the viewer's local calendar date.
private fun formatLastModified(epochMillis: Long): String =
    Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .format(LastModifiedDateFormat)

@Composable
fun BoxScope.VideoListItem(entry: Entry) {
    AsyncImage(
        model = "${getBaseUrl()}/api/thumbnail/${entry.path}?type=${entry.type}",
        contentDescription = entry.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
    entry.meta?.get(MetaKeys.DURATION)?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.background,
            fontSize = 20.sp,
            modifier = Modifier.padding(16.dp).align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun BoxScope.FileListItem(entry: Entry) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        val (resource, contentDescription) = when (entry.type) {
            EntryType.Audio -> Res.drawable.ic_audio_waveform to "Audio waveform icon"
            else -> Res.drawable.ic_text_file to "Generic file icon"
        }
        Spacer(Modifier.height(12.dp))
        Image(
            vectorResource(resource),
            contentDescription = contentDescription,
            Modifier.fillMaxSize(0.65f)
        )
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            listOf(entry.title, formatLastModified(entry.lastModified)).forEach {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.background,
                    maxLines = 1,
                    lineHeight = 20.sp,
                    overflow = TextOverflow.MiddleEllipsis
                )
            }
        }
    }
}

@Composable
fun ImageListItem(entry: Entry) {
    AsyncImage(
        model = "${getBaseUrl()}/api/thumbnail/${entry.path}?type=${entry.type}",
        contentDescription = entry.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
}

@Composable
fun EntryListItem(
    entry: Entry,
    variant: Boolean,
    gridItemSize: Dp,
    isSelected: Boolean,
    onClick: (Entry) -> Unit
) {
    val bgColor =
        if (variant) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    Box(Modifier.aspectRatio(1f).background(bgColor).clickable {
        onClick(entry)
    }) {
        when (entry.type) {
            EntryType.Directory -> { /* Directories not supported currently */
            }

            EntryType.Image -> ImageListItem(entry)
            EntryType.Video -> VideoListItem(entry)
            EntryType.GenericFile, EntryType.Text, EntryType.Audio -> FileListItem(entry)
        }
        if (isSelected) {
            Box(Modifier.fillMaxSize().border(5.dp, MaterialTheme.colorScheme.onBackground))
        }
    }
//    Card(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(4.dp)
//            .clickable { onClick(entry) },
//        elevation = CardDefaults.cardElevation(
//            defaultElevation = if (isSelected) 8.dp else 4.dp
//        ),
//        colors = CardDefaults.cardColors(
//            containerColor = if (isSelected)
//                MaterialTheme.colorScheme.primaryContainer
//            else
//                MaterialTheme.colorScheme.surface
//        )
//    ) {
//        Column {
//            if (entry.type == EntryType.Image || entry.type == EntryType.Video) {
//                AsyncImage(
//                    model = "${getBaseUrl()}/api/thumbnail/${entry.path}?type=${entry.type}",
//                    contentDescription = entry.title,
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .height(200.dp)
//                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
//                    contentScale = ContentScale.Crop
//                )
//            }
//
//            Column(
//                modifier = Modifier.padding(16.dp)
//            ) {
//                Text(
//                    entry.title,
//                    style = MaterialTheme.typography.headlineSmall,
//                    fontWeight = FontWeight.Bold
//                )
//
//                Spacer(modifier = Modifier.height(4.dp))
//
//                Text(
//                    entry.type.name,
//                    style = MaterialTheme.typography.bodyMedium,
//                    color = MaterialTheme.colorScheme.onSurfaceVariant
//                )
//            }
//        }
//    }
}

private data class Sort(
    val sortBy: SortBy = SortBy.NAME,
    val sortOrder: SortOrder = SortOrder.ASC
)

private val previewEntries = listOf(
    Entry(EntryType.Directory, "vacation", "photos/vacation", 0L, 0L),
    Entry(EntryType.Image, "photo.jpg", "photos/photo.jpg", 0L, 1024L),
    Entry(EntryType.Audio, "song.mp3", "photos/song.mp3", 0L, 4096L),
    Entry(EntryType.Text, "notes_and_other_important_things.txt", "photos/notes.txt", 0L, 256L),
    Entry(
        EntryType.Video,
        "Video.mp4",
        "photos/Video.mp4",
        0L,
        4096L,
        meta = mapOf(MetaKeys.DURATION to "12:34")
    ),
)

@Preview(device = Devices.DESKTOP)
@Composable
fun EntriesScreenContentPreview() {
    var sort by remember { mutableStateOf(Sort()) }
    val entries by derivedStateOf {
        if (sort.sortOrder == SortOrder.ASC) {
            previewEntries.sortedBy { it.title }
        } else {
            previewEntries.sortedByDescending { it.title }
        }
    }
    val spec = FileBrowserSpec("Files", listOf("files"), "Sample text options.")
    AppTheme {
        EntriesScreenContent(
            spec = spec,
            currentPath = spec.asPathString,
            entries = entries,
            sort = sort,
            isLoading = false,
            isLoadingMore = false,
            hasMorePages = true,
            selectedPaths = setOf("photos/photo.jpg"),
            isReadOnly = false,
            isUploading = false,
            onRetry = { sort = it },
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
    val spec = FileBrowserSpec("Files", listOf("files"), "Sample text options.")
    AppTheme {
        EntriesScreenContent(
            spec = spec,
            currentPath = spec.asPathString,
            sort = Sort(),
            entries = emptyList(),
            isLoading = true,
            isLoadingMore = false,
            hasMorePages = false,
            selectedPaths = emptySet(),
            isReadOnly = true,
            isUploading = false,
            onRetry = {},
            onLoadMore = {},
            onEntryClick = {},
            onClearSelection = {},
            onDownloadSelected = {},
            onUpload = { _, _ -> }
        )
    }
}
