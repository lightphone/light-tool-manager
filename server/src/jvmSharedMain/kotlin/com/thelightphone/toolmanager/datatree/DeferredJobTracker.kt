package com.thelightphone.toolmanager.datatree

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class JobResult(val resultPath: Path? = null, val message: String? = null)

// Used to launch/track Tool Manager "Jobs" - some arbitrary async actions that
// users can kick off using the Tool Manager browser UI
class DeferredJobTracker(private val scope: CoroutineScope) {
    private val jobs = ConcurrentHashMap<String, CompletableDeferred<Result<JobResult>>>()

    // Launches `block` in this tracker's scope and tracks its outcome under a fresh id.
    fun start(block: suspend CoroutineScope.() -> Result<JobResult>): String {
        val jobId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Result<JobResult>>()
        jobs[jobId] = deferred
        scope.launch {
            deferred.complete(runCatching { block() }.getOrElse { Result.failure(it) })
        }
        return jobId
    }

    // For jobs whose completion is driven externally instead - e.g. an OAuth-style callback -
    // rather than by a coroutine this tracker launches itself. Call complete() with the returned
    // id once that external event arrives.
    fun startPending(): String {
        val jobId = UUID.randomUUID().toString()
        jobs[jobId] = CompletableDeferred()
        return jobId
    }

    // Resolves a job started via startPending(). False if jobId is unknown or already completed -
    // callers should treat that as a failure (e.g. a replayed or forged callback).
    fun complete(jobId: String, result: Result<JobResult>): Boolean {
        return jobs[jobId]?.complete(result) ?: false
    }

    suspend fun status(jobId: String): JobStatus {
        val deferred = jobs[jobId] ?: return JobStatus.NotFound
        if (!deferred.isCompleted) return JobStatus.Running
        // Already completed, so this returns immediately rather than suspending.
        return deferred.await().fold(
            onSuccess = { JobStatus.Succeeded(resultPath = it.resultPath, message = it.message) },
            onFailure = { JobStatus.Failed(it.message ?: "Job failed") }
        )
    }
}
