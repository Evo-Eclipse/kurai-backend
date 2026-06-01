package com.example.infrastructure.lucene

import com.example.infrastructure.sqlite.IndexGenerations
import com.example.infrastructure.sqlite.SystemStateRepository
import com.example.infrastructure.sqlite.initSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexGenerationManagerTest {
    private lateinit var rootDir: Path
    private lateinit var db: Database
    private lateinit var systemState: SystemStateRepository
    private lateinit var gcScope: CoroutineScope

    @BeforeTest
    fun setUp() {
        rootDir = createTempDirectory("kurai-genmgr-test-")
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        systemState = SystemStateRepository(db).also { it.seedIfMissing(0L) }
        gcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterTest
    fun tearDown() {
        gcScope.cancel()
        rootDir
            .toFile()
            .walkBottomUp()
            .forEach { it.delete() }
    }

    @Test
    fun `openActive returns null when no generation is active`() {
        val mgr = newManager(graceSeconds = 60)
        assertNull(mgr.openActive())
        mgr.close()
    }

    @Test
    fun `activate flips statuses and exposes new adapter via current`() {
        val mgr = newManager(graceSeconds = 60)
        val first = mgr.createBuilding("v1")
        first.adapter.write(1L, randomNormalized(1))
        mgr.activate(first)

        val second = mgr.createBuilding("v2")
        second.adapter.write(2L, randomNormalized(2))
        mgr.activate(second)

        val byId = transaction(db) { IndexGenerations.selectAll().toList() }.associateBy { it[IndexGenerations.id] }
        assertEquals("deprecated", byId[first.id]!![IndexGenerations.status])
        assertEquals("active", byId[second.id]!![IndexGenerations.status])

        val current = mgr.current()
        assertNotNull(current)
        assertEquals(listOf(2L), current.search(randomNormalized(2), k = 1))

        mgr.close()
    }

    @Test
    fun `concurrent searches during activate see consistent view, no crash`() =
        runBlocking {
            val mgr = newManager(graceSeconds = 60)
            val v1 = mgr.createBuilding("v1")
            v1.adapter.write(1L, randomNormalized(1))
            mgr.activate(v1)

            val v2 = mgr.createBuilding("v2")
            v2.adapter.write(2L, randomNormalized(2))

            val readers =
                (1..10).map {
                    async {
                        var observed = 0
                        repeat(50) {
                            val cur = mgr.current() ?: return@async observed
                            val r =
                                cur.search(randomNormalized(1), k = 1) +
                                    cur.search(randomNormalized(2), k = 1)
                            if (r.isNotEmpty()) observed++
                            delay(1)
                        }
                        observed
                    }
                }

            delay(5)
            mgr.activate(v2)
            val observations = readers.awaitAll()
            assertTrue(observations.all { it > 0 }, "All readers must observe at least one healthy result")

            mgr.close()
        }

    @Test
    fun `deprecated generation directory is deleted after grace period`() =
        runBlocking {
            val mgr = newManager(graceSeconds = 1)
            val v1 = mgr.createBuilding("v1")
            v1.adapter.write(1L, randomNormalized(1))
            mgr.activate(v1)
            val v1Path = v1.adapter.indexPath

            val v2 = mgr.createBuilding("v2")
            v2.adapter.write(2L, randomNormalized(2))
            mgr.activate(v2)
            assertTrue(Files.exists(v1Path), "v1 dir should still exist within grace period")

            delay(2_000)
            assertTrue(!Files.exists(v1Path), "v1 dir should be deleted after grace period")

            mgr.close()
        }

    @Test
    fun `openActive picks up the active generation written to DB`() {
        val mgr1 = newManager(graceSeconds = 60)
        val v1 = mgr1.createBuilding("v1")
        v1.adapter.write(1L, randomNormalized(1))
        mgr1.activate(v1)
        mgr1.close()
        gcScope.cancel()
        gcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val mgr2 = newManager(graceSeconds = 60)
        val reopened = mgr2.openActive()
        assertNotNull(reopened)
        assertEquals(listOf(1L), reopened.search(randomNormalized(1), k = 1))

        mgr2.close()
    }

    private fun newManager(graceSeconds: Long): IndexGenerationManager =
        IndexGenerationManager(
            db = db,
            systemState = systemState,
            rootDir = rootDir,
            deprecatedGracePeriodSeconds = graceSeconds,
            gcScope = gcScope,
        )

    private fun randomNormalized(seed: Int): FloatArray {
        val rng = Random(seed)
        val v = FloatArray(LuceneAdapter.VECTOR_DIM) { rng.nextFloat() * 2f - 1f }
        var sumSq = 0.0
        for (x in v) sumSq += x * x
        val norm = sqrt(sumSq).toFloat()
        for (i in v.indices) v[i] = v[i] / norm
        return v
    }
}
