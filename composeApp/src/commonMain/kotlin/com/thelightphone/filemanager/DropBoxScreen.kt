package com.thelightphone.filemanager

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch

@Composable
fun DropBoxScreen(
    spec: DropboxSpec,
    isUploading: Boolean,
    onUploadClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = InnerColumnWidth)
    ) {
        SpecHeaderText(spec)
        TextButton(
            enabled = !isUploading,
            text = spec.buttonText,
            dashed = true,
            onClick = onUploadClick,
        )
    }
}

@Composable
fun DropBoxScreen(
    client: HttpClient,
    spec: DropboxSpec,
    onAlert: (FileManagerAlert) -> Unit = ::pushGlobalAlert
) {
    val rootPath = remember(spec) { spec.path }
    var currentPath by remember(spec) { mutableStateOf(rootPath) }
    var isUploading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun onUpload(files: List<Pair<String, ByteArray>>) {
        coroutineScope.launch {
            // uploadFiles already posts a "<file> was uploaded, N remaining" alert per file, so
            // there's no separate batch-complete alert here — the last one (0 remaining) is that.
            uploadFiles(files, currentPath, client, onIsUploading = { isUploading = it }) {}
        }
    }

    fun onClickUpload() {
        isUploading = true
        triggerFilePicker(
            onFilesSelected = { files -> onUpload(files) },
            onCancelled = { isUploading = false }
        )
    }

    DropBoxScreen(spec, isUploading, ::onClickUpload)
}

@Preview(device = Devices.DESKTOP)
@Composable
fun DropBoxScreenPreview() {
    AppTheme {
        val spec = DropboxSpec(
            "DropBox",
            "",
            headerText = "Sample thing\nTry this.",
            buttonText = "Click Here to Upload"
        )
        DropBoxScreen(spec, false, onUploadClick = {})
    }
}