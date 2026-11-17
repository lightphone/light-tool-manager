package com.thelightphone.toolmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val CODE_LENGTH = 6

/**
 * Shown when the page was opened without a key (e.g. typing the phone's USB tether address).
 * Trades the 6-digit code the phone displays for a key at /api/pair, stores it where getApiKey()
 * looks, and reloads so the app starts up exactly as it would with a key in the URL.
 */
@Composable
fun PairingScreen() {
    TitleOverride("Pair")
    var code by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun submit() {
        if (code.length != CODE_LENGTH || pairing) return
        coroutineScope.launch {
            pairing = true
            error = null
            val key = requestKey(code)
            if (key != null) {
                storeApiKey(key)
                window.location.reload()
            } else {
                error = "Invalid code."
                code = ""
                pairing = false
            }
        }
    }

    val contentColor = MaterialTheme.colorScheme.onSurface
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxHeight().widthIn(max = InnerColumnWidth)
                .align(Alignment.TopCenter)
        ) {
            Spacer(Modifier.height(80.dp))
            Text("Enter the code shown on your Light Phone", textAlign = TextAlign.Center)
            Spacer(Modifier.height(40.dp))
            BasicTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(CODE_LENGTH) },
                enabled = !pairing,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    color = contentColor,
                    letterSpacing = 8.sp,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(contentColor),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.width(220.dp).padding(vertical = 4.dp),
            )
            Box(Modifier.width(220.dp).height(2.dp).background(contentColor))
            error?.let {
                Spacer(Modifier.height(24.dp))
                Text(it, textAlign = TextAlign.Center, fontSize = 14.sp)
            }
            Spacer(Modifier.height(40.dp))
            TextButton(
                enabled = code.length == CODE_LENGTH && !pairing,
                text = "pair",
                onClick = ::submit,
            )
        }
    }
}

// Null for a wrong/expired code (or too many attempts) as well as network errors.
private suspend fun requestKey(code: String): String? = runCatching {
    HttpClient().use { client ->
        val response = client.post("${getBaseUrl()}/api/pair") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code"}""")
        }
        if (!response.status.isSuccess()) return null
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["key"]?.jsonPrimitive?.content
    }
}.getOrNull()
