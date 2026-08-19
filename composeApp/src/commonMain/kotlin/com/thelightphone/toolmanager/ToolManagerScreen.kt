package com.thelightphone.toolmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.toolmanager.composeapp.generated.resources.Res
import com.thelightphone.toolmanager.composeapp.generated.resources.ic_back_white
import org.jetbrains.compose.resources.painterResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Composable
fun ToolManagerScreen(
    onBackPressed: (() -> Unit)?,
    title: String? = null,
    alerts: List<ToolManagerAlert> = emptyList(),
    onDismissAlert: (String) -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.widthIn(max = 1200.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (onBackPressed != null) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_back_white),
                        contentDescription = "Back",
                        modifier = Modifier
                            .padding(18.dp)
                            .fillMaxHeight()
                            .clickable {
                                if (!InterceptorRegistry.dispatch()) {
                                    onBackPressed()
                                }
                            }
                    )
                }
                val effectiveTitle = TitleOverrideRegistry.current ?: title
                if (effectiveTitle != null) {
                    Text(
                        effectiveTitle,
                        textDecoration = TextDecoration.Underline,
                        fontSize = 24.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            content()
        }
        AlertStack(
            alerts = alerts,
            onDismiss = onDismissAlert,
            modifier = Modifier.align(Alignment.BottomEnd).padding(30.dp)
        )
    }
}

@Composable
fun SpecHeaderText(spec: DataViewSpec) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        spec.headerText?.let {
            Box(Modifier.height(80.dp))
            Text(it, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(80.dp))
    }
}

object InterceptorRegistry {
    private val listeners = mutableStateMapOf<Any, () -> Boolean>()

    fun put(key: Any, l: () -> Boolean) {
        listeners[key] = l
    }

    fun remove(key: Any) {
        listeners.remove(key)
    }

    fun dispatch() = listeners.values.toList().map { it() }.any { it }
}

@Composable
@NonRestartableComposable
fun BackClickInterceptor(
    enabled: Boolean = true,
    onIntercept: () -> Boolean
) {
    val key = remember { Any() }
    val latest by rememberUpdatedState(onIntercept)

    DisposableEffect(InterceptorRegistry, key, enabled) {
        if (enabled) InterceptorRegistry.put(key) { latest() }
        onDispose { InterceptorRegistry.remove(key) }
    }
}

// Same shape as InterceptorRegistry, for the same reason: a screen nested arbitrarily deep below
// ToolManagerScreen (e.g. a confirmation dialog inside EntriesScreen) has no direct handle on
// ToolManagerScreen's own `title` prop to change it temporarily. Unlike back-click interception,
// only one title can be shown at a time, so there's no aggregation — just "whatever was
// registered most recently wins" (in practice only one screen ever registers at once).
object TitleOverrideRegistry {
    private val overrides = mutableStateMapOf<Any, String>()

    fun put(key: Any, title: String) {
        overrides[key] = title
    }

    fun remove(key: Any) {
        overrides.remove(key)
    }

    val current: String? get() = overrides.values.lastOrNull()
}

@Composable
@NonRestartableComposable
fun TitleOverride(title: String) {
    val key = remember { Any() }

    DisposableEffect(TitleOverrideRegistry, key, title) {
        TitleOverrideRegistry.put(key, title)
        onDispose { TitleOverrideRegistry.remove(key) }
    }
}

@Preview
@Composable
fun AlertPreview() {
    AppTheme {
        Alert(
            ToolManagerAlert("Your login credentials were ad asd asd asd asd asd"),
            onDismiss = {}
        )
    }
}

@Preview(device = Devices.DESKTOP)
@OptIn(ExperimentalUuidApi::class)
@Composable
fun ToolManagerScreenPreview() {
    var alerts by remember { mutableStateOf<List<ToolManagerAlert>>(emptyList()) }
    AppTheme {
        ToolManagerScreen(
            onBackPressed = {},
            title = "Title",
            alerts = alerts,
            onDismissAlert = { id -> alerts = alerts.filterNot { it.id == id } }
        ) {
            Text("Screen content goes here", Modifier.clickable {
                val uuidString = Uuid.random().toString()
                alerts = alerts + ToolManagerAlert(uuidString, uuidString)
            })
        }
    }
}