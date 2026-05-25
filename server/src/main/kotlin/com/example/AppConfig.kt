package com.example

import java.nio.file.Path

data class AppConfig(
    val jwtSecret: String,
    val luceneDir: Path,
    val luceneDeprecatedGcSeconds: Long,
    val onnxIntraOpThreads: Int,
) {
    companion object {
        const val DEFAULT_LUCENE_DEPRECATED_GC_SECONDS: Long = 60
        const val DEFAULT_ONNX_INTRA_OP_THREADS: Int = 2

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
            )
    }
}
