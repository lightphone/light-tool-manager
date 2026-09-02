package com.thelightphone.toolmanager

import com.thelightphone.toolmanager.datatree.CustomDataTree
import com.thelightphone.toolmanager.datatree.DeferredJobTracker
import com.thelightphone.toolmanager.datatree.FileDataTree
import com.thelightphone.toolmanager.datatree.JobResult
import com.thelightphone.toolmanager.datatree.JobStart
import com.thelightphone.toolmanager.datatree.JobStatus
import com.thelightphone.toolmanager.datatree.RootDataTree
import com.thelightphone.toolmanager.datatree.StaticBranchProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ApplicationTest {

    private val logger = object : Logger {
        override fun log(tag: String, message: String) {
            println("$tag: $message")
        }

        override fun reportError(
            tag: String,
            exception: Throwable?,
            message: String
        ) {
            System.err.println("$tag: $message")
            exception?.let { System.err.println(it.stackTraceToString()) }
        }
    }

    @Test
    fun testRootEndpoint() = testApplication {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "apptest-${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            val provider = FileDataTree(
                tempDir,
                emptyMap()
            )
            val view = LeafView(FileBrowserSpec("files", "files"), provider)
            val rootProvider =
                RootDataTree {
                    BranchView(
                        RootViewSpec("root", ""),
                        StaticBranchProvider(
                            listOf(view)
                        )
                    )
                }
            rootProvider.refreshProviders()

            application {
                module(rootProvider, false, logger)
            }
            val response = client.get("/api/tree")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("files"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testJobLifecycle() = testApplication {
        val jobTracker = DeferredJobTracker(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val jobProvider = object : CustomDataTree() {
            override suspend fun getBytes(filePath: Path): Result<InputStream> {
                return if (filePath.toString() == "result.txt") {
                    Result.success("done".byteInputStream())
                } else {
                    Result.failure(NoSuchElementException("no such result: $filePath"))
                }
            }

            override suspend fun startJob(
                path: Path,
                params: Map<String, String>,
                selfOrigin: String,
                mintCallbackState: (jobId: String) -> String
            ): Result<JobStart> {
                return Result.success(
                    JobStart(
                        jobTracker.start {
                            delay(50.milliseconds)
                            Result.success(JobResult(Path.of("result.txt")))
                        }
                    )
                )
            }

            override suspend fun getJobStatus(path: Path, jobId: String): JobStatus =
                jobTracker.status(jobId)
        }
        val jobView = LeafView(CustomSpec("job-demo", "job-demo"), jobProvider)
        val filesView = LeafView(FileBrowserSpec("files", "files"), object : CustomDataTree() {
            override suspend fun getBytes(filePath: Path): Result<InputStream> =
                Result.failure(UnsupportedOperationException("not a real files leaf"))
        })
        val rootProvider = RootDataTree {
            BranchView(
                RootViewSpec("root", ""),
                StaticBranchProvider(listOf(jobView, filesView))
            )
        }
        rootProvider.refreshProviders()

        application {
            module(rootProvider, false, logger)
        }

        // Starting a job at a path that doesn't support jobs fails synchronously, no job id.
        val unsupported = client.post("/api/job/files")
        assertEquals(HttpStatusCode.NotImplemented, unsupported.status)

        // Kicking off the real job returns a job id right away, before the job finishes.
        val started = client.post("/api/job/job-demo")
        assertEquals(HttpStatusCode.Accepted, started.status)
        val jobId = Json.decodeFromString<JobStartResponse>(started.bodyAsText()).jobId

        val initialStatus = Json.decodeFromString<JobStatusResponse>(
            client.get("/api/job/job-demo?jobId=$jobId").bodyAsText()
        )
        assertEquals(JobState.RUNNING, initialStatus.status)

        val finalStatus = withTimeout(2.seconds) {
            var status: JobStatusResponse
            do {
                delay(20.milliseconds)
                status = Json.decodeFromString(
                    client.get("/api/job/job-demo?jobId=$jobId").bodyAsText()
                )
            } while (status.status == JobState.RUNNING || status.status == JobState.PENDING)
            status
        }
        assertEquals(JobState.SUCCEEDED, finalStatus.status)
        assertEquals("job-demo/result.txt", finalStatus.resultPath)

        val resultResponse = client.get("/api/download/${finalStatus.resultPath}")
        assertEquals(HttpStatusCode.OK, resultResponse.status)
        assertEquals("done", resultResponse.bodyAsText())

        // Right path, but a jobId that provider doesn't recognize - the provider itself, not any
        // API-side registry, is the one reporting "not found".
        val missingJob = client.get("/api/job/job-demo?jobId=does-not-exist")
        assertEquals(HttpStatusCode.NotFound, missingJob.status)

        // A path that doesn't resolve at all is treated the same as an unrecognized jobId.
        val badPath = client.get("/api/job/no-such-path?jobId=$jobId")
        assertEquals(HttpStatusCode.NotFound, badPath.status)
    }

    @Test
    fun testRemoteJobCallback() = testApplication {
        val auth = TotpToolManagerAuth()
        val jobTracker = DeferredJobTracker(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val remoteJobProvider = object : CustomDataTree() {
            override suspend fun getBytes(filePath: Path): Result<InputStream> {
                return if (filePath.toString() == "result.txt") {
                    Result.success("authorized".byteInputStream())
                } else {
                    Result.failure(NoSuchElementException("no such result: $filePath"))
                }
            }

            // Doesn't do any work itself - just hands back a redirectUrl pointing straight at the
            // callback URL built for its own jobId (a real implementation would embed that URL as
            // the redirect_uri of some actual external authorize URL instead).
            override suspend fun startJob(
                path: Path,
                params: Map<String, String>,
                selfOrigin: String,
                mintCallbackState: (jobId: String) -> String
            ): Result<JobStart> {
                val jobId = jobTracker.startPending()
                val state = mintCallbackState(jobId)
                val redirectUrl = "$selfOrigin$JobCallbackPath?state=${state.encodeURLParameter()}"
                return Result.success(JobStart(jobId, redirectUrl))
            }

            override suspend fun getJobStatus(path: Path, jobId: String): JobStatus =
                jobTracker.status(jobId)

            override suspend fun completeJob(
                path: Path,
                jobId: String,
                data: Map<String, String>
            ): Result<Unit> {
                val code = data["code"] ?: return Result.failure(IllegalArgumentException("missing code"))
                return if (jobTracker.complete(jobId, Result.success(JobResult(Path.of("result.txt"))))) {
                    Result.success(Unit)
                } else {
                    Result.failure(NoSuchElementException("unknown job: $jobId ($code)"))
                }
            }
        }
        val remoteJobView = LeafView(CustomSpec("job-remote", "job-remote"), remoteJobProvider)
        val rootProvider = RootDataTree {
            BranchView(RootViewSpec("root", ""), StaticBranchProvider(listOf(remoteJobView)))
        }
        rootProvider.refreshProviders()

        application {
            module(rootProvider, false, logger, auth)
        }

        suspend fun signedRequest(
            method: HttpMethod,
            path: String,
            block: HttpRequestBuilder.() -> Unit = {}
        ): HttpResponse {
            val timestampMillis = System.currentTimeMillis()
            val signature = signRequest(auth.primaryKey, method.value, path, timestampMillis)
            return client.request(path) {
                this.method = method
                parameter(SignatureQueryParam, signature)
                parameter(TimestampQueryParam, timestampMillis)
                block()
            }
        }

        // Starting the job (like every other /api/* route) still requires the normal signature.
        val unsigned = client.post("/api/job/job-remote")
        assertEquals(HttpStatusCode.Unauthorized, unsigned.status)

        val started = signedRequest(HttpMethod.Post, "/api/job/job-remote")
        assertEquals(HttpStatusCode.Accepted, started.status)
        val jobStart = Json.decodeFromString<JobStartResponse>(started.bodyAsText())
        val redirectUrl = requireNotNull(jobStart.redirectUrl)
        assertTrue(redirectUrl.contains("/api/job-callback"))
        assertTrue(redirectUrl.contains("state="))

        // The callback itself needs no signature - a third party's browser redirect can't attach
        // one - but it does need a validly-signed `state`. Nothing else on this route is trusted:
        // path/jobId/origin all have to come from state, since a provider requiring an exact,
        // pre-registered redirect_uri wouldn't tolerate us appending our own query params to it.
        val forgedState = client.get("/api/job-callback?state=bogus&code=x")
        assertEquals(HttpStatusCode.Unauthorized, forgedState.status)

        // Following the actual redirect the job handed back (plus a fake authorization code, as
        // if a real remote party appended one) completes the job and redirects the browser back
        // into the app itself (its own job screen), not to a generic static page.
        val nonFollowingClient = createClient { followRedirects = false }
        val callbackResponse = nonFollowingClient.get("$redirectUrl&code=demo-code-123")
        assertEquals(HttpStatusCode.Found, callbackResponse.status)
        val resumeLocation = requireNotNull(callbackResponse.headers[HttpHeaders.Location])
        assertTrue(resumeLocation.contains("resumeJob=job-remote"))
        assertTrue(resumeLocation.contains("jobId=${jobStart.jobId}"))

        val finalStatus = Json.decodeFromString<JobStatusResponse>(
            signedRequest(HttpMethod.Get, "/api/job/job-remote") {
                parameter("jobId", jobStart.jobId)
            }.bodyAsText()
        )
        assertEquals(JobState.SUCCEEDED, finalStatus.status)
        assertEquals("job-remote/result.txt", finalStatus.resultPath)
    }
}
