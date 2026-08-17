package com.thelightphone.toolmanager

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thelightphone.toolmanager.composeapp.generated.resources.Res
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


// Not a licensed-for-distribution asset: composeResources/.gitignore excludes files/font/, so
// these bytes only exist on machines that have been given the font directly. Read as raw "files"
// resources (a plain string path) rather than typed Res.font.* accessors, since accessors are
// only generated for files actually present at build time — referencing one directly would fail
// to compile on any checkout that doesn't have the font.
@Composable
private fun rememberAppFontFamily(): FontFamily? {
    var fontFamily by remember { mutableStateOf<FontFamily?>(null) }
    LaunchedEffect(Unit) {
        fontFamily = runCatching {
            val regular = Res.readBytes("files/font/akkuratll_regular.ttf")
            val light = Res.readBytes("files/font/akkuratll_light.ttf")
            val bold = Res.readBytes("files/font/akkuratpro_bold.otf")
            check(regular.isNotEmpty() && light.isNotEmpty() && bold.isNotEmpty())
            val fonts = listOfNotNull(
                fontFromBytes("Akkurat-Regular", regular, FontWeight.Normal),
                fontFromBytes("Akkurat-Light", light, FontWeight.Light),
                fontFromBytes("Akkurat-Bold", bold, FontWeight.Bold),
            )
            check(fonts.isNotEmpty())
            FontFamily(fonts)
        }.getOrNull()
    }
    return fontFamily
}

private fun Typography.withFontFamily(fontFamily: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = fontFamily),
    displayMedium = displayMedium.copy(fontFamily = fontFamily),
    displaySmall = displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = titleLarge.copy(fontFamily = fontFamily),
    titleMedium = titleMedium.copy(fontFamily = fontFamily),
    titleSmall = titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = bodySmall.copy(fontFamily = fontFamily),
    labelLarge = labelLarge.copy(fontFamily = fontFamily),
    labelMedium = labelMedium.copy(fontFamily = fontFamily),
    labelSmall = labelSmall.copy(fontFamily = fontFamily),
)

val InnerColumnWidth = 600.dp

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val bgGrey = Color(0xff222222)
    val colorScheme = darkColorScheme(
        background = bgGrey,
        surface = bgGrey,
        onBackground = Color.White,
        onSurface = Color.White,
        primary = Color(0xffd9d9d9),
        onPrimary = bgGrey,
        secondary = Color(0xFFB2ACACAC),
        onSecondary = bgGrey
    )
    val customFontFamily = rememberAppFontFamily()
    val typography = remember(customFontFamily) {
        customFontFamily?.let { Typography().withFontFamily(it) } ?: Typography()
    }
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
            content()
        }
    }
}

private val globalAlertsFlow = MutableStateFlow<List<ToolManagerAlert>>(emptyList())

// Pushing an id already in the queue replaces that entry in place (e.g. the server-disconnected
// banner keeps a fixed id) rather than adding a duplicate.
fun pushGlobalAlert(alert: ToolManagerAlert) {
    val current = globalAlertsFlow.value
    globalAlertsFlow.value = if (current.any { it.id == alert.id }) {
        current.map { if (it.id == alert.id) alert else it }
    } else {
        current + alert
    }
}

fun dismissGlobalAlert(id: String) {
    globalAlertsFlow.value = globalAlertsFlow.value.filterNot { it.id == id }
}

private const val ServerDisconnectedAlertId = "server-disconnected"

@Composable
fun App() {
    AppTheme {
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
            visitedSpecs = visitedSpecs + (spec.path to spec)
            if (pushState) pushBrowserState(spec.path)
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
            pushBrowserState(previous?.path)
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

        // Server connectivity polling. The banner isn't a transient toast, so it's pushed once
        // per state transition (not re-pushed every poll tick) and given an infinite duration —
        // it's dismissed explicitly on reconnect rather than timing itself out.
        LaunchedEffect(Unit) {
            val pollDuration = 6.seconds
            var wasConnected = true
            while (true) {
                delay(pollDuration)
                val success = try {
                    client.get("${getBaseUrl()}/ping").status.isSuccess()
                } catch (_: Throwable) {
                    false
                }
                if (success != wasConnected) {
                    wasConnected = success
                    if (success) {
                        dismissGlobalAlert(ServerDisconnectedAlertId)
                    } else {
                        pushGlobalAlert(
                            ToolManagerAlert(
                                "Error: Server Disconnected",
                                id = ServerDisconnectedAlertId,
                                duration = Duration.INFINITE,
                                dismissable = false
                            )
                        )
                    }
                }
            }
        }

        val alerts by globalAlertsFlow.collectAsState()
        val onBackPressed = if (backStack.isEmpty()) null else ::navigateBack

        ToolManagerScreen(
            onBackPressed = onBackPressed,
            title = currentSpec?.label ?: "Tool Manager",
            alerts = alerts,
            onDismissAlert = ::dismissGlobalAlert
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                return@Column when (val spec = currentSpec) {
                    null -> RootScreen(
                        client = client,
                        spec = null,
                        onPageClick = { navigateTo(it) },
                    )

                    is RootViewSpec -> RootScreen(
                        client = client,
                        spec = spec,
                        onPageClick = { navigateTo(it) },
                    )

                    is FileBrowserSpec -> EntriesScreen(
                        client = client,
                        spec = spec,
                    )

                    is DropboxSpec -> DropBoxScreen(
                        client = client,
                        spec = spec,
                    )

                    is CustomSpec -> { /*Do not render anything for custom specs */ }
                }
            }
        }
    }
}


fun Modifier.dashedBorder(
    width: Dp,
    color: Color,
    dashLength: Dp = 8.dp,
    gapLength: Dp = 4.dp,
) = drawBehind {
    val strokeWidth = width.toPx()
    drawRect(
        color = color,
        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(dashLength.toPx(), gapLength.toPx()),
                phase = 0f
            )
        )
    )
}


val DisabledColor @Composable get() = MaterialTheme.colorScheme.secondary
val EnabledColor @Composable get() = MaterialTheme.colorScheme.onSurface

@Composable
fun TextButton(
    enabled: Boolean,
    text: String,
    dashed: Boolean = false,
    onClick: () -> Unit
) {

    val borderColor = if (!enabled) DisabledColor else MaterialTheme.colorScheme.onSurface
    val borderWidth = 2.dp
    val borderMod: Modifier.() -> Modifier = if (dashed) {
        { dashedBorder(borderWidth, dashLength = 4.dp, color = borderColor) }
    } else {
        { border(borderWidth, borderColor) }
    }
    Button(
        enabled = enabled,
        onClick = onClick,
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = EnabledColor,
            disabledContentColor = DisabledColor
        ),
        elevation = null,
        contentPadding = PaddingValues(
            horizontal = if (dashed) 24.dp else 12.dp,
        ),
        modifier = Modifier.borderMod().height(44.dp)
    ) {
        Text(text, textAlign = TextAlign.Center)
    }
}

