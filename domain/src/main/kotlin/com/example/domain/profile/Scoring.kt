package com.example.domain.profile

import com.example.domain.model.Prototype
import com.example.domain.model.UserEvent
import com.example.domain.model.UserProfile
import kotlin.math.abs
import kotlin.math.sqrt

object Scoring {
    const val ALPHA_SESSION: Float = 0.5f
    const val ALPHA_LONG_TERM: Float = 0.05f
    const val GAMMA_NEGATIVE: Float = 0.4f

    fun l2Normalize(v: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in v) sumSq += x * x
        val norm = sqrt(sumSq).toFloat()
        if (norm < 1e-10f) return v.copyOf()
        return FloatArray(v.size) { v[it] / norm }
    }

    fun cos(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        require(a.size == b.size) { "Vectors must have the same dimension: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom < 1e-10) 0f else (dot / denom).toFloat()
    }

    fun applyEma(
        profile: UserProfile,
        event: UserEvent,
        itemVector: FloatArray,
    ): UserProfile {
        val w = event.weight
        if (w == 0f) return profile.copy(lastAppliedEventId = event.id)
        val absW = abs(w)
        val isNegative = w < 0f
        // Both vectors track attention regardless of valence (absW); they are reserved
        // for future attention-aware re-ranking and are not used by score().
        val newSession = l2Normalize(blend(profile.sessionVector, itemVector, ALPHA_SESSION * absW))
        val newLongTerm = l2Normalize(blend(profile.longTermVector, itemVector, ALPHA_LONG_TERM * absW))
        val updatedPositives =
            if (!isNegative) {
                updateClosestPrototype(profile.positivePrototypes, itemVector)
            } else {
                profile.positivePrototypes
            }
        val updatedNegatives =
            if (isNegative) {
                updateClosestPrototype(profile.negativePrototypes, itemVector)
            } else {
                profile.negativePrototypes
            }
        return profile.copy(
            sessionVector = newSession,
            longTermVector = newLongTerm,
            positivePrototypes = updatedPositives,
            negativePrototypes = updatedNegatives,
            lastAppliedEventId = event.id,
        )
    }

    fun score(
        profile: UserProfile,
        candidateVector: FloatArray,
    ): Float {
        val posScore = profile.positivePrototypes.maxOfOrNull { cos(it.vector, candidateVector) } ?: 0f
        val negScore = profile.negativePrototypes.maxOfOrNull { cos(it.vector, candidateVector) } ?: 0f
        return posScore - GAMMA_NEGATIVE * negScore
    }

    private fun blend(
        base: FloatArray,
        update: FloatArray,
        alpha: Float,
    ): FloatArray = FloatArray(base.size) { i -> (1f - alpha) * base[i] + alpha * update[i] }

    private fun updateClosestPrototype(
        prototypes: List<Prototype>,
        itemVector: FloatArray,
    ): List<Prototype> {
        if (prototypes.isEmpty()) return prototypes
        val idx = prototypes.indices.maxBy { cos(prototypes[it].vector, itemVector) }
        val proto = prototypes[idx]
        val blended = l2Normalize(blend(proto.vector, itemVector, ALPHA_LONG_TERM))
        return prototypes
            .toMutableList()
            .also { it[idx] = Prototype(blended, proto.weight) }
    }
}
