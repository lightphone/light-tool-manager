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
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
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

        // null = at the true top-level root; otherwise, which page we're on. The composable
        // rendered is dispatched off this spec's sealed type (see the `when` below), not off
        // nullability alone — RootScreen also handles nested RootViewSpec pages.
        var currentSpec by remember { mutableStateOf<DataViewSpec?>(null) }

        // Parent of each currentSpec, in navigation order, so Back returns to the immediate
        // parent rather than always jumping to the true root.
        var backStack by remember { mutableStateOf<List<DataViewSpec?>>(emptyList()) }

        // Every spec navigated to this session, keyed by its pushed path string, so a browser
        // back/forward event (which only hands back a path string) can be resolved to a spec
        // without needing the whole page tree in memory.
        var visitedSpecs by remember { mutableStateOf<Map<String, DataViewSpec>>(emptyMap()) }

        val apiKey = remember { getApiKey() }

        val client = remember(apiKey) {
            HttpClient {
                install(ContentNegotiation) {
                    json()
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 10000
                    connectTimeoutMillis = 5000
                }
                if (apiKey != null) {
                    defaultRequest {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                    }
                }
            }
        }

        fun navigateTo(spec: DataViewSpec, pushState: Boolean = true) {
            backStack = backStack + currentSpec
            currentSpec = spec
            visitedSpecs = visitedSpecs + (spec.path.joinToString("/") to spec)
            if (pushState) pushBrowserState(spec.path.joinToString("/"))
        }

        fun navigateToRoot(pushState: Boolean = true) {
            backStack = emptyList()
            currentSpec = null
            if (pushState) pushBrowserState(null)
        }

        fun navigateBack() {
            val previous = backStack.lastOrNull()
            backStack = backStack.dropLast(1)
            currentSpec = previous
            pushBrowserState(previous?.path?.joinToString("/"))
        }

        // Handle browser back/forward. A path string resolves against specs visited this
        // session; anything unresolvable (e.g. a stale/foreign history entry) falls back to root
        // rather than getting stuck.
        LaunchedEffect(Unit) {
            onBrowserBack { pathString ->
                val target = pathString?.let { visitedSpecs[it] }
                if (target != null) {
                    currentSpec = target
                } else {
                    currentSpec = null
                    backStack = emptyList()
                }
            }
        }

        // Server connectivity polling
        LaunchedEffect(Unit) {
            while (true) {
                delay(6000)
                serverConnected = try {
                    client.get("${getBaseUrl()}/ping").status.isSuccess()
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

            // Typed as an expression (rather than a statement) so the `when` is exhaustiveness-
            // checked by the compiler: adding a new DataViewSpec subtype, or giving Dropbox or
            // Configurator their own screen, forces every call site here to be revisited.
            return@Column when (val spec = currentSpec) {
                null -> RootScreen(
                    client = client,
                    spec = null,
                    onPageClick = { navigateTo(it) },
                    onBack = { navigateBack() }
                )
                is RootViewSpec -> RootScreen(
                    client = client,
                    spec = spec,
                    onPageClick = { navigateTo(it) },
                    onBack = { navigateBack() }
                )
                is FileBrowserSpec -> EntriesScreen(client = client, spec = spec, onBack = { navigateBack() })
                is DropboxSpec -> EntriesScreen(client = client, spec = spec, onBack = { navigateBack() })
                is ConfiguratorSpec -> EntriesScreen(client = client, spec = spec, onBack = { navigateBack() })
            }
        }
        }
    }
}
