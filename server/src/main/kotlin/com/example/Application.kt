package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.acquisition.AcquisitionHandler
import com.example.acquisition.configureAcquisitionRoutes
import com.example.application.acquisition.AcquisitionService
import com.example.application.auth.AuthService
import com.example.application.auth.LoggingMagicLinkSender
import com.example.application.auth.MagicLinkSender
import com.example.application.catalog.KMeansScheduler
import com.example.application.config.ConfigKey
import com.example.application.config.RuntimeConfig
import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.application.profile.CachingProfileAdapter
import com.example.application.profile.EventBatcherWorker
import com.example.application.profile.ProfileMigrationWorker
import com.example.application.profile.ProfilePersistWorker
import com.example.application.profile.ProtoSplitWorker
import com.example.auth.AuthHandler
import com.example.auth.ChallengeIpRateLimiter
import com.example.auth.FixedWindowRateLimiter
import com.example.auth.SessionAuthenticator
import com.example.auth.configureAuthRoutes
import com.example.domain.cluster.ClusterService
import com.example.domain.embedding.EmbedLookupPort
import com.example.domain.events.EventQueue
import com.example.domain.inference.InferenceService
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserEvent
import com.example.domain.model.UserProfile
import com.example.health.configureHealthRoutes
import com.example.infrastructure.content.CdnContentSource
import com.example.infrastructure.content.ContentSource
import com.example.infrastructure.content.E621ContentSource
import com.example.infrastructure.content.ImagePreprocessor
import com.example.infrastructure.content.UnsplashContentSource
import com.example.infrastructure.lucene.LuceneAdapter
import com.example.infrastructure.onnx.OnnxInferenceAdapter
import com.example.infrastructure.sqlite.AcquisitionJobRepository
import com.example.infrastructure.sqlite.AuthIdentityRepository
import com.example.infrastructure.sqlite.AuthSessionRepository
import com.example.infrastructure.sqlite.EmbeddingGenerationRepository
import com.example.infrastructure.sqlite.EventBatcher
import com.example.infrastructure.sqlite.EventData
import com.example.infrastructure.sqlite.EventRepository
import com.example.infrastructure.sqlite.ItemRepository
import com.example.infrastructure.sqlite.LoginChallengeRepository
import com.example.infrastructure.sqlite.ProfileRepository
import com.example.infrastructure.sqlite.PrototypeRepository
import com.example.infrastructure.sqlite.PrototypeType
import com.example.infrastructure.sqlite.RuntimeConfigRepository
import com.example.infrastructure.sqlite.UserRepository
import com.example.infrastructure.sqlite.initSchema
import com.example.infrastructure.storage.LocalObjectStore
import com.example.ingestion.IngestionHandler
import com.example.ingestion.configureIngestionRoutes
import com.example.profile.RankingHandler
import com.example.profile.configureRankingRoutes
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.statuspages.StatusPages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger("com.example.Application")
private const val SHUTDOWN_DEADLINE_MS = 25_000L

/**
 * ViT DINOv3 / CLIP-style export shape and node names.
 */
private val ONNX_INPUT_SHAPE = longArrayOf(1, 3, 224, 224)
private const val ONNX_INPUT_NAME = "pixel_values"
private const val ONNX_OUTPUT_NAME = "image_embeds"

/**
 * Provides the full object graph via Ktor native DI.
 *
 * Heavy initialisers (Database open, ONNX load, Lucene open) are
 * wrapped in `withContext(Dispatchers.IO)` inside `provide` blocks
 * so Ktor's concurrent module startup can run them in parallel —
 * see `application.startup: concurrent` in application.yaml.
 */
suspend fun Application.installCore() {
    val config = AppConfig.load()
    dependencies.provide<AppConfig> { config }
    dependencies.provide<ReadinessGate> { ReadinessGate() }
    dependencies.provide<AcquisitionScope> { AcquisitionScope(CoroutineScope(SupervisorJob())) }

    dependencies.provide<Database> {
        withContext(Dispatchers.IO) {
            Database
                .connect("jdbc:sqlite:${config.sqlitePath}", driver = "org.sqlite.JDBC")
                .also { initSchema(it) }
        }
    }
    dependencies.provide<OnnxInferenceAdapter> {
        withContext(Dispatchers.IO) {
            OnnxInferenceAdapter(
                modelBytes = Files.readAllBytes(config.onnxModelPath),
                expectedSha256 = config.onnxModelSha256,
                inputName = ONNX_INPUT_NAME,
                outputName = ONNX_OUTPUT_NAME,
                intraOpThreads = config.onnxIntraOpThreads,
            )
        }
    }
    dependencies.provide<LuceneAdapter> {
        withContext(Dispatchers.IO) { LuceneAdapter(config.luceneDir) }
    }
    dependencies.provide<LocalObjectStore> { LocalObjectStore(config.objectStoreDir) }
    dependencies.provide<HttpClient> { HttpClient(CIO) }
    dependencies.provide<ImagePreprocessor> { ImagePreprocessor() }

    dependencies.provide<AcquisitionJobRepository> { AcquisitionJobRepository(dependencies.resolve()) }
    dependencies.provide<ItemRepository> { ItemRepository(dependencies.resolve()) }
    dependencies.provide<EmbeddingGenerationRepository> { EmbeddingGenerationRepository(dependencies.resolve()) }
    dependencies.provide<ProfileRepository> { ProfileRepository(dependencies.resolve()) }
    dependencies.provide<EventRepository> { EventRepository(dependencies.resolve()) }
    dependencies.provide<PrototypeRepository> { PrototypeRepository(dependencies.resolve()) }
    dependencies.provide<UserRepository> { UserRepository(dependencies.resolve()) }
    dependencies.provide<AuthIdentityRepository> { AuthIdentityRepository(dependencies.resolve()) }
    dependencies.provide<AuthSessionRepository> { AuthSessionRepository(dependencies.resolve()) }
    dependencies.provide<LoginChallengeRepository> { LoginChallengeRepository(dependencies.resolve()) }
    dependencies.provide<RuntimeConfigRepository> { RuntimeConfigRepository(dependencies.resolve()) }

    dependencies.provide<RuntimeConfig> {
        val runtime = RuntimeConfig(dependencies.resolve<RuntimeConfigRepository>())
        // Seed bootstrap defaults so the first AuthService TTL lookup
        // does not blow up on a fresh database. Operator-set values
        // (if a row already exists) are preserved across restarts.
        runtime.seedIfMissing(ConfigKey.AuthSessionTtlMs, config.authSessionTtlMs.toString())
        runtime.seedIfMissing(ConfigKey.AuthChallengeTtlMs, config.authChallengeTtlMs.toString())
        runtime
    }

    dependencies.provide<MagicLinkSender> {
        if (config.authMailStub) {
            LoggingMagicLinkSender()
        } else {
            error(
                "No outbound mail sender configured. Set KURAI_AUTH_MAIL_STUB=true for local development.",
            )
        }
    }
    dependencies.provide<AuthService> {
        val runtime = dependencies.resolve<RuntimeConfig>()
        AuthService(
            users = dependencies.resolve(),
            identities = dependencies.resolve(),
            sessions = dependencies.resolve(),
            challenges = dependencies.resolve(),
            sender = dependencies.resolve(),
            challengeTtlMs = { runtime.get(ConfigKey.AuthChallengeTtlMs) },
            sessionTtlMs = { runtime.get(ConfigKey.AuthSessionTtlMs) },
        )
    }
    dependencies.provide<SessionAuthenticator> {
        SessionAuthenticator(authService = dependencies.resolve())
    }
    dependencies.provide<ChallengeIpRateLimiter> {
        ChallengeIpRateLimiter(
            FixedWindowRateLimiter(
                maxPerWindow = { AuthService.DEFAULT_CHALLENGE_RATE_LIMIT_MAX },
                windowMs = { AuthService.DEFAULT_CHALLENGE_RATE_LIMIT_WINDOW_MS },
            ),
        )
    }
    dependencies.provide<AuthHandler> {
        AuthHandler(
            authService = dependencies.resolve(),
            sessionAuth = dependencies.resolve(),
            challengeIpRateLimiter = dependencies.resolve(),
            jwtSecret = config.jwtSecret,
            jwtTtlMs = config.authJwtTtlMs,
        )
    }

    dependencies.provide<EmbeddingVersionLookup> {
        val repo = dependencies.resolve<EmbeddingGenerationRepository>()
        EmbeddingVersionLookup { repo.getActiveVersion() ?: "unknown" }
    }

    dependencies.provide<InferenceService> {
        val preprocessor = dependencies.resolve<ImagePreprocessor>()
        val onnx = dependencies.resolve<OnnxInferenceAdapter>()
        InferenceService(
            preprocess = { bytes -> preprocessor.preprocess(bytes) },
            infer = { tensor -> onnx.infer(tensor, ONNX_INPUT_SHAPE) },
        )
    }

    dependencies.provide<ContentSourceRegistry> {
        val httpClient = dependencies.resolve<HttpClient>()
        ContentSourceRegistry(
            mapOf(
                "unsplash" to UnsplashContentSource(config.unsplash, httpClient),
                "e621" to E621ContentSource(config.e621, httpClient),
                "cdn" to CdnContentSource(httpClient),
            ),
        )
    }

    dependencies.provide<AcquisitionService> {
        AcquisitionService(
            jobRepository = dependencies.resolve(),
            inferenceService = dependencies.resolve(),
            itemRepository = dependencies.resolve(),
            luceneAdapter = dependencies.resolve(),
            objectStore = dependencies.resolve(),
            activeEmbeddingVersion = dependencies.resolve<EmbeddingVersionLookup>().asStringLookup(),
            contentSources = dependencies.resolve<ContentSourceRegistry>().byName,
        )
    }
    dependencies.provide<AcquisitionHandler> {
        AcquisitionHandler(dependencies.resolve(), dependencies.resolve<AcquisitionScope>().scope)
    }

    dependencies.provide<CachingEmbeddingAdapter> {
        val lucene = dependencies.resolve<LuceneAdapter>()
        val embedLookup: EmbedLookupPort = { ids ->
            ids.mapNotNull { id -> lucene.getVector(id)?.let { id to it } }.toMap()
        }
        CachingEmbeddingAdapter(lookupFromStore = embedLookup)
    }

    dependencies.provide<CachingProfileAdapter> {
        val profileRepo = dependencies.resolve<ProfileRepository>()
        val eventRepo = dependencies.resolve<EventRepository>()
        val prototypeRepo = dependencies.resolve<PrototypeRepository>()
        val cachingEmbedding = dependencies.resolve<CachingEmbeddingAdapter>()
        CachingProfileAdapter(
            loadProfile = { userId ->
                profileRepo.load(userId)?.let { row ->
                    val protos = prototypeRepo.load(userId)
                    UserProfile(
                        userId = row.userId,
                        embeddingVersion = EmbeddingVersion(row.embeddingVersion),
                        positivePrototypes =
                            protos
                                .filter { it.prototypeType == PrototypeType.POSITIVE }
                                .map { Prototype(it.vector, it.weight.toFloat()) },
                        negativePrototypes =
                            protos
                                .filter { it.prototypeType == PrototypeType.NEGATIVE }
                                .map { Prototype(it.vector, it.weight.toFloat()) },
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
        )
    }

    dependencies.provide<EventBatcher> {
        val eventRepo = dependencies.resolve<EventRepository>()
        EventBatcher(flush = { events -> eventRepo.appendBatch(events) })
    }
    dependencies.provide<EventQueue> {
        val batcher = dependencies.resolve<EventBatcher>()
        EventQueue { event ->
            batcher.enqueue(EventData(event.userId, event.itemId, event.weight, event.embeddingVersion.value))
        }
    }

    dependencies.provide<IngestionHandler> {
        IngestionHandler(
            cachingProfile = dependencies.resolve(),
            cachingEmbedding = dependencies.resolve(),
            eventQueue = dependencies.resolve(),
            activeEmbeddingVersion = dependencies.resolve<EmbeddingVersionLookup>().asEmbeddingVersionLookup(),
        )
    }

    dependencies.provide<ClusterServiceRef> {
        ClusterServiceRef(
            config.clustersPath?.let { path ->
                runCatching { ClusterService.load(path) }
                    .onFailure { log.warn("Failed to load clusters from $path — ε-exploration disabled") }
                    .getOrNull()
            },
        )
    }
    dependencies.provide<RankingHandler> {
        val ref = dependencies.resolve<ClusterServiceRef>()
        RankingHandler(
            cachingProfile = dependencies.resolve(),
            cachingEmbedding = dependencies.resolve(),
            getClusterService = { ref.get() },
            activeEmbeddingVersion = dependencies.resolve<EmbeddingVersionLookup>().asEmbeddingVersionLookup(),
        )
    }
}

/**
 * Installs Ktor middleware and JWT authentication. Resolves the
 * shared [AppConfig] / [ReadinessGate] from DI.
 */
suspend fun Application.installPlugins() {
    val config = dependencies.resolve<AppConfig>()
    val gate = dependencies.resolve<ReadinessGate>()
    configure(gate, config.jwtSecret)
}

/**
 * Mounts HTTP route handlers. Each handler is resolved from DI;
 * the routes themselves stay tiny adapter functions.
 */
suspend fun Application.installRouting() {
    configureAcquisitionRoutes(dependencies.resolve<AcquisitionHandler>())
    configureIngestionRoutes(dependencies.resolve<IngestionHandler>())
    configureRankingRoutes(dependencies.resolve<RankingHandler>())
    configureAuthRoutes(dependencies.resolve<AuthHandler>())
}

/**
 * Starts background workers, warms the profile cache, wires
 * graceful shutdown, and flips the readiness gate to ready. Runs
 * last so traffic is only accepted once every prior module has
 * resolved its share of the DI graph.
 */
suspend fun Application.installLifecycle() {
    val config = dependencies.resolve<AppConfig>()
    val gate = dependencies.resolve<ReadinessGate>()
    val acquisitionScope = dependencies.resolve<AcquisitionScope>().scope
    val cachingProfile = dependencies.resolve<CachingProfileAdapter>()
    val cachingEmbedding = dependencies.resolve<CachingEmbeddingAdapter>()
    val eventBatcher = dependencies.resolve<EventBatcher>()
    val profileRepo = dependencies.resolve<ProfileRepository>()
    val eventRepo = dependencies.resolve<EventRepository>()
    val prototypeRepo = dependencies.resolve<PrototypeRepository>()
    val itemRepo = dependencies.resolve<ItemRepository>()
    val lucene = dependencies.resolve<LuceneAdapter>()
    val objectStore = dependencies.resolve<LocalObjectStore>()
    val onnxAdapter = dependencies.resolve<OnnxInferenceAdapter>()
    val httpClient = dependencies.resolve<HttpClient>()
    val clusterRef = dependencies.resolve<ClusterServiceRef>()
    val versionLookup = dependencies.resolve<EmbeddingVersionLookup>()

    acquisitionScope.launch { EventBatcherWorker(eventBatcher).run() }
    acquisitionScope.launch {
        ProfilePersistWorker(cachingProfile, profileRepo, config.profilePersistIntervalMs).run()
    }
    acquisitionScope.launch {
        ProfileMigrationWorker(
            profileRepo = profileRepo,
            eventRepo = eventRepo,
            cachingEmbedding = cachingEmbedding,
            cachingProfile = cachingProfile,
            activeEmbeddingVersion = versionLookup.asEmbeddingVersionLookup(),
        ).run()
    }
    acquisitionScope.launch {
        ProtoSplitWorker(
            cachingProfile = cachingProfile,
            cachingEmbedding = cachingEmbedding,
            prototypeRepo = prototypeRepo,
            eventRepo = eventRepo,
        ).run()
    }
    if (config.clustersPath != null) {
        acquisitionScope.launch {
            KMeansScheduler(
                itemRepo = itemRepo,
                luceneAdapter = lucene,
                objectStore = objectStore,
                clustersKey = config.clustersPath.fileName.toString(),
                clusterServiceRef = clusterRef.asAtomicReference(),
                intervalMs = config.kMeansCheckIntervalMs,
            ).run()
        }
    }

    monitor.subscribe(ApplicationStopping) {
        runBlocking(Dispatchers.IO) {
            withTimeout(SHUTDOWN_DEADLINE_MS) {
                gate.markStopping()
                acquisitionScope.cancel()
                checkNotNull(acquisitionScope.coroutineContext[Job]).join()
                lucene.close()
                onnxAdapter.close()
                httpClient.close()
            }
        }
    }

    withContext(Dispatchers.IO) { profileRepo.loadAllUserIds() }.forEach { userId ->
        cachingProfile.getOrLoad(userId)
    }

    gate.markReady()
}

/**
 * Testable entry point — caller controls readiness state and JWT secret.
 * Pass an empty jwtSecret to skip JWT auth (only safe in test scope where
 * no authenticate("kurai") routes are installed).
 */
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

/**
 * Wrapper for the worker [CoroutineScope]. Ktor DI keys on type, so a
 * raw `CoroutineScope` would collide with any future scope provider.
 */
internal class AcquisitionScope(
    val scope: CoroutineScope,
)

/**
 * Wrapper around the content source map. A bare `Map<String, ContentSource>`
 * is hard to use as a DI key because of erased generic parameters.
 */
internal class ContentSourceRegistry(
    val byName: Map<String, ContentSource>,
)

/**
 * Mutable holder for the active [ClusterService]. The reference is swapped
 * out by [KMeansScheduler] on every rebuild; readers see the latest one.
 */
internal class ClusterServiceRef(
    initial: ClusterService?,
) {
    private val ref = AtomicReference(initial)

    fun get(): ClusterService? = ref.get()

    fun asAtomicReference(): AtomicReference<ClusterService?> = ref
}

/**
 * Wrapper exposing the active embedding version both as a raw [String]
 * (the form AcquisitionService wants) and as a typed [EmbeddingVersion]
 * (the form handlers and workers want).
 */
internal class EmbeddingVersionLookup(
    private val lookup: () -> String,
) {
    fun asStringLookup(): () -> String = lookup

    fun asEmbeddingVersionLookup(): () -> EmbeddingVersion = { EmbeddingVersion(lookup()) }
}
