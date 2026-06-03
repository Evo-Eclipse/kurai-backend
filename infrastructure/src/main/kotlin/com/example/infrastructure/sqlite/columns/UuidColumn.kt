package com.example.infrastructure.sqlite.columns

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/**
 * UUID stored as TEXT (36-char canonical form). SQLite has no native
 * UUID type and storing as TEXT keeps generated SQL portable across
 * SQLite/H2 with no dialect-specific casts. Callers serialise with
 * `java.util.UUID.toString()` or `kotlin.uuid.Uuid.toString()`.
 */
fun Table.uuidText(name: String): Column<String> = varchar(name, length = 36)
