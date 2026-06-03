package com.example.infrastructure.sqlite
import com.example.domain.auth.EmailKind
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthSessionRepositoryTest {
    private fun freshDb(): Database =
        Database
            .connect(
                "jdbc:h2:mem:auth-sess${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
            ).also { initSchema(it) }

    @Test
    fun `rotateIfActive allows only one successor for concurrent callers`() =
        runBlocking {
            val db = freshDb()
            val repo = AuthSessionRepository(db)
            val users = UserRepository(db)
            val now = 1_700_000_000_000L
            val userId = users.insertVerifiedEmail("rotate-test@example.com", EmailKind.REAL, now)
            val sessionId = UUID.randomUUID().toString()
            repo.insert(
                id = sessionId,
                userId = userId,
                deviceLabel = "phone",
                refreshHash = "hash-parent",
                expiresAt = now + 60_000,
                now = now,
            )

            val successorA = UUID.randomUUID().toString()
            val successorB = UUID.randomUUID().toString()
            val wonA =
                repo.rotateIfActive(
                    sessionId = sessionId,
                    successorId = successorA,
                    userId = userId,
                    deviceLabel = "phone",
                    refreshHash = "hash-a",
                    expiresAt = now + 120_000,
                    now = now + 1,
                )
            val wonB =
                repo.rotateIfActive(
                    sessionId = sessionId,
                    successorId = successorB,
                    userId = userId,
                    deviceLabel = "phone",
                    refreshHash = "hash-b",
                    expiresAt = now + 120_000,
                    now = now + 2,
                )

            assertTrue(wonA xor wonB)
            val parent = checkNotNull(repo.findById(sessionId))
            assertNotNull(parent.replacedBy)
            assertEquals(if (wonA) successorA else successorB, parent.replacedBy)
            assertNull(repo.findById(if (wonA) successorB else successorA))
        }
}
