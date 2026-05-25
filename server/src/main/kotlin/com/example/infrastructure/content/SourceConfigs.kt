package com.example.infrastructure.content

/**
 * Per-source credentials and identification. Each source defines its own
 * config record; `AppConfig` aggregates them. Adding a source = new
 * record + matching env block, no changes to existing sources.
 */
data class E621Config(
    val baseUrl: String,
    val userAgent: String,
    val username: String,
    val accessKey: String,
)

data class UnsplashConfig(
    val baseUrl: String,
    val userAgent: String,
    val accessKey: String,
)
