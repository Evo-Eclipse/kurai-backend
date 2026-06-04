package com.example.application.profile

import com.example.application.embedding.CachingEmbeddingAdapter
import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserProfile
import com.example.domain.profile.PendingUserEvent
import com.example.domain.profile.Scoring
import com.example.infrastructure.sqlite.EventRepository
import com.example.infrastructure.sqlite.EventWeightRepository
import com.example.infrastructure.sqlite.ProfileRepository
import com.example.infrastructure.sqlite.PrototypeRepository
import com.example.infrastructure.sqlite.initSchema
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtoSplitWorkerTest {
    private lateinit var db: Database
    private lateinit var profileRepo: ProfileRepository
    private lateinit var eventRepo: EventRepository
    private lateinit var prototypeRepo: PrototypeRepository

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        profileRepo = ProfileRepository(db)
        eventRepo = EventRepository(db)
        prototypeRepo = PrototypeRepository(db)
        // Resolve "like" to a positive weight so loadPositiveSince returns the events.
        runBlocking { EventWeightRepository(db).upsert("like", 1.0, now = 0L) }
    }

    // Builds a normalized vector dominated by dimension `axis` with slight variation from `offset`.
    private fun axisVec(
        axis: Int,
        offset: Float = 0f,
    ): FloatArray {
        val v = FloatArray(Prototype.VECTOR_DIM)
        v[axis] = 1f + offset
        return Scoring.l2Normalize(v)
    }

    private fun makeWorker(vectors: Map<Long, FloatArray>): ProtoSplitWorker {
        val cachingProfile =
            CachingProfileAdapter(
                loadProfile = { null },
                loadEvents = { _, _ -> emptyList() },
            )
        val cachingEmbedding =
            CachingEmbeddingAdapter(lookupFromStore = { ids ->
                ids.mapNotNull { id -> vectors[id]?.let { id to it } }.toMap()
            })
        return ProtoSplitWorker(
            cachingProfile = cachingProfile,
            cachingEmbedding = cachingEmbedding,
            prototypeRepo = prototypeRepo,
            eventRepo = eventRepo,
            intervalMs = { 1000L },
        )
    }

    // Loads cached userId into a worker with the given profile and vectors.
    private suspend fun setupAndSweep(
        userId: Long,
        positiveItemIds: List<Long>,
        vectors: Map<Long, FloatArray>,
        initialPrototypes: List<Prototype> = emptyList(),
    ) {
        profileRepo.upsert(userId = userId, embeddingVersion = "v1", lastAppliedEventId = 0L)
        val events =
            positiveItemIds.map { itemId ->
                PendingUserEvent(userId = userId, itemId = itemId, sourceTag = "like", embeddingVersion = "v1")
            }
        eventRepo.appendBatch(events)

        val cachingProfile =
            CachingProfileAdapter(
                loadProfile = { uid ->
                    if (uid == userId) {
                        UserProfile(
                            userId = uid,
                            embeddingVersion = EmbeddingVersion("v1"),
                            positivePrototypes = initialPrototypes,
                            negativePrototypes = emptyList(),
                            sessionVector = FloatArray(Prototype.VECTOR_DIM),
                            longTermVector = FloatArray(Prototype.VECTOR_DIM),
                            lastAppliedEventId = 0L,
                        )
                    } else {
                        null
                    }
                },
                loadEvents = { _, _ -> emptyList() },
            )
        // Prime cache
        cachingProfile.getOrLoad(userId)

        val cachingEmbedding =
            CachingEmbeddingAdapter(lookupFromStore = { ids ->
                ids.mapNotNull { id -> vectors[id]?.let { id to it } }.toMap()
            })
        val worker =
            ProtoSplitWorker(
                cachingProfile = cachingProfile,
                cachingEmbedding = cachingEmbedding,
                prototypeRepo = prototypeRepo,
                eventRepo = eventRepo,
                intervalMs = { 1000L },
            )
        worker.sweep()
    }

    @Test
    fun `multimodal profile gets split into multiple prototypes`() =
        runTest {
            val userId = 1L
            // 3 clusters of 10 events each, pointing in orthogonal axis directions
            val itemIds = (1L..30L).toList()
            val vectors =
                buildMap {
                    for (i in 1..10) put(i.toLong(), axisVec(0, i * 0.001f))
                    for (i in 11..20) put(i.toLong(), axisVec(1, (i - 10) * 0.001f))
                    for (i in 21..30) put(i.toLong(), axisVec(2, (i - 20) * 0.001f))
                }

            setupAndSweep(userId, itemIds, vectors)

            val persisted = prototypeRepo.load(userId)
            assertTrue(persisted.size in 2..ProtoSplitWorker.MAX_POS, "expected 2..5 prototypes, got ${persisted.size}")
            assertTrue(persisted.all { it.prototypeType == "positive" })
        }

    @Test
    fun `unimodal profile silhouette too low does not get split`() =
        runTest {
            val userId = 2L
            // 30 events all pointing in the same direction — silhouette will be <0.25
            val itemIds = (1L..30L).toList()
            val vectors = itemIds.associateWith { axisVec(0, it * 0.00001f) }

            setupAndSweep(userId, itemIds, vectors)

            val persisted = prototypeRepo.load(userId)
            assertEquals(0, persisted.size, "unimodal profile should not be split")
        }

    @Test
    fun `profile already at MAX_POS prototypes is not split again`() =
        runTest {
            val userId = 3L
            val itemIds = (1L..30L).toList()
            val vectors = itemIds.associateWith { axisVec((it % 3).toInt(), it * 0.001f) }

            // Pre-fill with MAX_POS prototypes
            val existingPrototypes =
                (0 until ProtoSplitWorker.MAX_POS).map { i ->
                    Prototype(vector = axisVec(i % Prototype.VECTOR_DIM), weight = 1.0f)
                }

            setupAndSweep(userId, itemIds, vectors, initialPrototypes = existingPrototypes)

            // PrototypeRepo should remain empty (replaceAll was never called)
            val persisted = prototypeRepo.load(userId)
            assertEquals(0, persisted.size, "profile at MAX_POS should not be split")
        }

    @Test
    fun `prototypes are persisted after split`() =
        runTest {
            val userId = 4L
            val itemIds = (1L..30L).toList()
            val vectors =
                buildMap {
                    for (i in 1..15) put(i.toLong(), axisVec(0, i * 0.001f))
                    for (i in 16..30) put(i.toLong(), axisVec(1, (i - 15) * 0.001f))
                }

            setupAndSweep(userId, itemIds, vectors)

            val persisted = prototypeRepo.load(userId)
            assertTrue(persisted.isNotEmpty(), "expected persisted prototypes after split")
            assertTrue(persisted.all { it.embeddingVersion == "v1" })
        }
}
