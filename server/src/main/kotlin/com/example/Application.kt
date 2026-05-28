package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.application.acquisition.AcquisitionService
import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.application.profile.CachingProfileAdapter
import com.example.domain.cluster.ClusterService
import com.example.domain.embedding.EmbedLookupPort
import com.example.domain.events.EventQueue
import com.example.domain.inference.InferenceService
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserEvent
import com.example.domain.model.UserProfile
import com.example.infrastructure.content.CdnContentSource
import com.example.infrastructure.content.E621ContentSource
import com.example.infrastructure.content.ImagePreprocessor
import com.example.infrastructure.content.UnsplashContentSource
import com.example.infrastructure.lucene.LuceneAdapter
import com.example.infrastructure.onnx.OnnxInferenceAdapter
import com.example.infrastructure.sqlite.AcquisitionJobRepository
import com.example.infrastructure.sqlite.EmbeddingGenerationRepository
import com.example.infrastructure.sqlite.EventBatcher
import com.example.infrastructure.sqlite.EventData
import com.example.infrastructure.sqlite.EventRepository
import com.example.infrastructure.sqlite.ItemRepository
import com.example.infrastructure.sqlite.ProfileRepository
import com.example.infrastructure.sqlite.initSchema
import com.example.infrastructure.storage.LocalObjectStore
import com.example.routing.configureHealthRoutes
import com.example.routing.handlers.AcquisitionHandler
import com.example.routing.handlers.IngestionHandler
import com.example.routing.handlers.RankingHandler
import com.example.routing.routes.configureAcquisitionRoutes
import com.example.routing.routes.configureIngestionRoutes
import com.example.routing.routes.configureRankingRoutes
import com.example.workers.EventBatcherWorker
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.nio.file.Files

private val log = LoggerFactory.getLogger("com.example.Application")

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

    val profileRepo = ProfileRepository(db)
    val eventRepo = EventRepository(db)

    val embedLookup: EmbedLookupPort = { ids ->
        ids.mapNotNull { id -> lucene.getVector(id)?.let { id to it } }.toMap()
    }
    val cachingEmbedding = CachingEmbeddingAdapter(lookupFromStore = embedLookup)

    val cachingProfile =
        CachingProfileAdapter(
            loadProfile = { userId ->
                profileRepo.load(userId)?.let { row ->
                    UserProfile(
                        userId = row.userId,
                        embeddingVersion = EmbeddingVersion(row.embeddingVersion),
                        positivePrototypes = emptyList(),
                        negativePrototypes = emptyList(),
                        // Prototypes are populated by worker-proto-split (wave 27).
                        sessionVector = FloatArray(Prototype.VECTOR_DIM),
                        longTermVector = FloatArray(Prototype.VECTOR_DIM),
                        lastAppliedEventId = row.lastAppliedEventId,
                    )
                }
            },
            loadEvents = { userId, sinceId ->
                val rows = eventRepo.loadSince(userId, sinceId)
                val vecs = cachingEmbedding.lookupVectors(rows.map { it.itemId })
                rows.mapNotNull { row ->
                    val vec = vecs[row.itemId] ?: return@mapNotNull null
                    UserEvent(
                        id = 0L,
                        userId = row.userId,
                        itemId = row.itemId,
                        weight = row.weight,
                        embeddingVersion = EmbeddingVersion(row.embeddingVersion),
                        ts = 0L,
                    ) to vec
                }
            },
            saveProfile = { profile ->
                profileRepo.upsert(profile.userId, profile.embeddingVersion.value, profile.lastAppliedEventId)
            },
        )

    val eventBatcher = EventBatcher(flush = { events -> eventRepo.appendBatch(events) })
    val eventQueue: EventQueue =
        EventQueue { event ->
            eventBatcher.enqueue(EventData(event.userId, event.itemId, event.weight, event.embeddingVersion.value))
        }
    acquisitionScope.launch { EventBatcherWorker(eventBatcher).run() }

    val ingestionHandler =
        IngestionHandler(
            cachingProfile = cachingProfile,
            cachingEmbedding = cachingEmbedding,
            eventQueue = eventQueue,
            activeEmbeddingVersion = { EmbeddingVersion(embeddingVersionRepo.getActiveVersion() ?: "unknown") },
        )
    configureIngestionRoutes(ingestionHandler)

    val clusterService: ClusterService? =
        config.clustersPath?.let { path ->
            runCatching { ClusterService.load(path) }
                .onFailure { log.warn("Failed to load clusters from $path — ε-exploration disabled") }
                .getOrNull()
        }

    val rankingHandler =
        RankingHandler(
            cachingProfile = cachingProfile,
            cachingEmbedding = cachingEmbedding,
            clusterService = clusterService,
            activeEmbeddingVersion = { EmbeddingVersion(embeddingVersionRepo.getActiveVersion() ?: "unknown") },
        )
    configureRankingRoutes(rankingHandler)

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
