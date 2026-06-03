package com.example

import com.example.infrastructure.content.E621Config
import com.example.infrastructure.content.UnsplashConfig
import java.nio.file.Path

data class AppConfig(
    val jwtSecret: String,
    val luceneDir: Path,
    val onnxIntraOpThreads: Int,
    /** Max concurrent ONNX `infer` calls (bounded IO dispatcher). */
    val onnxInferenceParallelism: Int,
    val unsplash: UnsplashConfig,
    val e621: E621Config,
    val sqlitePath: Path,
    val objectStoreDir: Path,
    val onnxModelPath: Path,
    val onnxModelSha256: String,
    val profilePersistIntervalMs: Long,
    val kMeansCheckIntervalMs: Long,
    val authJwtTtlMs: Long,
    val authSessionTtlMs: Long,
    val authChallengeTtlMs: Long,
    /** When true, OTP/magic-link codes are logged at INFO instead of sent by mail. */
    val authMailStub: Boolean,
    val keyIssueRateLimitMax: Int,
    val keyIssueRateLimitWindowMs: Long,
    val sessionGcIntervalMs: Long,
    val sessionGcRetentionMs: Long,
) {
    init {
        require(onnxIntraOpThreads > 0) { "KURAI_ONNX_INTRA_OP_THREADS should be positive" }
        require(onnxInferenceParallelism > 0) {
            "KURAI_ONNX_INFERENCE_PARALLELISM should be positive"
        }
        require(profilePersistIntervalMs > 0) { "KURAI_PROFILE_PERSIST_INTERVAL_MS should be positive" }
        require(kMeansCheckIntervalMs > 0) { "KURAI_KMEANS_CHECK_INTERVAL_MS should be positive" }
        require(authJwtTtlMs > 0) { "KURAI_AUTH_JWT_TTL_MS should be positive" }
        require(authSessionTtlMs > authJwtTtlMs) {
            "KURAI_AUTH_SESSION_TTL_MS must exceed KURAI_AUTH_JWT_TTL_MS (refresh outlives JWT)"
        }
        require(authChallengeTtlMs > 0) { "KURAI_AUTH_CHALLENGE_TTL_MS should be positive" }
        require(keyIssueRateLimitMax > 0) { "KURAI_KEY_ISSUE_RATE_LIMIT_MAX should be positive" }
        require(keyIssueRateLimitWindowMs > 0) { "KURAI_KEY_ISSUE_RATE_LIMIT_WINDOW_MS should be positive" }
        require(sessionGcIntervalMs > 0) { "KURAI_SESSION_GC_INTERVAL_MS should be positive" }
        require(sessionGcRetentionMs > 0) { "KURAI_SESSION_GC_RETENTION_MS should be positive" }
    }

    companion object {
        const val DEFAULT_ONNX_INTRA_OP_THREADS: Int = 2
        const val DEFAULT_ONNX_INFERENCE_PARALLELISM: Int = 1
        const val DEFAULT_UNSPLASH_BASE_URL: String = "https://api.unsplash.com"
        const val DEFAULT_E621_BASE_URL: String = "https://e621.net"
        const val DEFAULT_PROFILE_PERSIST_INTERVAL_MS: Long = 30_000
        const val DEFAULT_KMEANS_CHECK_INTERVAL_MS: Long = 3_600_000
        const val DEFAULT_AUTH_JWT_TTL_MS: Long = 24L * 60L * 60L * 1000L // 1 day
        const val DEFAULT_AUTH_SESSION_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L // 30 days
        const val DEFAULT_AUTH_CHALLENGE_TTL_MS: Long = 10L * 60L * 1000L // 10 minutes
        const val DEFAULT_KEY_ISSUE_RATE_LIMIT_MAX: Int = 10
        const val DEFAULT_KEY_ISSUE_RATE_LIMIT_WINDOW_MS: Long = 60L * 1000L // 1 minute
        const val DEFAULT_SESSION_GC_INTERVAL_MS: Long = 60L * 60L * 1000L // 1 hour
        const val DEFAULT_SESSION_GC_RETENTION_MS: Long = 24L * 60L * 60L * 1000L // 1 day past expiry

        fun load(env: Map<String, String> = System.getenv()): AppConfig =
            AppConfig(
                jwtSecret =
                    env["KURAI_JWT_SECRET"]
                        ?: error("Missing required environment variable: KURAI_JWT_SECRET"),
                luceneDir =
                    env["KURAI_LUCENE_DIR"]?.let(Path::of)
                        ?: error("Missing required environment variable: KURAI_LUCENE_DIR"),
                onnxIntraOpThreads =
                    env["KURAI_ONNX_INTRA_OP_THREADS"]?.toInt()
                        ?: DEFAULT_ONNX_INTRA_OP_THREADS,
                onnxInferenceParallelism =
                    env["KURAI_ONNX_INFERENCE_PARALLELISM"]?.toInt()
                        ?: DEFAULT_ONNX_INFERENCE_PARALLELISM,
                unsplash =
                    UnsplashConfig(
                        baseUrl = env["KURAI_UNSPLASH_BASE_URL"] ?: DEFAULT_UNSPLASH_BASE_URL,
                        userAgent =
                            env["KURAI_UNSPLASH_USER_AGENT"]
                                ?: error("Missing required environment variable: KURAI_UNSPLASH_USER_AGENT"),
                        accessKey =
                            env["KURAI_UNSPLASH_ACCESS_KEY"]
                                ?: error("Missing required environment variable: KURAI_UNSPLASH_ACCESS_KEY"),
                    ),
                e621 =
                    E621Config(
                        baseUrl = env["KURAI_E621_BASE_URL"] ?: DEFAULT_E621_BASE_URL,
                        userAgent =
                            env["KURAI_E621_USER_AGENT"]
                                ?: error("Missing required environment variable: KURAI_E621_USER_AGENT"),
                        username =
                            env["KURAI_E621_USERNAME"]
                                ?: error("Missing required environment variable: KURAI_E621_USERNAME"),
                        accessKey =
                            env["KURAI_E621_ACCESS_KEY"]
                                ?: error("Missing required environment variable: KURAI_E621_ACCESS_KEY"),
                    ),
                sqlitePath =
                    env["KURAI_SQLITE_PATH"]?.let(Path::of)
                        ?: error("Missing required environment variable: KURAI_SQLITE_PATH"),
                objectStoreDir =
                    env["KURAI_OBJECT_STORE_DIR"]?.let(Path::of)
                        ?: error("Missing required environment variable: KURAI_OBJECT_STORE_DIR"),
                onnxModelPath =
                    env["KURAI_ONNX_MODEL_PATH"]?.let(Path::of)
                        ?: error("Missing required environment variable: KURAI_ONNX_MODEL_PATH"),
                onnxModelSha256 =
                    env["KURAI_ONNX_MODEL_SHA256"]
                        ?: error("Missing required environment variable: KURAI_ONNX_MODEL_SHA256"),
                profilePersistIntervalMs =
                    env["KURAI_PROFILE_PERSIST_INTERVAL_MS"]?.toLong()
                        ?: DEFAULT_PROFILE_PERSIST_INTERVAL_MS,
                kMeansCheckIntervalMs =
                    env["KURAI_KMEANS_CHECK_INTERVAL_MS"]?.toLong()
                        ?: DEFAULT_KMEANS_CHECK_INTERVAL_MS,
                authJwtTtlMs =
                    env["KURAI_AUTH_JWT_TTL_MS"]?.toLong()
                        ?: DEFAULT_AUTH_JWT_TTL_MS,
                authSessionTtlMs =
                    env["KURAI_AUTH_SESSION_TTL_MS"]?.toLong()
                        ?: DEFAULT_AUTH_SESSION_TTL_MS,
                authChallengeTtlMs =
                    env["KURAI_AUTH_CHALLENGE_TTL_MS"]?.toLong()
                        ?: DEFAULT_AUTH_CHALLENGE_TTL_MS,
                authMailStub = env.parseBooleanFlag("KURAI_AUTH_MAIL_STUB"),
                keyIssueRateLimitMax =
                    env["KURAI_KEY_ISSUE_RATE_LIMIT_MAX"]?.toInt()
                        ?: DEFAULT_KEY_ISSUE_RATE_LIMIT_MAX,
                keyIssueRateLimitWindowMs =
                    env["KURAI_KEY_ISSUE_RATE_LIMIT_WINDOW_MS"]?.toLong()
                        ?: DEFAULT_KEY_ISSUE_RATE_LIMIT_WINDOW_MS,
                sessionGcIntervalMs =
                    env["KURAI_SESSION_GC_INTERVAL_MS"]?.toLong()
                        ?: DEFAULT_SESSION_GC_INTERVAL_MS,
                sessionGcRetentionMs =
                    env["KURAI_SESSION_GC_RETENTION_MS"]?.toLong()
                        ?: DEFAULT_SESSION_GC_RETENTION_MS,
            )

        private fun Map<String, String>.parseBooleanFlag(name: String): Boolean =
            this[name]?.lowercase() in setOf("1", "true", "yes", "on")
    }
}
