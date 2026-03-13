package com.thelightphone.filemanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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


@Composable
fun RootScreen(
    client: HttpClient,
    serverConnected: Boolean,
    onPathClick: (String) -> Unit
) {
    var rootPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadRoot() {
        isLoading = true
        error = null
    }

    // Reload when first shown or when server reconnects
    LaunchedEffect(serverConnected) {
        if (!serverConnected) return@LaunchedEffect
        isLoading = true
        error = null
        try {
            val root: Root = client.get("${getBaseUrl()}/api/root").body()
            rootPaths = root.paths
        } catch (e: Throwable) {
            error = "Failed to load root: ${e.message}"
        }
        isLoading = false
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
                Button(onClick = { loadRoot() }) {
                    Text("Retry")
                }
            }
        }
        else -> {
            RootList(paths = rootPaths, onPathClick = onPathClick)
        }
    }
}

@Composable
fun RootList(
    paths: List<String>,
    onPathClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        paths.forEach { path ->
            Text(
                text = path,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPathClick(path) }
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
            paths = listOf("photos", "documents", "music"),
            onPathClick = {}
        )
    }
}
