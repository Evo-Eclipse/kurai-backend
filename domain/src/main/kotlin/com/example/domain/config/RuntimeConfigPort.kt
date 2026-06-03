package com.example.domain.config

data class RuntimeConfigEntry(
    val key: String,
    val valueType: String,
    val value: String,
    val updatedAt: Long,
)

interface RuntimeConfigPort {
    fun load(key: String): RuntimeConfigEntry?

    fun upsert(
        key: String,
        valueType: String,
        value: String,
        now: Long,
    )
}
