package com.example.infrastructure.sqlite.columns

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

/**
 * Timestamp column policies for the kurai schema.
 *
 *  - [timestampMillis] — new tables (auth_*, future runtime_config, …).
 *    Epoch millis match the JVM `System.currentTimeMillis()` /
 *    `Instant.toEpochMilli()` surface and survive sub-second comparisons.
 *  - [timestampSeconds] — already-shipped tables (items.indexed_at,
 *    user_events.ts, acquisition_jobs.created_at). Stay on seconds to
 *    avoid a breaking data migration; an explicit factory keeps the
 *    decision visible at the call site instead of hidden behind
 *    `Table.long(name)` ambiguity.
 *
 * Both factories return `Column<Long>` so existing `.clientDefault {…}` /
 * `.default(0)` chaining keeps working.
 */

fun Table.timestampMillis(name: String): Column<Long> = long(name)

fun Table.timestampMillisDefaultNow(name: String): Column<Long> =
    long(name).clientDefault { Instant.now().toEpochMilli() }

fun Table.timestampSeconds(name: String): Column<Long> = long(name)

fun Table.timestampSecondsDefaultNow(name: String): Column<Long> =
    long(name).clientDefault { Instant.now().epochSecond }
