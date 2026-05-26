package com.example.domain.model

import kotlin.math.abs
import kotlin.math.sqrt

data class Prototype(
    val vector: FloatArray,
    val weight: Float,
) {
    init {
        require(vector.size == VECTOR_DIM) {
            "Prototype vector must be $VECTOR_DIM-dimensional, got ${vector.size}"
        }
        assertNormalized(vector)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Prototype) return false
        return vector.contentEquals(other.vector) && weight == other.weight
    }

    override fun hashCode(): Int {
        var result = vector.contentHashCode()
        result = 31 * result + weight.hashCode()
        return result
    }

    companion object {
        const val VECTOR_DIM: Int = 768
        const val L2_TOLERANCE: Float = 1e-3f

        private fun assertNormalized(v: FloatArray) {
            assert(isNormalized(v)) {
                "Prototype vector must be L2-normalized (‖v‖≈1), got ‖v‖=${l2Norm(v)}"
            }
        }

        private fun isNormalized(v: FloatArray): Boolean = abs(l2Norm(v) - 1f) < L2_TOLERANCE

        private fun l2Norm(v: FloatArray): Float {
            var sum = 0.0
            for (x in v) sum += x * x
            return sqrt(sum).toFloat()
        }
    }
}
