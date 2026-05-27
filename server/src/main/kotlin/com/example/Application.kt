package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.application.acquisition.AcquisitionService
import com.example.domain.inference.InferenceService
import com.example.infrastructure.content.CdnContentSource
import com.example.infrastructure.content.E621ContentSource
import com.example.infrastructure.content.ImagePreprocessor
import com.example.infrastructure.content.UnsplashContentSource
import com.example.infrastructure.lucene.LuceneAdapter
import com.example.infrastructure.onnx.OnnxInferenceAdapter
import com.example.infrastructure.sqlite.AcquisitionJobRepository
import com.example.infrastructure.sqlite.EmbeddingGenerationRepository
import com.example.infrastructure.sqlite.ItemRepository
import com.example.infrastructure.sqlite.initSchema
import com.example.infrastructure.storage.LocalObjectStore
import com.example.routing.configureHealthRoutes
import com.example.routing.handlers.AcquisitionHandler
import com.example.routing.routes.configureAcquisitionRoutes
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Files

// ViT-B/16 (DINOv2 / CLIP-style export) node names.
private val ONNX_INPUT_SHAPE = longArrayOf(1, 3, 224, 224)
private const val ONNX_INPUT_NAME = "pixel_values"
private const val ONNX_OUTPUT_NAME = "image_embeds"

// Entry point loaded by Ktor EngineMain via application.yaml.
fun Application.configure() {
    val config = AppConfig.load()
    val gate = ReadinessGate()
    configure(gate, config.jwtSecret)

    val db =
        Database.connect(
            url = "jdbc:sqlite:${config.sqlitePath}",
            driver = "org.sqlite.JDBC",
        )
    initSchema(db)

    val lucene = LuceneAdapter(config.luceneDir)
    val objectStore = LocalObjectStore(config.objectStoreDir)

    val modelBytes = Files.readAllBytes(config.onnxModelPath)
    val onnxAdapter =
        OnnxInferenceAdapter(
            modelBytes = modelBytes,
            expectedSha256 = config.onnxModelSha256,
            inputName = ONNX_INPUT_NAME,
            outputName = ONNX_OUTPUT_NAME,
            intraOpThreads = config.onnxIntraOpThreads,
        )

    val preprocessor = ImagePreprocessor()
    val inferenceService =
        InferenceService(
            // Ports are non-suspend; bridge via runBlocking inside Dispatchers.Default.
            preprocess = { bytes -> runBlocking { preprocessor.preprocess(bytes) } },
            infer = { tensor -> runBlocking { onnxAdapter.infer(tensor, ONNX_INPUT_SHAPE) } },
        )

    val jobRepo = AcquisitionJobRepository(db)
    val itemRepo = ItemRepository(db)
    val embeddingVersionRepo = EmbeddingGenerationRepository(db)

    val httpClient = HttpClient(CIO)

    val contentSources =
        mapOf(
            "unsplash" to UnsplashContentSource(config.unsplash, httpClient),
            "e621" to E621ContentSource(config.e621, httpClient),
            "cdn" to CdnContentSource(httpClient),
        )

    val acquisitionService =
        AcquisitionService(
            jobRepository = jobRepo,
            inferenceService = inferenceService,
            itemRepository = itemRepo,
            luceneAdapter = lucene,
            objectStore = objectStore,
            activeEmbeddingVersion = { embeddingVersionRepo.getActiveVersion() ?: "unknown" },
            contentSources = contentSources,
        )

    val acquisitionScope = CoroutineScope(SupervisorJob())
    val acquisitionHandler = AcquisitionHandler(acquisitionService, acquisitionScope)

    configureAcquisitionRoutes(acquisitionHandler)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            acquisitionScope.cancel()
            lucene.close()
            onnxAdapter.close()
            httpClient.close()
        },
    )

    gate.markReady()
}

// Testable entry point — caller controls readiness state and JWT secret.
// Pass an empty jwtSecret to skip JWT auth (only safe in test scope where
// no authenticate("kurai") routes are installed).
fun Application.configure(
    readinessGate: ReadinessGate,
    jwtSecret: String = "",
) {
    install(ContentNegotiation) { json() }
    // Default CallLogging format: logs method + URI + status, not headers.
    // Authorization header values never appear in log output.
    install(CallLogging)
    install(StatusPages) { errorMapping() }
    install(Authentication) {
        jwt("kurai") {
            verifier(
                JWT
                    .require(Algorithm.HMAC256(jwtSecret))
                    .withClaimPresence("sub")
                    .build(),
            )
            validate { credential ->
                credential.payload
                    .getClaim("sub")
                    .asString()
                    .takeIf { !it.isNullOrBlank() }
                    ?.let { JWTPrincipal(credential.payload) }
            }
        }
    }

    configureHealthRoutes(readinessGate)
}
