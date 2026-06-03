package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.acquisition.AcquisitionHandler
import com.example.acquisition.configureAcquisitionRoutes
import com.example.application.acquisition.AcquisitionService
import com.example.application.auth.AuthService
import com.example.application.auth.LoggingMagicLinkSender
import com.example.application.auth.MagicLinkSender
import com.example.application.auth.SessionGcWorker
import com.example.application.catalog.KMeansScheduler
import com.example.application.config.ConfigKey
import com.example.application.config.RuntimeConfig
import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.application.profile.CachingProfileAdapter
import com.example.application.profile.EventBatcher
import com.example.application.profile.EventBatcherWorker
import com.example.application.profile.ProfileMigrationWorker
import com.example.application.profile.ProfilePersistWorker
import com.example.application.profile.ProtoSplitWorker
import com.example.auth.AuthHandler
import com.example.auth.ChallengeIpRateLimiter
import com.example.auth.FixedWindowRateLimiter
import com.example.auth.SessionAuthenticator
import com.example.auth.configureAuthRoutes
import com.example.domain.auth.AuthIdentityPort
import com.example.domain.auth.AuthSessionPort
import com.example.domain.auth.LoginChallengePort
import com.example.domain.auth.UserPort
import com.example.domain.catalog.AcquisitionJobPort
import com.example.domain.catalog.CatalogItemPort
import com.example.domain.catalog.ClusterGenerationPort
import com.example.domain.catalog.ItemVectorIndexPort
import com.example.domain.catalog.SystemStatePort
import com.example.domain.cluster.ClusterService
import com.example.domain.config.RuntimeConfigPort
import com.example.domain.content.ContentSource
import com.example.domain.embedding.EmbedLookupPort
import com.example.domain.events.EventQueue
import com.example.domain.inference.InferenceService
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserEvent
import com.example.domain.model.UserProfile
import com.example.domain.profile.PendingUserEvent
import com.example.domain.profile.ProfilePort
import com.example.domain.profile.PrototypePort
import com.example.domain.profile.PrototypeType
import com.example.domain.profile.UserEventPort
import com.example.domain.storage.GetResult
import com.example.domain.storage.ObjectStorePort
import com.example.health.configureHealthRoutes
import com.example.infrastructure.content.CdnContentSource
import com.example.infrastructure.content.E621ContentSource
import com.example.infrastructure.content.ImagePreprocessor
import com.example.infrastructure.content.UnsplashContentSource
import com.example.infrastructure.lucene.LuceneAdapter
import com.example.infrastructure.onnx.OnnxInferenceAdapter
import com.example.infrastructure.sqlite.AcquisitionJobRepository
import com.example.infrastructure.sqlite.AuthIdentityRepository
import com.example.infrastructure.sqlite.AuthSessionRepository
import com.example.infrastructure.sqlite.ClusterGenerationRepository
import com.example.infrastructure.sqlite.EventRepository
import com.example.infrastructure.sqlite.EventWeightRepository
import com.example.infrastructure.sqlite.ItemRepository
import com.example.infrastructure.sqlite.LoginChallengeRepository
import com.example.infrastructure.sqlite.ProfileRepository
import com.example.infrastructure.sqlite.PrototypeRepository
import com.example.infrastructure.sqlite.RuntimeConfigRepository
import com.example.infrastructure.sqlite.SystemStateRepository
import com.example.infrastructure.sqlite.UserRepository
import com.example.infrastructure.sqlite.initSchema
import com.example.infrastructure.sqlite.sqliteDispatcher
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
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
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
    dependencies.provide<AcquisitionScope> {
        AcquisitionScope(
            CoroutineScope(
                SupervisorJob() + Dispatchers.IO.limitedParallelism(8),
            ),
        )
    }

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
                inferenceParallelism = config.onnxInferenceParallelism,
            )
        }
    }
    dependencies.provide<LuceneAdapter> {
        withContext(Dispatchers.IO) { LuceneAdapter(config.luceneDir) }
    }
    dependencies.provide<LocalObjectStore> { LocalObjectStore(config.objectStoreDir) }
    dependencies.provide<HttpClient> { HttpClient(CIO) }
    dependencies.provide<ImagePreprocessor> { ImagePreprocessor() }

    dependencies.provide<AcquisitionJobPort> { AcquisitionJobRepository(dependencies.resolve()) }
    dependencies.provide<CatalogItemPort> { ItemRepository(dependencies.resolve()) }
    dependencies.provide<ItemVectorIndexPort> { dependencies.resolve<LuceneAdapter>() }
    dependencies.provide<ObjectStorePort> { dependencies.resolve<LocalObjectStore>() }
    dependencies.provide<SystemStatePort> {
        SystemStateRepository(dependencies.resolve()).also { it.seedIfMissing(System.currentTimeMillis()) }
    }
    dependencies.provide<ClusterGenerationPort> { ClusterGenerationRepository(dependencies.resolve()) }
    dependencies.provide<ProfilePort> { ProfileRepository(dependencies.resolve()) }
    dependencies.provide<UserEventPort> { EventRepository(dependencies.resolve()) }
    dependencies.provide<EventWeightRepository> { EventWeightRepository(dependencies.resolve()) }
    dependencies.provide<PrototypePort> { PrototypeRepository(dependencies.resolve()) }
    dependencies.provide<UserPort> { UserRepository(dependencies.resolve()) }
    dependencies.provide<AuthIdentityPort> { AuthIdentityRepository(dependencies.resolve()) }
    dependencies.provide<AuthSessionPort> { AuthSessionRepository(dependencies.resolve()) }
    dependencies.provide<LoginChallengePort> { LoginChallengeRepository(dependencies.resolve()) }
    dependencies.provide<RuntimeConfigPort> { RuntimeConfigRepository(dependencies.resolve()) }

    dependencies.provide<RuntimeConfig> {
        val runtime = RuntimeConfig(dependencies.resolve<RuntimeConfigPort>())
        // Seed bootstrap defaults so the first AuthService TTL lookup
        // does not blow up on a fresh database. Operator-set values
        // (if a row already exists) are preserved across restarts.
        runtime.seedIfMissing(ConfigKey.AuthSessionTtlMs, config.authSessionTtlMs.toString())
        runtime.seedIfMissing(ConfigKey.AuthChallengeTtlMs, config.authChallengeTtlMs.toString())
        runtime.seedIfMissing(ConfigKey.AuthJwtTtlMs, config.authJwtTtlMs.toString())
        runtime.seedIfMissing(ConfigKey.ProfilePersistIntervalMs, config.profilePersistIntervalMs.toString())
        runtime.seedIfMissing(ConfigKey.KMeansCheckIntervalMs, config.kMeansCheckIntervalMs.toString())
        runtime.seedIfMissing(ConfigKey.ProtoSplitIntervalMs, 3_600_000L.toString())
        runtime.seedIfMissing(ConfigKey.SessionGcIntervalMs, config.sessionGcIntervalMs.toString())
        runtime.seedIfMissing(ConfigKey.SessionGcRetentionMs, config.sessionGcRetentionMs.toString())
        runtime.seedIfMissing(ConfigKey.KeyIssueRateLimitMax, config.keyIssueRateLimitMax.toString())
        runtime.seedIfMissing(ConfigKey.KeyIssueRateLimitWindowMs, config.keyIssueRateLimitWindowMs.toString())
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
            sessionCheckDispatcher = sqliteDispatcher,
        )
    }
    dependencies.provide<SessionAuthenticator> {
        SessionAuthenticator(authService = dependencies.resolve())
    }
    dependencies.provide<FixedWindowRateLimiter> {
        val runtime = dependencies.resolve<RuntimeConfig>()
        FixedWindowRateLimiter(
            maxPerWindow = { runtime.get(ConfigKey.KeyIssueRateLimitMax) },
            windowMs = { runtime.get(ConfigKey.KeyIssueRateLimitWindowMs) },
        )
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
        val runtime = dependencies.resolve<RuntimeConfig>()
        AuthHandler(
            authService = dependencies.resolve(),
            sessionAuth = dependencies.resolve(),
            issueRateLimiter = dependencies.resolve(),
            challengeIpRateLimiter = dependencies.resolve(),
            jwtSecret = config.jwtSecret,
            jwtTtlMs = { runtime.get(ConfigKey.AuthJwtTtlMs) },
        )
    }

    dependencies.provide<EmbeddingVersionLookup> {
        EmbeddingVersionLookup(systemState = dependencies.resolve())
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
            vectorIndex = dependencies.resolve(),
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
        val profileRepo = dependencies.resolve<ProfilePort>()
        val eventRepo = dependencies.resolve<UserEventPort>()
        val prototypeRepo = dependencies.resolve<PrototypePort>()
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
        val eventRepo = dependencies.resolve<UserEventPort>()
        EventBatcher(flush = { events -> eventRepo.appendBatch(events) })
    }
    dependencies.provide<EventQueue> {
        val batcher = dependencies.resolve<EventBatcher>()
        EventQueue { event ->
            batcher.enqueue(PendingUserEvent(event.userId, event.itemId, event.sourceTag, event.embeddingVersion.value))
        }
    }

    dependencies.provide<IngestionHandler> {
        val eventWeights = dependencies.resolve<EventWeightRepository>()
        IngestionHandler(
            cachingProfile = dependencies.resolve(),
            cachingEmbedding = dependencies.resolve(),
            eventQueue = dependencies.resolve(),
            activeEmbeddingVersion = dependencies.resolve<EmbeddingVersionLookup>().asEmbeddingVersionLookup(),
            resolveWeight = { tag ->
                (eventWeights.resolve(tag) ?: EventWeightRepository.DEFAULT_EVENT_WEIGHT).toFloat()
            },
        )
    }

    dependencies.provide<ClusterServiceRef> {
        // Load whichever cluster generation system_state currently points at.
        val systemState = dependencies.resolve<SystemStatePort>()
        val clusterGenerations = dependencies.resolve<ClusterGenerationPort>()
        val store = dependencies.resolve<ObjectStorePort>()
        val active = systemState.read().activeClusterId?.let { clusterGenerations.findById(it) }
        val clusters =
            active?.let { gen ->
                when (val result = store.get(gen.centroidsPath)) {
                    is GetResult.Found -> ClusterService.fromBytes(result.bytes)
                    GetResult.NotFound -> {
                        log.warn(
                            "Active cluster ${gen.id} centroids missing at ${gen.centroidsPath} — ε-exploration disabled",
                        )
                        null
                    }
                }
            }
        ClusterServiceRef(clusters)
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
    val sessionAuth = dependencies.resolve<SessionAuthenticator>()
    configure(gate, config.jwtSecret) { sessionId -> sessionAuth.isActive(sessionId) }
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
    val gate = dependencies.resolve<ReadinessGate>()
    val acquisitionScope = dependencies.resolve<AcquisitionScope>().scope
    val cachingProfile = dependencies.resolve<CachingProfileAdapter>()
    val cachingEmbedding = dependencies.resolve<CachingEmbeddingAdapter>()
    val eventBatcher = dependencies.resolve<EventBatcher>()
    val profileRepo = dependencies.resolve<ProfilePort>()
    val eventRepo = dependencies.resolve<UserEventPort>()
    val prototypeRepo = dependencies.resolve<PrototypePort>()
    val itemRepo = dependencies.resolve<CatalogItemPort>()
    val lucene = dependencies.resolve<LuceneAdapter>()
    val objectStore = dependencies.resolve<ObjectStorePort>()
    val onnxAdapter = dependencies.resolve<OnnxInferenceAdapter>()
    val httpClient = dependencies.resolve<HttpClient>()
    val clusterRef = dependencies.resolve<ClusterServiceRef>()
    val versionLookup = dependencies.resolve<EmbeddingVersionLookup>()
    val authSessions = dependencies.resolve<AuthSessionPort>()
    val runtime = dependencies.resolve<RuntimeConfig>()

    acquisitionScope.launch { EventBatcherWorker(eventBatcher).run() }
    acquisitionScope.launch {
        SessionGcWorker(
            sessions = authSessions,
            intervalMs = { runtime.get(ConfigKey.SessionGcIntervalMs) },
            retentionMs = { runtime.get(ConfigKey.SessionGcRetentionMs) },
        ).run()
    }
    acquisitionScope.launch {
        ProfilePersistWorker(
            cachingProfile,
            profileRepo,
            intervalMs = { runtime.get(ConfigKey.ProfilePersistIntervalMs) },
        ).run()
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
            intervalMs = { runtime.get(ConfigKey.ProtoSplitIntervalMs) },
        ).run()
    }
    acquisitionScope.launch {
        KMeansScheduler(
            itemRepo = itemRepo,
            vectorIndex = lucene,
            objectStore = objectStore,
            clusterGenerations = dependencies.resolve(),
            systemState = dependencies.resolve(),
            clusterServiceRef = clusterRef.asAtomicReference(),
            intervalMs = { runtime.get(ConfigKey.KMeansCheckIntervalMs) },
        ).run()
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

    profileRepo.loadAllUserIds().forEach { userId ->
        cachingProfile.getOrLoad(userId)
    }

    gate.markReady()
}

/**
 * Testable entry point — caller controls readiness state and JWT secret.
 * Pass an empty jwtSecret to skip JWT auth (only safe in test scope where
 * no authenticate("kurai") routes are installed).
 *
 * [isSessionActive] is consulted for every token that carries a `sid`
 * claim, so a revoked or expired session is rejected on *all*
 * `authenticate("kurai")` routes — not only the ones that re-check the
 * session in their handler. The default accepts every session, which
 * suits tests that mint bare `sub`-only tokens; production wires the
 * real check in [installPlugins].
 */
fun Application.configure(
    readinessGate: ReadinessGate,
    jwtSecret: String = "",
    isSessionActive: (sessionId: String) -> Boolean = { true },
) {
    install(ContentNegotiation) { json() }
    install(XForwardedHeaders)
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
                val sub = credential.payload.getClaim("sub").asString()
                if (sub.isNullOrBlank()) return@validate null
                // Tokens minted by AuthHandler always carry `sid`; reject
                // the principal if that session has been revoked or expired.
                val sid = credential.payload.getClaim("sid").asString()
                if (!sid.isNullOrBlank() && !isSessionActive(sid)) return@validate null
                JWTPrincipal(credential.payload)
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
 * (the form handlers and workers want). Reads [SystemStatePort] via suspend
 * lookups so callers never bridge with `runBlocking`.
 */
internal class EmbeddingVersionLookup(
    private val systemState: SystemStatePort,
) {
    suspend fun current(): String = systemState.read().defaultEmbeddingVersion ?: "unknown"

    fun asStringLookup(): suspend () -> String = { current() }

    fun asEmbeddingVersionLookup(): suspend () -> EmbeddingVersion = { EmbeddingVersion(current()) }
}
