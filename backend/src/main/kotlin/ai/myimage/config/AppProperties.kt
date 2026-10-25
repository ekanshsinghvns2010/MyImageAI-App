package ai.myimage.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val ai: Ai = Ai(),
    val models: List<ModelConfig> = emptyList(),
    val security: Security = Security(),
    val storage: Storage = Storage(),
    val jobs: Jobs = Jobs(),
    val cors: Cors = Cors(),
    val limits: Limits = Limits(),
) {
    data class Ai(
        val provider: String = "mock",
        val inferenceUrl: String = "http://localhost:8001",
        val apiToken: String? = null,
        val timeoutSeconds: Long = 120,
        val connectTimeoutSeconds: Long = 5,
    )

    /** Limits and capabilities of one model. Nothing here is assumed about the model: it is all configuration. */
    data class ModelConfig(
        val id: String,
        val displayName: String = id,
        val description: String = "",
        val version: String = "1",
        val enabled: Boolean = true,
        val minWidth: Int = 256,
        val maxWidth: Int = 1024,
        val minHeight: Int = 256,
        val maxHeight: Int = 1024,
        val dimensionMultiple: Int = 8,
        val defaultWidth: Int = 512,
        val defaultHeight: Int = 512,
        val minSteps: Int = 1,
        val maxSteps: Int = 50,
        val defaultSteps: Int = 20,
        val maxImages: Int = 4,
        val maxPromptLength: Int = 1000,
        val supportsNegativePrompt: Boolean = true,
        val supportsSeed: Boolean = true,
    )

    data class Security(
        val adminBootstrapKey: String? = null,
        val signingSecret: String? = null,
        val publicBaseUrl: String = "",
        val signedUrlTtlSeconds: Long = 900,
        val defaultRateLimitPerMinute: Int = 60,
        val defaultDailyQuota: Int = 200,
        val defaultMaxConcurrentJobs: Int = 2,
    )

    data class Storage(
        val type: String = "local",
        val localPath: String = "./data/images",
        val maxImageBytes: Long = 20L * 1024 * 1024,
        val maxImageWidth: Int = 4096,
        val maxImageHeight: Int = 4096,
        val retentionDays: Int = 30,
    )

    data class Jobs(
        val workerConcurrency: Int = 2,
        val maxAttempts: Int = 3,
        val retryBackoffMillis: Long = 2000,
        val syncWaitSeconds: Long = 20,
        val shutdownWaitSeconds: Long = 30,
    )

    data class Cors(val allowedOrigins: List<String> = emptyList())

    data class Limits(
        val maxRequestBytes: Long = 65536,
        val historyMaxPageSize: Int = 100,
    )
}
