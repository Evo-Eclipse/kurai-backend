package com.example

import com.example.infrastructure.content.E621Config
import com.example.infrastructure.content.UnsplashConfig
import java.nio.file.Path

data class AppConfig(
    val jwtSecret: String,
    val luceneDir: Path,
    val luceneDeprecatedGcSeconds: Long,
    val onnxIntraOpThreads: Int,
    val unsplash: UnsplashConfig,
    val e621: E621Config,
    val sqlitePath: Path,
    val objectStoreDir: Path,
    val onnxModelPath: Path,
    val onnxModelSha256: String,
) {
    companion object {
        const val DEFAULT_LUCENE_DEPRECATED_GC_SECONDS: Long = 60
        const val DEFAULT_ONNX_INTRA_OP_THREADS: Int = 2
        const val DEFAULT_UNSPLASH_BASE_URL: String = "https://api.unsplash.com"
        const val DEFAULT_E621_BASE_URL: String = "https://e621.net"

        fun load(env: Map<String, String> = System.getenv()): AppConfig =
            AppConfig(
                jwtSecret =
                    env["KURAI_JWT_SECRET"]
                        ?: error("Missing required environment variable: KURAI_JWT_SECRET"),
                luceneDir =
                    env["KURAI_LUCENE_DIR"]?.let(Path::of)
                        ?: error("Missing required environment variable: KURAI_LUCENE_DIR"),
                luceneDeprecatedGcSeconds =
                    env["KURAI_LUCENE_DEPRECATED_GC_SECONDS"]?.toLong()
                        ?: DEFAULT_LUCENE_DEPRECATED_GC_SECONDS,
                onnxIntraOpThreads =
                    env["KURAI_ONNX_INTRA_OP_THREADS"]?.toInt()
                        ?: DEFAULT_ONNX_INTRA_OP_THREADS,
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
            )
    }
}
