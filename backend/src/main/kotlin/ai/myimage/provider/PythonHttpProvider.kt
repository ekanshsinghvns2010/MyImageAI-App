package ai.myimage.provider

import ai.myimage.config.AppProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Base64

/**
 * Talks to the Python inference server (or any remote endpoint implementing the same contract, see README):
 *   POST {url}/v1/generate   GET {url}/v1/health
 * Error bodies from the inference server are logged (truncated) but never returned to API clients.
 */
class PythonHttpProvider(
    private val cfg: AppProperties.Ai,
    override val name: String,
) : ImageGenerationProvider {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()
    private val base = cfg.inferenceUrl.trim().trimEnd('/')
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(cfg.connectTimeoutSeconds))
        .build()

    override val simulated: Boolean = false // the server reports per response whether its output is simulated

    private fun request(path: String, timeout: Duration): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).header("Accept", "application/json")
        cfg.apiToken?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    override fun generate(request: ProviderRequest): ProviderResult {
        val payload = linkedMapOf<String, Any?>(
            "model_id" to request.modelId,
            "prompt" to request.prompt,
            "negative_prompt" to request.negativePrompt,
            "width" to request.width,
            "height" to request.height,
            "steps" to request.steps,
            "seed" to request.seed,
            "num_images" to request.numImages,
        )
        val httpRequest = request("/v1/generate", Duration.ofSeconds(cfg.timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build()

        val response = try {
            client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpConnectTimeoutException) {
            throw ProviderException(ProviderException.Kind.UNAVAILABLE, "Connect timeout to inference server", e)
        } catch (e: HttpTimeoutException) {
            throw ProviderException(ProviderException.Kind.TIMEOUT, "Inference request timed out", e)
        } catch (e: IOException) {
            throw ProviderException(ProviderException.Kind.UNAVAILABLE, "Cannot reach inference server: ${e.javaClass.simpleName}", e)
        }

        val status = response.statusCode()
        if (status != 200) {
            log.warn("Inference server returned HTTP {}: {}", status, response.body().take(300))
            val kind = when (status) {
                502, 503, 504 -> ProviderException.Kind.UNAVAILABLE
                400, 422 -> ProviderException.Kind.REJECTED
                else -> ProviderException.Kind.FAILED
            }
            throw ProviderException(kind, "Inference server returned HTTP $status")
        }
        return parse(response.body(), request.numImages)
    }

    private fun parse(body: String, expectedMax: Int): ProviderResult {
        try {
            val root = mapper.readTree(body)
            val arr = root.path("images")
            if (!arr.isArray || arr.size() == 0 || arr.size() > expectedMax) {
                throw ProviderException(ProviderException.Kind.FAILED, "Inference server returned an unexpected number of images")
            }
            val images = arr.map { node ->
                ProviderImage(
                    bytes = Base64.getDecoder().decode(node.path("data_base64").asText()),
                    mimeType = node.path("mime_type").asText("image/png"),
                    seed = if (node.hasNonNull("seed")) node.get("seed").asLong() else null,
                )
            }
            return ProviderResult(images, root.path("simulated").asBoolean(false))
        } catch (e: ProviderException) {
            throw e
        } catch (e: Exception) {
            throw ProviderException(ProviderException.Kind.FAILED, "Malformed response from inference server", e)
        }
    }

    override fun health(): ProviderHealth {
        return try {
            val r = client.send(request("/v1/health", Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString())
            if (r.statusCode() != 200) return ProviderHealth(false, "inference server unhealthy")
            val json = mapper.readTree(r.body())
            if (json.path("model_loaded").asBoolean(false)) ProviderHealth(true, "model loaded")
            else ProviderHealth(false, "inference server is up but no model is loaded")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ProviderHealth(false, "health check interrupted")
        } catch (e: Exception) {
            ProviderHealth(false, "inference server unreachable")
        }
    }
}
