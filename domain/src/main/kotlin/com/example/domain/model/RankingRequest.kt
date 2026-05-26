package com.example.domain.model

data class RankingRequest(
    val userId: Long,
    val candidateIds: List<Long>,
)
