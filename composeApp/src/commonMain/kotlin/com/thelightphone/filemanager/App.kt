package com.thelightphone.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay


@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        background = Color.Black,
        surface = Color.White,
        onBackground = Color.White,
        onSurface = Color.Black,
        primary = Color.White,
        onPrimary = Color.Black,
        primaryContainer = Color.DarkGray,
        onPrimaryContainer = Color.White,
        surfaceVariant = Color(0xFF1A1A1A),
        onSurfaceVariant = Color.LightGray,
        surfaceContainerLow = Color.White,
        surfaceContainerHigh = Color.White,
    )
    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
            content()
        }
    }
}

@Composable
fun App() {
    AppTheme {
        var serverConnected by remember { mutableStateOf(true) }
        // null = at the root list, non-null = browsing a directory
        var currentPath by remember { mutableStateOf<String?>(null) }

        val client = remember {
            HttpClient {
                install(ContentNegotiation) {
                    json()
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 10000
                    connectTimeoutMillis = 5000
                }
            }
        }

        fun navigateTo(path: String, pushState: Boolean = true) {
            currentPath = path
            if (pushState) pushBrowserState(path)
        }

        fun navigateToRoot(pushState: Boolean = true) {
            currentPath = null
            if (pushState) pushBrowserState(null)
        }

        fun navigateBack() {
            val path = currentPath ?: return
            val parent = path.substringBeforeLast('/', "")
            if (parent.isEmpty()) {
                navigateToRoot()
            } else {
                navigateTo(parent)
            }
        }

        // Handle browser back/forward
        LaunchedEffect(Unit) {
            onBrowserBack { path ->
                if (path == null) {
                    navigateToRoot(pushState = false)
                } else {
                    navigateTo(path, pushState = false)
                }
            }
        }

        // Server connectivity polling
        LaunchedEffect(Unit) {
            while (true) {
                delay(6000)
                serverConnected = try {
                    client.get("${getBaseUrl()}/api/ping").status.isSuccess()
                } catch (_: Throwable) {
                    false
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = 800.dp)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "File Browser",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (!serverConnected) {
                Text(
                    "Server disconnected",
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Red, RoundedCornerShape(4.dp))
                        .padding(12.dp),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (currentPath == null) {
                RootScreen(
                    client = client,
                    serverConnected = serverConnected,
                    onPathClick = { navigateTo(it) }
                )
            } else {
                EntriesScreen(
                    client = client,
                    currentPath = currentPath!!,
                    onNavigateTo = { navigateTo(it) },
                    onBack = { navigateBack() }
                )
            }
        }
        }
    }
}
