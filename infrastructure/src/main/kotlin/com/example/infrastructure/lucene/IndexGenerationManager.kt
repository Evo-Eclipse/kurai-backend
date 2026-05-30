package com.example.infrastructure.lucene

import com.example.infrastructure.sqlite.IndexGenerations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the lifecycle of Lucene HNSW index generations.
 *
 * Each generation lives in its own directory under [rootDir] and is tracked
 * in `index_generations` (status: `building | active | deprecated`).
 * The currently-serving adapter is held in an [AtomicReference] so callers
 * see atomic switches without locking the read path.
 *
 * Activation flow:
 *  1. [createBuilding] — register a new row, allocate empty index dir.
 *  2. Caller writes vectors via the returned adapter.
 *  3. [activate] — flip statuses in DB transactionally, swap the in-memory
 *     reference, schedule deletion of the just-deprecated directory after
 *     [deprecatedGracePeriodSeconds].
 *
 * The grace period must exceed the longest in-flight ranking query so that
 * a request that opened the old reader before the swap can finish reading
 * before its files vanish. Default 60s ≫ Ranking p99 ≤ 120ms (NFR-1).
 */
class IndexGenerationManager(
    private val db: Database,
    private val rootDir: Path,
    private val deprecatedGracePeriodSeconds: Long,
    /**
     * Scope for the GC coroutines. Owned by the manager — callers should
     * call [close] to cancel pending GC tasks during shutdown.
     */
    private val gcScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(IndexGenerationManager::class.java)
    private val activeRef = AtomicReference<ActiveGeneration?>()

    init {
        Files.createDirectories(rootDir)
    }

    /**
     * Loads the generation marked `active` in `index_generations`, opening
     * its directory. Returns `null` if no active generation exists yet
     * (cold-start before the first acquisition).
     */
    fun openActive(): LuceneAdapter? {
        val row =
            transaction(db) {
                IndexGenerations
                    .selectAll()
                    .where { IndexGenerations.status eq "active" }
                    .singleOrNull()
            }
        if (row == null) {
            activeRef.set(null)
            return null
        }
        val id = row[IndexGenerations.id]
        val path = Path.of(row[IndexGenerations.indexPath])
        val adapter = LuceneAdapter(path)
        activeRef.set(ActiveGeneration(id, adapter))
        return adapter
    }

    /**
     * Returns the currently-active adapter loaded by [openActive] or
     * activated via [activate]. `null` until the first active generation.
     */
    fun current(): LuceneAdapter? = activeRef.get()?.adapter

    /**
     * Allocates a new generation directory and DB row in `building` status.
     * Returns a fresh adapter pointed at the new directory; vectors written
     * through it are not visible to the active reader until [activate].
     */
    fun createBuilding(embeddingVersion: String): BuildingGeneration {
        val dirName = "index_${embeddingVersion}_${System.nanoTime()}"
        val path = rootDir.resolve(dirName)
        Files.createDirectories(path)
        val id =
            transaction(db) {
                IndexGenerations.insert {
                    it[IndexGenerations.embeddingVersion] = embeddingVersion
                    it[IndexGenerations.status] = "building"
                    it[IndexGenerations.indexPath] = path.toString()
                }[IndexGenerations.id]
            }
        val adapter = LuceneAdapter(path)
        return BuildingGeneration(id, adapter)
    }

    /**
     * Atomically promotes [building] to `active` and demotes the previously-
     * active generation to `deprecated`. The DB transition and the in-memory
     * swap commit before this call returns; the deprecated directory is
     * scheduled for deletion after [deprecatedGracePeriodSeconds].
     */
    fun activate(building: BuildingGeneration) {
        building.adapter.refresh()
        val previous = activeRef.get()
        val now = Instant.now().epochSecond
        transaction(db) {
            previous?.let { prev ->
                IndexGenerations.update({ IndexGenerations.id eq prev.id }) {
                    it[status] = "deprecated"
                }
            }
            IndexGenerations.update({ IndexGenerations.id eq building.id }) {
                it[status] = "active"
                it[activatedAt] = now
            }
        }
        activeRef.set(ActiveGeneration(building.id, building.adapter))
        previous?.let { scheduleGc(it) }
    }

    /**
     * Schedules deletion of [generation]'s on-disk directory after the
     * grace period. The adapter is closed first to release file handles.
     */
    private fun scheduleGc(generation: ActiveGeneration) {
        gcScope.launch {
            delay(deprecatedGracePeriodSeconds * 1000)
            try {
                generation.adapter.close()
                deleteRecursively(generation.adapter.indexPath)
                log.info("GC'd deprecated generation id={} path={}", generation.id, generation.adapter.indexPath)
            } catch (e: IOException) {
                log.warn("Failed to GC generation id={}", generation.id, e)
            }
        }
    }

    private suspend fun deleteRecursively(path: Path) =
        withContext(Dispatchers.IO) {
            if (!Files.exists(path)) return@withContext
            Files
                .walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(Files::delete)
        }

    override fun close() {
        gcScope.cancel()
        activeRef.getAndSet(null)?.adapter?.close()
    }

    /** A generation in `building` status, ready for vector writes. */
    data class BuildingGeneration(
        val id: Long,
        val adapter: LuceneAdapter,
    )

    private data class ActiveGeneration(
        val id: Long,
        val adapter: LuceneAdapter,
    )
}
