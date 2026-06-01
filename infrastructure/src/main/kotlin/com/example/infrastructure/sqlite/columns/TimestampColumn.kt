package com.example.infrastructure.sqlite.columns

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

/**
 * Timestamp columns for the kurai schema.
 *
 * Every timestamp is epoch **milliseconds** — matching
 * `System.currentTimeMillis()` / `Instant.toEpochMilli()` — so there is a
 * single unit across the whole schema with no per-table exceptions.
 *
 * Both factories return `Column<Long>` so existing `.clientDefault {…}` /
 * `.default(0)` chaining keeps working.
 */

fun Table.timestampMillis(name: String): Column<Long> = long(name)

fun Table.timestampMillisDefaultNow(name: String): Column<Long> =
    long(name).clientDefault {
        Instant.now().toEpochMilli()
    }
