package com.thelightphone.toolmanager

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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.LocalPlatformContext
import com.thelightphone.filemanager.PreviewRemote
import com.thelightphone.filemanager.Remote
import com.thelightphone.toolmanager.composeapp.generated.resources.Res
import com.thelightphone.toolmanager.composeapp.generated.resources.ic_reverse_order_white
import com.thelightphone.toolmanager.composeapp.generated.resources.ic_trash
import com.thelightphone.toolmanager.composeapp.generated.resources.ic_text_file
import com.thelightphone.toolmanager.composeapp.generated.resources.ic_audio_waveform
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.format
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.vectorResource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun EntriesScreen(
    remote: Remote,
    spec: FileBrowserSpec,
    onAlert: (ToolManagerAlert) -> Unit = ::pushGlobalAlert
) {
    val rootPath = remember(spec) { spec.path }
    var currentPath by remember(spec) { mutableStateOf(rootPath) }
    var allEntries by remember(currentPath) { mutableStateOf<List<Entry>>(emptyList()) }
    var isLoading by remember(currentPath) { mutableStateOf(true) }
    var isLoadingMore by remember(currentPath) { mutableStateOf(false) }
    var sort by remember(currentPath) { mutableStateOf(Sort()) }
    var currentPage by remember(currentPath) { mutableStateOf(1) }
    var hasMorePages by remember(currentPath) { mutableStateOf(true) }
    var selectedPaths by remember(currentPath) { mutableStateOf<Set<String>>(emptySet()) }
    var lastSelectedIndex by remember(currentPath) { mutableStateOf<Int?>(null) }
    var isReadOnly by remember(currentPath) { mutableStateOf(true) }
    var isUploading by remember(currentPath) { mutableStateOf(false) }
    val showDeleteConfirmation = remember(currentPath) { mutableStateOf(false) }
    val pageSize = 150

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
                val response =
                    remote.filesAt(currentPath, page, pageSize, sort.sortBy, sort.sortOrder)
                        .getOrThrow()

                allEntries = if (append) {
                    allEntries + response.data
                } else {
                    response.data
                }

                currentPage = page
                hasMorePages = response.pagination.hasNext
            } catch (e: Throwable) {
                onAlert(ToolManagerAlert("Failed to load: ${e.message}"))
            }
            isLoading = false
            isLoadingMore = false
        }
    }

    // accumulate is true if holding shift key
    fun toggleSelection(path: String, index: Int, accumulate: Boolean = false) {
        val lastIndexSnapshot = lastSelectedIndex
        if (accumulate && lastIndexSnapshot != null) {
            val range = if (index < lastIndexSnapshot) {
                index..lastIndexSnapshot
            } else {
                lastIndexSnapshot..index
            }
            val selections = range.mapNotNull { allEntries.getOrNull(it)?.path }
            selectedPaths += selections
        } else {
            selectedPaths = if (selectedPaths.contains(path)) {
                selectedPaths - path
            } else {
                selectedPaths + path
            }
        }
        lastSelectedIndex = index
    }

    fun downloadSelected() {
        if (selectedPaths.isEmpty()) return
        coroutineScope.launch {
            remote.requestDownloadToken(selectedPaths.toList()).onSuccess { tokenResponse ->
                remote.downloadFile(tokenResponse.token)
                selectedPaths = emptySet()
            }
        }
    }

    // Initial load + fetch meta
    LaunchedEffect(currentPath) {
        loadEntries(sort, 1)
        isReadOnly = remote.metaAt(currentPath).map { it.readOnly }.getOrElse { true }
    }

    fun navigateBack(): Boolean {
        return if (showDeleteConfirmation.value) {
            showDeleteConfirmation.value = false
            true
        } else if (currentPath == rootPath) {
            false
        } else {
            currentPath = currentPath.substringBeforeLast("/")
            true
        }
    }

    BackClickInterceptor { navigateBack() }

    fun onUpload(files: List<Pair<String, ByteArray>>) {
        coroutineScope.launch {
            uploadFiles(
                files,
                currentPath,
                remote,
                onIsUploading = { isUploading = it },
                onSuccess = { loadEntries(sort, 1) }
            )
        }
    }

    EntriesScreenContent(
        remote = remote,
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
        showDeleteConfirmation = showDeleteConfirmation,
        onRetry = { loadEntries(it, 1) },
        onLoadMore = { loadEntries(sort, currentPage + 1, append = true) },
        onEntryClick = { entry, index, accumulate ->
            if (entry.type == EntryType.Directory) {
                // entry.path is already the full absolute path from the server, not relative
                // to currentPath, so it can be used directly as the new location.
                currentPath = entry.path
            } else {
                toggleSelection(entry.path, index, accumulate)
            }
        },
        onClearSelection = { selectedPaths = emptySet() },
        onDownloadSelected = ::downloadSelected,
        performDelete = suspend {
            deleteFiles(selectedPaths.toList(), remote) {
                selectedPaths = emptySet()
                loadEntries(sort, 1)
            }
            showDeleteConfirmation.value = false
        },
        onUpload = ::onUpload
    )
}

@Composable
private fun EntriesScreenContent(
    remote: Remote,
    spec: FileBrowserSpec,
    currentPath: String,
    entries: List<Entry>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMorePages: Boolean,
    selectedPaths: Set<String>,
    isReadOnly: Boolean,
    isUploading: Boolean,
    showDeleteConfirmation: MutableState<Boolean>,
    sort: Sort,
    onRetry: (Sort) -> Unit,
    onLoadMore: () -> Unit,
    onEntryClick: (Entry, Int, Boolean) -> Unit,
    onClearSelection: () -> Unit,
    onDownloadSelected: () -> Unit,
    performDelete: suspend () -> Unit,
    onUpload: (files: List<Pair<String, ByteArray>>) -> Unit
) {
    val gridState = rememberLazyGridState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    var isShiftPressed by remember { mutableStateOf(false) }
    val latestIsLoadingMore by rememberUpdatedState(isLoadingMore)
    val latestHasMorePages by rememberUpdatedState(hasMorePages)
    val latestIsLoading by rememberUpdatedState(isLoading)
    val latestOnLoadMore by rememberUpdatedState(onLoadMore)
    val showDelete by showDeleteConfirmation
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

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = InnerColumnWidth)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onFocusChanged { if (!it.hasFocus) isShiftPressed = false }
                .onPreviewKeyEvent { event ->
                    when {
                        event.key == Key.ShiftLeft || event.key == Key.ShiftRight -> {
                            isShiftPressed = event.type == KeyEventType.KeyDown
                            false
                        }

                        event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                            onClearSelection()
                            true
                        }

                        else -> false
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
                        remote = remote,
                        entries = entries,
                        gridState = gridState,
                        isLoadingMore = isLoadingMore,
                        selectedPaths = selectedPaths,
                        onEntryClick = { entry, index ->
                            onEntryClick(
                                entry,
                                index,
                                isShiftPressed
                            )
                        },
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
                                    if (!isReadOnly) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_trash),
                                            contentDescription = "Delete",
                                            tint = if (optionsSelected) EnabledColor else DisabledColor,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clickable(enabled = optionsSelected) {
                                                    showDeleteConfirmation.value = true
                                                }
                                        )
                                    }
                                    TextButton(
                                        text = "download",
                                        onClick = onDownloadSelected,
                                        enabled = optionsSelected
                                    )
                                    if (!isReadOnly) {
                                        TextButton(
                                            text = "upload",
                                            onClick = {
                                                triggerFilePicker(onFilesSelected = { files ->
                                                    onUpload(
                                                        files
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

        if (showDelete) {
            val numFiles = selectedPaths.size
            ConfirmationScreen(
                title = "Delete files",
                "Are you sure you want to delete $numFiles file(s)?\n\n You cannot undo this.",
                "delete files",
                action = performDelete
            )
        }
    }
}

private val AlertBatchWindow = 1.seconds

private class AlertBatch(
    private val scope: CoroutineScope,
    private val emit: (names: List<String>, remaining: Int) -> Unit
) {
    private val names = mutableListOf<String>()
    private var remaining = 0
    private var flushJob: Job? = null

    fun add(name: String, remainingCount: Int) {
        names.add(name)
        remaining = remainingCount
        if (flushJob == null) {
            flushJob = scope.launch {
                delay(AlertBatchWindow)
                flushNow()
            }
        }
    }

    fun flushNow() {
        flushJob?.cancel()
        flushJob = null
        if (names.isEmpty()) return
        val batchedNames = names.toList()
        val batchedRemaining = remaining
        names.clear()
        emit(batchedNames, batchedRemaining)
    }
}

// "a.jpg" (+ verb) for a lone result, "a.jpg and 2 others" (+ verb) for a batch.
private fun batchedMessage(names: List<String>, verb: String): String {
    val others = names.size - 1
    val lead = if (others <= 0) {
        names.first()
    } else {
        "${names.first()} and $others other${if (others == 1) "" else "s"}"
    }
    return "$lead $verb"
}

suspend fun uploadFiles(
    files: List<Pair<String, ByteArray>>,
    currentPath: String,
    remote: Remote,
    onAlert: (ToolManagerAlert) -> Unit = ::pushGlobalAlert,
    onIsUploading: (Boolean) -> Unit,
    onSuccess: () -> Unit,
) {
    if (files.isEmpty()) return
    onIsUploading(true)
    var remaining = files.size

    coroutineScope {
        val successes = AlertBatch(this) { names, remainingAtFlush ->
            val message = batchedMessage(names, "uploaded successfully")
            onAlert(ToolManagerAlert("$message, $remainingAtFlush file(s) remaining"))
        }
        val failures = AlertBatch(this) { names, remainingAtFlush ->
            val message = batchedMessage(names, "failed to upload")
            onAlert(ToolManagerAlert("$message, $remainingAtFlush file(s) remaining"))
        }

        // upload sequentially for now - not sure parallel uploads are going to improve throughput
        // on light devices, worth testing though
        for ((fileName, bytes) in files) {
            runCatching {
                val status = remote.uploadOctetStream(
                    currentPath,
                    fileName,
                    bytes,
                    5.minutes.inWholeMilliseconds,
                )
                if (status.isSuccess()) {
                    remote.notifyUpload(currentPath)
                }
                status
            }.onSuccess { status ->
                remaining--
                if (status.isSuccess()) {
                    successes.add(fileName, remaining)
                } else {
                    failures.add(fileName, remaining)
                }
            }.onFailure {
                remaining--
                // catches failures not returned from server
                failures.add(fileName, remaining)
            }
        }

        successes.flushNow()
        failures.flushNow()
    }

    onSuccess()
    onIsUploading(false)
}

suspend fun deleteFiles(
    paths: List<String>,
    remote: Remote,
    onAlert: (ToolManagerAlert) -> Unit = ::pushGlobalAlert,
    onSuccess: () -> Unit,
) {
    if (paths.isEmpty()) return

    coroutineScope {
        val successes = AlertBatch(this) { names, _ ->
            onAlert(ToolManagerAlert(batchedMessage(names, "deleted successfully")))
        }
        val failures = AlertBatch(this) { names, _ ->
            onAlert(ToolManagerAlert(batchedMessage(names, "failed to delete")))
        }

        for (path in paths) {
            val fileName = path.substringAfterLast('/')
            remote.deleteFile(path).onSuccess { deleted ->
                if (deleted) {
                    successes.add(fileName, 0)
                } else {
                    failures.add(fileName, 0)
                }
            }.onFailure {
                failures.add(fileName, 0)
            }
        }

        successes.flushNow()
        failures.flushNow()
    }

    onSuccess()
}

@Composable
fun EntryList(
    remote: Remote,
    entries: List<Entry>,
    gridState: LazyGridState,
    isLoadingMore: Boolean,
    selectedPaths: Set<String>,
    onEntryClick: (Entry, Int) -> Unit,
    scrollingHeader: @Composable () -> Unit,
    header: @Composable () -> Unit
) {
    val gridItemSize = 180.dp
    val gridSpacing = 8.dp
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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
            itemsIndexed(entries) { idx, item ->
                EntryListItem(
                    remote = remote,
                    entry = item,
                    isSelected = selectedPaths.contains(item.path),
                    onClick = { onEntryClick(item, idx) }
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
fun BoxScope.VideoListItem(remote: Remote, entry: Entry) {
    val durationBackground = Color(0x88CCCCCC)
    AsyncImage(
        model = remote.thumbnailFetcherForEntry(entry, LocalPlatformContext.current),
        contentDescription = entry.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
    entry.meta?.get(MetaKeys.DURATION)?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.background,
            fontSize = 20.sp,
            modifier = Modifier.padding(8.dp)
                .background(durationBackground)
                .padding(8.dp)
                .align(Alignment.BottomCenter)
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
fun ImageListItem(remote: Remote, entry: Entry) {
    AsyncImage(
        model = remote.thumbnailFetcherForEntry(entry, LocalPlatformContext.current),
        contentDescription = entry.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
}

@Composable
fun EntryListItem(
    remote: Remote,
    entry: Entry,
    isSelected: Boolean,
    onClick: (Entry) -> Unit
) {
    val bgColor = MaterialTheme.colorScheme.primary
    val selectedOverlayColor = Color(0x88CCCCCC)
    val selectedBorderColor = Color(0xFFF7E22E)
    Box(Modifier.aspectRatio(1f).background(bgColor).clickable {
        onClick(entry)
    }) {
        when (entry.type) {
            EntryType.Directory -> { /* Directories not supported currently */
            }

            EntryType.Image -> ImageListItem(remote, entry)
            EntryType.Video -> VideoListItem(remote, entry)
            EntryType.GenericFile, EntryType.Text, EntryType.Audio -> FileListItem(entry)
        }
        if (isSelected) {
            Box(
                Modifier.fillMaxSize()
                    .border(7.dp, selectedBorderColor)
                    .background(selectedOverlayColor)
            )
        }
    }
}

private data class Sort(
    val sortBy: SortBy = SortBy.NAME,
    val sortOrder: SortOrder = SortOrder.ASC
)

private val previewEntries = listOf(
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

// Intercepts AsyncImage's thumbnail loads while a @Preview is rendering (LocalInspectionMode)
// so the preview canvas shows solid placeholders instead of hitting the (nonexistent) server.
@OptIn(ExperimentalCoilApi::class)
@Composable
private fun WithPreviewThumbnails(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { _ ->
            ColorImage(color = 0xFFFFaaFF.toInt())
        }
    ) {
        content()
    }
}

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
    val showDeleteConfirmation = remember { mutableStateOf(false) }
    val spec = FileBrowserSpec("Files", "files", "Sample text options.")
    WithPreviewThumbnails {
        AppTheme {
            EntriesScreenContent(
                remote = PreviewRemote,
                spec = spec,
                currentPath = spec.path,
                entries = entries,
                sort = sort,
                isLoading = false,
                isLoadingMore = false,
                hasMorePages = true,
                selectedPaths = setOf("photos/photo.jpg"),
                isReadOnly = false,
                isUploading = false,
                showDeleteConfirmation = showDeleteConfirmation,
                onRetry = { sort = it },
                onLoadMore = {},
                onEntryClick = { _, _, _ -> },
                onClearSelection = {},
                onDownloadSelected = {},
                performDelete = {
                    delay(2.seconds)
                    showDeleteConfirmation.value = false
                },
                onUpload = {}
            )
        }
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
fun EntriesScreenContentLoadingPreview() {
    val spec = FileBrowserSpec("Files", "files", "Sample text options.")
    WithPreviewThumbnails {
        AppTheme {
            EntriesScreenContent(
                remote = PreviewRemote,
                spec = spec,
                currentPath = spec.path,
                sort = Sort(),
                entries = emptyList(),
                isLoading = true,
                isLoadingMore = false,
                hasMorePages = false,
                selectedPaths = emptySet(),
                isReadOnly = true,
                isUploading = false,
                showDeleteConfirmation = mutableStateOf(false),
                onRetry = {},
                onLoadMore = {},
                onEntryClick = { _, _, _ -> },
                onClearSelection = {},
                onDownloadSelected = {},
                performDelete = {},
                onUpload = {}
            )
        }
    }
}
