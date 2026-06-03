package com.example.infrastructure.sqlite
import com.example.domain.profile.PendingUserEvent
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClusterGenerationRepositoryTest {
    private lateinit var db: Database
    private lateinit var repo: ClusterGenerationRepository

    @BeforeTest
    fun setUp() {
        db =
            Database.connect(
                "jdbc:h2:mem:${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            )
        initSchema(db)
        repo = ClusterGenerationRepository(db)
    }

    @Test
    fun `createBuilding then findById round-trips and starts as building`() {
        val id =
            repo.createBuilding(
                embeddingVersion = "v1",
                clusterCount = 23,
                catalogSizeAtBuild = 10_000,
                centroidsPath = "clusters/v1.bin",
            )

        val row = assertNotNull(repo.findById(id))
        assertEquals("v1", row.embeddingVersion)
        assertEquals(GenerationStatus.BUILDING, row.status)
        assertEquals(23, row.clusterCount)
        assertEquals(10_000, row.catalogSizeAtBuild)
        assertEquals("clusters/v1.bin", row.centroidsPath)
        assertNull(row.activatedAt, "a building generation is not activated yet")
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(repo.findById(404L))
    }
}
