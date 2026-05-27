package com.example.domain.profile

fun List<Pair<Long, Float>>.mmr(
    embeddings: Map<Long, FloatArray>,
    lambda: Float,
    n: Int,
): List<Long> {
    if (isEmpty()) return emptyList()
    val limit = minOf(n, size)
    val selected = mutableListOf<Long>()
    val remaining = toMutableList()

    while (selected.size < limit && remaining.isNotEmpty()) {
        val best =
            remaining.maxByOrNull { (id, score) ->
                val relevance = lambda * score
                val diversity =
                    if (selected.isEmpty()) {
                        0f
                    } else {
                        val vec = embeddings[id]
                        if (vec == null) {
                            0f
                        } else {
                            selected.maxOf { sel ->
                                val selVec = embeddings[sel] ?: return@maxOf 0f
                                Scoring.cos(vec, selVec)
                            }
                        }
                    }
                relevance - (1f - lambda) * diversity
            }!!
        selected.add(best.first)
        remaining.remove(best)
    }

    return selected
}
