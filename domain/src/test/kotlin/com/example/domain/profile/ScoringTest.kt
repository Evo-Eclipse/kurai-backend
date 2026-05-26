package com.example.domain.profile

import com.example.domain.model.EmbeddingVersion
import com.example.domain.model.Prototype
import com.example.domain.model.UserEvent
import com.example.domain.model.UserProfile
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

class ScoringTest {
    private val rng = Random(0xCAFE)

    private fun randomVec(dim: Int = Prototype.VECTOR_DIM): FloatArray = FloatArray(dim) { rng.nextFloat() * 2f - 1f }

    private fun normalizedVec(dim: Int = Prototype.VECTOR_DIM): FloatArray = Scoring.l2Normalize(randomVec(dim))

    private fun l2Norm(v: FloatArray): Float {
        var s = 0.0
        for (x in v) s += x * x
        return sqrt(s).toFloat()
    }

    private fun buildProfile(vararg posVecs: FloatArray): UserProfile {
        val ev = EmbeddingVersion("v1")
        return UserProfile(
            userId = 1L,
            embeddingVersion = ev,
            positivePrototypes = posVecs.map { Prototype(Scoring.l2Normalize(it), 1f) },
            negativePrototypes = emptyList(),
            sessionVector = FloatArray(Prototype.VECTOR_DIM),
            longTermVector = FloatArray(Prototype.VECTOR_DIM),
            lastAppliedEventId = 0L,
        )
    }

    private fun event(
        id: Long,
        weight: Float,
    ) = UserEvent(id, 1L, 10L, weight, EmbeddingVersion("v1"), 1000L)

    // region l2Normalize

    @Test
    fun `l2Normalize produces unit vector`() {
        repeat(500) {
            val v = randomVec()
            val n = Scoring.l2Normalize(v)
            assertTrue(abs(l2Norm(n) - 1f) < 1e-5f, "Expected norm=1, got ${l2Norm(n)}")
        }
    }

    @Test
    fun `l2Normalize is idempotent`() {
        repeat(500) {
            val v = randomVec()
            val n1 = Scoring.l2Normalize(v)
            val n2 = Scoring.l2Normalize(n1)
            for (i in n1.indices) {
                assertTrue(abs(n1[i] - n2[i]) < 1e-5f, "Not idempotent at index $i")
            }
        }
    }

    @Test
    fun `l2Normalize of zero vector returns zero vector unchanged`() {
        val zero = FloatArray(Prototype.VECTOR_DIM)
        val result = Scoring.l2Normalize(zero)
        for (x in result) assertTrue(x == 0f, "Zero vector should stay zero")
    }

    // endregion

    // region cos

    @Test
    fun `cos is symmetric`() {
        repeat(500) {
            val a = randomVec()
            val b = randomVec()
            val diff = abs(Scoring.cos(a, b) - Scoring.cos(b, a))
            assertTrue(diff < 1e-5f, "cos not symmetric: diff=$diff")
        }
    }

    @Test
    fun `cos of normalized vector with itself equals 1`() {
        repeat(500) {
            val u = normalizedVec()
            val c = Scoring.cos(u, u)
            assertTrue(abs(c - 1f) < 1e-5f, "cos(u,u) should be 1, got $c")
        }
    }

    @Test
    fun `cos of orthogonal vectors equals 0`() {
        val a = FloatArray(Prototype.VECTOR_DIM)
        val b = FloatArray(Prototype.VECTOR_DIM)
        a[0] = 1f
        b[1] = 1f
        assertTrue(abs(Scoring.cos(a, b)) < 1e-6f)
    }

    // endregion

    // region applyEma

    @Test
    fun `positive weight moves sessionVector toward item vector`() {
        val baseVec = normalizedVec()
        val profile = buildProfile(baseVec)
        val itemVec = normalizedVec()

        val updated = Scoring.applyEma(profile, event(1L, 1.0f), itemVec)

        val cosBefore = Scoring.cos(profile.sessionVector, itemVec)
        val cosAfter = Scoring.cos(updated.sessionVector, itemVec)
        assertTrue(cosAfter > cosBefore, "sessionVector should move toward itemVec on positive weight")
    }

    @Test
    fun `negative weight does not update positive prototypes`() {
        val baseVec = normalizedVec()
        val profile = buildProfile(baseVec)
        val itemVec = normalizedVec()

        val updated = Scoring.applyEma(profile, event(1L, -1.0f), itemVec)

        assertTrue(
            updated.positivePrototypes == profile.positivePrototypes,
            "Positive prototypes must not change on negative weight",
        )
    }

    @Test
    fun `zero weight returns profile unchanged except lastAppliedEventId`() {
        val profile = buildProfile(normalizedVec())
        val updated = Scoring.applyEma(profile, event(42L, 0f), normalizedVec())

        assertTrue(updated.lastAppliedEventId == 42L)
        assertTrue(updated.sessionVector.contentEquals(profile.sessionVector))
        assertTrue(updated.longTermVector.contentEquals(profile.longTermVector))
        assertTrue(updated.positivePrototypes == profile.positivePrototypes)
    }

    @Test
    fun `higher abs weight moves sessionVector further`() {
        val basis = FloatArray(Prototype.VECTOR_DIM)
        basis[0] = 1f
        val itemVec = FloatArray(Prototype.VECTOR_DIM)
        itemVec[1] = 1f
        val ev = EmbeddingVersion("v1")
        val profile =
            UserProfile(
                userId = 1L,
                embeddingVersion = ev,
                positivePrototypes = listOf(Prototype(basis.copyOf(), 1f)),
                negativePrototypes = emptyList(),
                sessionVector = basis.copyOf(),
                longTermVector = basis.copyOf(),
                lastAppliedEventId = 0L,
            )

        val strongUpdate = Scoring.applyEma(profile, event(1L, 1.0f), itemVec)
        val weakUpdate = Scoring.applyEma(profile, event(2L, 0.1f), itemVec)

        val strongCos = Scoring.cos(strongUpdate.sessionVector, itemVec)
        val weakCos = Scoring.cos(weakUpdate.sessionVector, itemVec)
        assertTrue(strongCos > weakCos, "weight=1.0 should move further than weight=0.1")
    }

    @Test
    fun `longTermVector changes more slowly than sessionVector`() {
        val basis = FloatArray(Prototype.VECTOR_DIM)
        basis[0] = 1f
        val itemVec = FloatArray(Prototype.VECTOR_DIM)
        itemVec[1] = 1f
        val ev = EmbeddingVersion("v1")
        val profile =
            UserProfile(
                userId = 1L,
                embeddingVersion = ev,
                positivePrototypes = listOf(Prototype(basis.copyOf(), 1f)),
                negativePrototypes = emptyList(),
                sessionVector = basis.copyOf(),
                longTermVector = basis.copyOf(),
                lastAppliedEventId = 0L,
            )

        val updated = Scoring.applyEma(profile, event(1L, 1.0f), itemVec)

        val sessionCosItem = Scoring.cos(updated.sessionVector, itemVec)
        val ltCosItem = Scoring.cos(updated.longTermVector, itemVec)
        assertTrue(
            sessionCosItem > ltCosItem,
            "sessionVector (alpha=0.5) should move more toward itemVec than longTermVector (alpha=0.05)",
        )
    }

    @Test
    fun `applyEma updates lastAppliedEventId`() {
        val profile = buildProfile(normalizedVec())
        val updated = Scoring.applyEma(profile, event(42L, 0.5f), normalizedVec())
        assertTrue(updated.lastAppliedEventId == 42L)
    }

    // endregion

    // region score

    @Test
    fun `score is positive when candidate aligns with positive prototype`() {
        val vec = normalizedVec()
        val profile = buildProfile(vec)
        val score = Scoring.score(profile, vec)
        assertTrue(score > 0.9f, "Score for aligned candidate should be near 1, got $score")
    }

    @Test
    fun `score with empty profile returns 0`() {
        val profile = UserProfile.coldStart(1L)
        val score = Scoring.score(profile, normalizedVec())
        assertTrue(score == 0f)
    }

    // endregion

    // region latency

    @Test
    fun `score p99 latency is below 1ms`() {
        val profile = buildProfile(normalizedVec(), normalizedVec(), normalizedVec())
        val candidate = normalizedVec()
        val warmupRuns = 200
        val sampledRuns = 1000

        repeat(warmupRuns) { Scoring.score(profile, candidate) }

        val nanos = LongArray(sampledRuns)
        for (i in nanos.indices) {
            val elapsed = measureTime { Scoring.score(profile, candidate) }
            nanos[i] = elapsed.inWholeNanoseconds
        }
        nanos.sort()
        val p99Ns = nanos[(sampledRuns * 99) / 100]
        assertTrue(p99Ns < 1_000_000L, "p99 score latency must be <1ms, was ${p99Ns}ns")
    }

    // endregion
}
