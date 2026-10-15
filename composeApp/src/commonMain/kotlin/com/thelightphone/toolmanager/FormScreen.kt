package com.thelightphone.toolmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.filemanager.Remote
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private val FormJson = Json { prettyPrint = true }
private const val MAX_RESPONSE_LENGTH = 2048

@Composable
fun FormScreen(
    spec: FormSpec,
    values: Map<String, String>,
    isUploading: Boolean,
    onValueChange: (id: String, value: String) -> Unit,
    onSubmitClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(max = InnerColumnWidth)
            .verticalScroll(rememberScrollState())
    ) {
        SpecHeaderText(spec)
        spec.items.forEach { item ->
            FormField(
                item = item,
                value = values[item.id].orEmpty(),
                enabled = !isUploading,
                onValueChange = { onValueChange(item.id, it) },
            )
        }
        Spacer(Modifier.height(40.dp))
        TextButton(
            enabled = !isUploading,
            text = spec.buttonText,
            onClick = onSubmitClick,
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun FormField(
    item: FormItem,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        color = contentColor,
        letterSpacing = 0.8.sp,
        fontSize = 16.sp
    )
    // An empty BasicTextField measures shorter than one holding text, so pin the field to the
    // height of a real line of text to keep it from jumping on the first keystroke.
    val textMeasurer = rememberTextMeasurer()
    val lineHeight = with(LocalDensity.current) {
        remember(textStyle) { textMeasurer.measure("Hg", textStyle, maxLines = 1).size.height }.toDp()
    }
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(20.dp))
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.take(MAX_RESPONSE_LENGTH)) },
            enabled = enabled,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(contentColor),
            visualTransformation = if (item.hidden) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (item.hidden) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxWidth().height(lineHeight), contentAlignment = Alignment.CenterStart) {
                    innerTextField()
                }
            }
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(contentColor)
        )
        Spacer(Modifier.height(12.dp))
        Text(text = item.label, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
fun FormScreen(
    remote: Remote,
    spec: FormSpec,
    onSubmitted: () -> Unit,
    onAlert: (ToolManagerAlert) -> Unit = ::pushGlobalAlert
) {
    var values by remember(spec) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isUploading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun onSubmit() {
        val missing = spec.items.filter { it.required && values[it.id].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            val labels = missing.joinToString(", ") { it.label }
            onAlert(ToolManagerAlert("$labels ${if (missing.size == 1) "is" else "are"} required"))
            return
        }

        val submission = FormSubmission(
            spec.items.map { FormResponse(it, values[it.id].orEmpty()) }
        )
        val bytes = FormJson.encodeToString(FormSubmission.serializer(), submission).encodeToByteArray()
        val fileName = "form_${Clock.System.now().toEpochMilliseconds()}.json"

        isUploading = true
        coroutineScope.launch {
            val succeeded = try {
                val status = remote.uploadOctetStream(spec.path, fileName, bytes, 1.minutes.inWholeMilliseconds)
                if (status.isSuccess()) {
                    remote.notifyUpload(spec.path)
                }
                status.isSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
            isUploading = false
            if (succeeded) {
                onAlert(ToolManagerAlert("Form submitted successfully"))
                onSubmitted()
            } else {
                onAlert(ToolManagerAlert("Failed to submit form"))
            }
        }
    }

    FormScreen(
        spec = spec,
        values = values,
        isUploading = isUploading,
        onValueChange = { id, value -> values = values + (id to value) },
        onSubmitClick = ::onSubmit,
    )
}

@Preview(device = Devices.DESKTOP)
@Composable
fun FormScreenPreview() {
    AppTheme {
        val spec = FormSpec(
            "Contact",
            "",
            items = listOf(
                FormItem("first", "First Name", "William", required = true),
                FormItem("last", "Last Name", "Nilliam"),
                FormItem("number", "Number", "555-555-5555"),
                FormItem("pin", "PIN", hidden = true),
            ),
            buttonText = "Submit"
        )
        FormScreen(spec, emptyMap(), false, onValueChange = { _, _ -> }, onSubmitClick = {})
    }
}
