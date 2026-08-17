package com.thelightphone.toolmanager

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.thelightphone.toolmanager.composeapp.generated.resources.Res
import com.thelightphone.toolmanager.composeapp.generated.resources.ic_close_white
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import kotlin.collections.set
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class ToolManagerAlert @OptIn(ExperimentalUuidApi::class) constructor(
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
    alert: ToolManagerAlert,
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

private const val AlertAnimationDurationMs = 250

// Alerts stack bottom-to-top from the bottom-right corner
@Composable
fun AlertStack(
    alerts: List<ToolManagerAlert>,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rendered = remember { mutableStateListOf<ToolManagerAlert>() }
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
    alert: ToolManagerAlert,
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