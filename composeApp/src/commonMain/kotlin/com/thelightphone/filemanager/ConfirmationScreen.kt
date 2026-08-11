package com.thelightphone.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices.DESKTOP
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ConfirmationScreen(
    title: String,
    text: String,
    buttonText: String,
    action: suspend () -> Unit
) {
    TitleOverride(title)
    var actionRunning by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    fun runAction() {
        coroutineScope.launch {
            actionRunning = true
            action()
            actionRunning = false
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxHeight().widthIn(max = InnerColumnWidth)
                .align(Alignment.TopCenter)
        ) {
            Spacer(Modifier.height(80.dp))
            Text(text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(80.dp))
            TextButton(
                enabled = !actionRunning,
                text = buttonText,
                onClick = ::runAction,
            )
        }
    }
}

@Preview(device = DESKTOP)
@Composable
fun PreviewConfirmationScreen() {
    AppTheme {
        ConfirmationScreen(
            "Delete files",
            "Are ya sure?\n\n no takesies-backsies!",
            "delete files"
        ) {

        }
    }
}