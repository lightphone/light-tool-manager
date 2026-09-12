package com.thelightphone.toolmanager

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.thelightphone.filemanager.Remote
import kotlinx.coroutines.launch

@Composable
fun DownloadScreen(
    spec: DownloadSpec,
    isDownloading: Boolean,
    onDownloadClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = InnerColumnWidth)
    ) {
        SpecHeaderText(spec)
        TextButton(
            enabled = !isDownloading,
            text = spec.buttonText,
            dashed = false,
            onClick = onDownloadClick,
        )
    }
}

@Composable
fun DownloadScreen(
    remote: Remote,
    spec: DownloadSpec,
    onAlert: (ToolManagerAlert) -> Unit = ::pushGlobalAlert
) {
    var isDownloading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun onClickDownload() {
        coroutineScope.launch {
            isDownloading = true
            remote.requestDownloadToken(listOf(spec.resourceFullPath))
                .onSuccess { remote.downloadFile(it.token) }
                .onFailure { onAlert(ToolManagerAlert("Error exporting resource."))}
            isDownloading = false
        }
    }

    DownloadScreen(spec, isDownloading, ::onClickDownload)
}

@Preview(device = Devices.DESKTOP)
@Composable
fun DownloadScreenPreview() {
    AppTheme {
        val spec = DownloadSpec(
            "Export",
            "",
            resourceSubPath = "",
            headerText = "Sample thing\nTry this.",
            buttonText = "Click Here to Export"
        )
        DownloadScreen(spec, false, onDownloadClick = {})
    }
}
