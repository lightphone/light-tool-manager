package com.thelightphone.filemanager

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.thelightphone.filemanager.composeapp.generated.resources.Res
import com.thelightphone.filemanager.composeapp.generated.resources.ic_back_white
import com.thelightphone.filemanager.composeapp.generated.resources.ic_close_white
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Composable
fun FileManagerScreen(
    onBackPressed: (() -> Unit)?,
    title: String? = null,
    alerts: List<FileManagerAlert> = emptyList(),
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
                if (title != null) {
                    Text(
                        title,
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

private const val AlertAnimationDurationMs = 250

// Alerts stack bottom-to-top from the bottom-right corner: new ones are appended to `alerts`
// and land at the bottom of the column, closest to the corner, pushing earlier ones up.
@Composable
private fun AlertStack(
    alerts: List<FileManagerAlert>,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rendered = remember { mutableStateListOf<FileManagerAlert>() }
    val transitionStates = remember { mutableStateMapOf<String, MutableTransitionState<Boolean>>() }

    LaunchedEffect(alerts) {
        val currentIds = alerts.map { it.id }.toSet()

        for (alert in alerts) {
            val existingIndex = rendered.indexOfFirst { it.id == alert.id }
            if (existingIndex == -1) {
                rendered.add(alert)
                transitionStates[alert.id] =
                    MutableTransitionState(false).apply { targetState = true }
            } else {
                rendered[existingIndex] = alert
            }
        }

        // Alerts no longer wanted (timed out, dismissed, or removed by the caller) start their
        // exit transition here; AlertStackItem removes them from `rendered` once it finishes.
        for (alert in rendered) {
            if (alert.id !in currentIds) {
                transitionStates[alert.id]?.targetState = false
            }
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        for (alert in rendered.toList()) {
            key(alert.id) {
                // Per the invariant above, an id is only ever added to `rendered` in the same
                // pass it's added to `transitionStates`, so this always exists; getOrPut (rather
                // than a `?: return@key` early-out) avoids non-local control flow out of a
                // for-loop-wrapped inline composable, which trips a ClassFormatError in the
                // layoutlib preview renderer ("Illegal method name \"<anonymous>\"").
                val transitionState = transitionStates.getOrPut(alert.id) {
                    MutableTransitionState(false).apply { targetState = true }
                }
                AlertStackItem(
                    alert = alert,
                    transitionState = transitionState,
                    onRequestDismiss = { transitionState.targetState = false },
                    onExitFinished = {
                        rendered.removeAll { it.id == alert.id }
                        transitionStates.remove(alert.id)
                        onDismiss(alert.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun AlertStackItem(
    alert: FileManagerAlert,
    transitionState: MutableTransitionState<Boolean>,
    onRequestDismiss: () -> Unit,
    onExitFinished: () -> Unit
) {
    val latestOnRequestDismiss by rememberUpdatedState(onRequestDismiss)
    val latestOnExitFinished by rememberUpdatedState(onExitFinished)

    LaunchedEffect(alert.id, alert.duration) {
        delay(alert.duration)
        latestOnRequestDismiss()
    }

    // currentState catches up to targetState once a transition finishes; currentState == false
    // while targetState is also false (as opposed to false-because-not-yet-entered) means the
    // exit animation just completed.
    LaunchedEffect(transitionState.currentState, transitionState.targetState) {
        if (!transitionState.currentState && !transitionState.targetState) {
            latestOnExitFinished()
        }
    }

    AnimatedVisibility(
        visibleState = transitionState,
        enter = slideInHorizontally(
            animationSpec = tween(AlertAnimationDurationMs),
            initialOffsetX = { fullWidth -> fullWidth }
        ) + fadeIn(tween(AlertAnimationDurationMs)),
        exit = slideOutHorizontally(
            animationSpec = tween(AlertAnimationDurationMs),
            targetOffsetX = { fullWidth -> fullWidth }
        ) + shrinkVertically(tween(AlertAnimationDurationMs)) + fadeOut(
            tween(
                AlertAnimationDurationMs
            )
        )
    ) {
        Alert(alert, onDismiss = latestOnRequestDismiss)
    }
}

data class FileManagerAlert @OptIn(ExperimentalUuidApi::class) constructor(
    val message: String,
    val id: String = Uuid.random().toString(),
    val duration: Duration = 4.seconds,
    val dismissable: Boolean = true
)

private val AlertMinWidth = 200.dp
private val AlertMaxWidth = 500.dp
private val AlertIconSize = 20.dp
private val AlertIconSpacing = 8.dp

@Composable
fun Alert(
    alert: FileManagerAlert,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(
        modifier
            .border(1.dp, MaterialTheme.colorScheme.onBackground)
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        val rowMaxWidth = min(maxWidth, AlertMaxWidth)
        val rowMinWidth = min(AlertMinWidth, rowMaxWidth)
        Row(
            modifier = Modifier.widthIn(rowMinWidth, rowMaxWidth),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                alert.message,
                fontSize = 18.sp,
                modifier = Modifier.widthIn(max = rowMaxWidth - AlertIconSize - AlertIconSpacing)
            )
            if (alert.dismissable) {
                Spacer(modifier = Modifier.width(AlertIconSpacing))
                Icon(
                    painter = painterResource(Res.drawable.ic_close_white),
                    contentDescription = "Dismiss",
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(AlertIconSize)
                        .clickable(onClick = onDismiss)
                )
            }
        }
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

@Preview
@Composable
fun AlertPreview() {
    AppTheme {
        Alert(
            FileManagerAlert("Your login credentials were ad asd asd asd asd asd"),
            onDismiss = {}
        )
    }
}

@Preview(device = Devices.DESKTOP)
@OptIn(ExperimentalUuidApi::class)
@Composable
fun FileManagerScreenPreview() {
    var alerts by remember { mutableStateOf<List<FileManagerAlert>>(emptyList()) }
    AppTheme {
        FileManagerScreen(
            onBackPressed = {},
            title = "Title",
            alerts = alerts,
            onDismissAlert = { id -> alerts = alerts.filterNot { it.id == id } }
        ) {
            Text("Screen content goes here", Modifier.clickable {
                val uuidString = Uuid.random().toString()
                alerts = alerts + FileManagerAlert(uuidString, uuidString)
            })
        }
    }
}