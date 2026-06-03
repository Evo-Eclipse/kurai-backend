package com.example.domain.config

data class RuntimeConfigEntry(
    val key: String,
    val valueType: String,
    val value: String,
    val updatedAt: Long,
)

interface RuntimeConfigPort {
    suspend fun load(key: String): RuntimeConfigEntry?

    suspend fun upsert(
        key: String,
        valueType: String,
        value: String,
        now: Long,
    )
}
