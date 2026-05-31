package com.example.application.config

import com.example.infrastructure.sqlite.RuntimeConfigRepository
import com.example.infrastructure.sqlite.initSchema
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeConfigContractTest {
    private fun fresh(): Pair<RuntimeConfigRepository, RuntimeConfig> {
        val db = Database.connect("jdbc:h2:mem:cfg${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        initSchema(db)
        val repo = RuntimeConfigRepository(db)
        return repo to RuntimeConfig(repo)
    }

    @Test
    fun `get returns the seeded Long value`() {
        val (_, runtime) = fresh()
        assertTrue(runtime.seedIfMissing(ConfigKey.AuthSessionTtlMs, "604800000"))
        assertEquals(604_800_000L, runtime.get(ConfigKey.AuthSessionTtlMs))
    }

    @Test
    fun `seedIfMissing is idempotent`() {
        val (_, runtime) = fresh()
        assertTrue(runtime.seedIfMissing(ConfigKey.AuthSessionTtlMs, "1000"))
        assertFalse(runtime.seedIfMissing(ConfigKey.AuthSessionTtlMs, "9999"))
        assertEquals(1000L, runtime.get(ConfigKey.AuthSessionTtlMs))
    }

    @Test
    fun `missing key fails fast with the key in the message`() {
        val (_, runtime) = fresh()
        val ex = assertFailsWith<IllegalStateException> { runtime.get(ConfigKey.AuthSessionTtlMs) }
        assertTrue(
            ex.message!!.contains(ConfigKey.AuthSessionTtlMs.key),
            "error should name the missing key, got: ${ex.message}",
        )
    }

    @Test
    fun `value_type mismatch between DB row and ConfigKey fails fast`() {
        val (repo, runtime) = fresh()
        // Manually seed a row that declares the wrong type for this key.
        repo.upsert(
            key = ConfigKey.AuthSessionTtlMs.key,
            valueType = ValueType.STRING.wire,
            value = "1000",
            now = 0L,
        )
        val ex = assertFailsWith<IllegalStateException> { runtime.get(ConfigKey.AuthSessionTtlMs) }
        assertTrue(ex.message!!.contains("value_type=string"))
        assertTrue(ex.message!!.contains("expects long"))
    }

    @Test
    fun `unparsable value fails fast and names the key`() {
        val (repo, runtime) = fresh()
        repo.upsert(
            key = ConfigKey.AuthSessionTtlMs.key,
            valueType = ValueType.LONG.wire,
            value = "not-a-number",
            now = 0L,
        )
        val ex = assertFailsWith<IllegalStateException> { runtime.get(ConfigKey.AuthSessionTtlMs) }
        assertTrue(ex.message!!.contains(ConfigKey.AuthSessionTtlMs.key))
        assertTrue(ex.message!!.contains("not-a-number"))
    }

    @Test
    fun `set overwrites and round-trips`() {
        val (_, runtime) = fresh()
        runtime.seedIfMissing(ConfigKey.AuthChallengeTtlMs, "60000")
        runtime.set(ConfigKey.AuthChallengeTtlMs, 30_000L)
        assertEquals(30_000L, runtime.get(ConfigKey.AuthChallengeTtlMs))
    }
}
