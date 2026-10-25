package ai.myimage.jobs

import ai.myimage.common.ApiException
import ai.myimage.security.ClientPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/images/generations")
@Tag(name = "Image generation")
class ImageController(private val service: JobService) {
    private val idempotencyPattern = Regex("^[A-Za-z0-9._:-]{1,128}$")

    @PostMapping
    @Operation(
        summary = "Submit a generation job",
        description = "Returns 202 with a job to poll. With ?wait=true the request waits up to a bounded time " +
            "(default 20s) and returns 200 if the job finished, otherwise 202. " +
            "Send an Idempotency-Key header to make retries safe."
    )
    fun create(
        @AuthenticationPrincipal principal: ClientPrincipal,
        @Valid @RequestBody body: GenerationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestParam(name = "wait", defaultValue = "false") wait: Boolean,
    ): ResponseEntity<GenerationResponse> {
        if (idempotencyKey != null && !idempotencyPattern.matches(idempotencyKey)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Idempotency-Key must be 1-128 characters of A-Z a-z 0-9 . _ : -")
        }
        val response = service.submit(principal, body, idempotencyKey, wait)
        val status = if (response.status.terminal) HttpStatus.OK else HttpStatus.ACCEPTED
        return ResponseEntity.status(status).body(response)
    }

    @GetMapping("/history")
    @Operation(summary = "List this API key's jobs, newest first (paginated)")
    fun history(
        @AuthenticationPrincipal principal: ClientPrincipal,
        @RequestParam(name = "page", defaultValue = "0") page: Int,
        @RequestParam(name = "page_size", defaultValue = "20") pageSize: Int,
        @RequestParam(name = "status", required = false) status: JobStatus?,
    ): PageResponse<GenerationResponse> = service.history(principal, page, pageSize, status)

    @GetMapping("/{jobId}")
    @Operation(summary = "Job status (includes images when COMPLETED)")
    fun get(
        @AuthenticationPrincipal principal: ClientPrincipal,
        @PathVariable("jobId") jobId: UUID,
    ): GenerationResponse = service.get(principal, jobId)

    @GetMapping("/{jobId}/result")
    @Operation(summary = "Generated image information; 409 JOB_NOT_COMPLETED until the job is COMPLETED")
    fun result(
        @AuthenticationPrincipal principal: ClientPrincipal,
        @PathVariable("jobId") jobId: UUID,
    ): GenerationResult = service.result(principal, jobId)

    @DeleteMapping("/{jobId}")
    @Operation(
        summary = "Cancel a QUEUED job",
        description = "Jobs already PROCESSING cannot be cancelled (409 CANCELLATION_NOT_SUPPORTED) because the inference backend cannot be interrupted."
    )
    fun cancel(
        @AuthenticationPrincipal principal: ClientPrincipal,
        @PathVariable("jobId") jobId: UUID,
    ): GenerationResponse = service.cancel(principal, jobId)
}
