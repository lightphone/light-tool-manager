package com.thelightphone.toolmanager

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.thelightphone.filemanager.Remote
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@Composable
fun JobScreen(
    spec: JobSpec,
    isBusy: Boolean,
    onStartClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = InnerColumnWidth)
    ) {
        SpecHeaderText(spec)
        TextButton(
            enabled = !isBusy,
            text = spec.buttonText,
            dashed = false,
            onClick = onStartClick,
        )
    }
}

private val JobPollInterval = 2.seconds

@Composable
fun JobScreen(
    remote: Remote,
    spec: JobSpec,
    onNavigateBack: () -> Unit,
    resumeJobId: String? = null,
    onResumeConsumed: () -> Unit = {},
    onAlert: (ToolManagerAlert) -> Unit = ::pushGlobalAlert,
) {
    var isBusy by remember(spec) { mutableStateOf(resumeJobId != null) }
    var succeeded by remember(spec) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    suspend fun pollUntilDone(jobId: String) {
        while (true) {
            delay(JobPollInterval)
            val status = remote.jobStatus(spec.path, jobId).getOrNull()
            when (status?.status) {
                JobState.SUCCEEDED -> {
                    isBusy = false
                    onAlert(ToolManagerAlert(status.message ?: "Job completed successfully."))
                    succeeded = true
                    return
                }

                JobState.FAILED -> {
                    isBusy = false
                    onAlert(ToolManagerAlert(status.message ?: "Job failed."))
                    return
                }

                JobState.PENDING, JobState.RUNNING -> { /* keep polling */
                }

                // Either the status request itself failed, or came back with something
                // unrecognized - either way there's nothing left to poll toward.
                null -> {
                    isBusy = false
                    onAlert(ToolManagerAlert("Lost track of job status."))
                    return
                }
            }
        }
    }

    fun onClickStart() {
        coroutineScope.launch {
            isBusy = true
            remote.startJob(spec.path).fold(
                onSuccess = { jobStart ->
                    val redirectUrl = jobStart.redirectUrl
                    if (redirectUrl != null) {
                        // Full-page navigation away, so isBusy staying true (and this composable
                        // never getting torn down cleanly) doesn't matter.
                        navigateToExternalUrl(redirectUrl)
                    } else {
                        pollUntilDone(jobStart.jobId)
                    }
                },
                onFailure = {
                    isBusy = false
                    onAlert(ToolManagerAlert("Error starting job."))
                }
            )
        }
    }

    LaunchedEffect(spec) {
        if (resumeJobId != null) {
            onResumeConsumed()
            pollUntilDone(resumeJobId)
        }
    }

    LaunchedEffect(succeeded) {
        if (succeeded) onNavigateBack()
    }

    JobScreen(spec, isBusy, ::onClickStart)
}

@Preview(device = Devices.DESKTOP)
@Composable
fun JobScreenPreview() {
    AppTheme {
        val spec = JobSpec(
            "Job",
            "",
            headerText = "Sample thing\nTry this.",
            buttonText = "Click Here to Start"
        )
        JobScreen(spec, false, onStartClick = {})
    }
}
