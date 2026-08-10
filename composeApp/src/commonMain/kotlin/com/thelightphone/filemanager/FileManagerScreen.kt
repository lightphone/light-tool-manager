package com.thelightphone.filemanager

import androidx.compose.animation.AnimatedVisibility
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

// Colors come from MaterialTheme.colorScheme (set up once in App.kt's AppTheme) instead of a
// screen-local color bag, so this screen stays in sync with the rest of the app's theme.
@Composable
fun FileManagerScreen(
    onBackPressed: (() -> Unit)?,
    title: String? = null,
    alert: FileManagerAlert? = null,
    content: @Composable () -> Unit
) {
    var alertVisible by remember { mutableStateOf(alert != null) }
    LaunchedEffect(alert) {
        if (alert != null) {
            alertVisible = true
            delay(alert.duration)
            alertVisible = false
        } else {
            alertVisible = false
        }
    }
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
                                if(!InterceptorRegistry.dispatch()) {
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
        AnimatedVisibility(
            alertVisible,
            enter = slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }),
            exit = slideOutHorizontally(targetOffsetX = { fullWidth -> fullWidth }),
            modifier = Modifier.align(Alignment.BottomEnd).padding(30.dp)
        ) {
            alert?.let {
                Alert(it) { alertVisible = false }
            }
        }
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

    fun put(key: Any, l: () -> Boolean) { listeners[key] = l }
    fun remove(key: Any) { listeners.remove(key) }

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
    var alert by remember { mutableStateOf<FileManagerAlert?>(null) }
    AppTheme {
        FileManagerScreen(onBackPressed = {}, title = "Title", alert = alert) {
            Text("Screen content goes here", Modifier.clickable {
                val uuidString = Uuid.random().toString()
                alert = FileManagerAlert(uuidString, uuidString)
            })
        }
    }
}