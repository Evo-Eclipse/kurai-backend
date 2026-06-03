package com.example.infrastructure.storage
import com.example.domain.storage.GetResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LocalObjectStoreTest {
    private lateinit var rootDir: Path
    private lateinit var store: LocalObjectStore

    @BeforeTest
    fun setUp() {
        rootDir = createTempDirectory("kurai-objstore-test-")
        store = LocalObjectStore(rootDir)
    }

    @AfterTest
    fun tearDown() {
        rootDir
            .toFile()
            .walkBottomUp()
            .forEach { it.delete() }
    }

    @Test
    fun `put then get returns binary-identical bytes`() =
        runTest {
            val payload = ByteArray(4096) { (it % 256).toByte() }
            store.put("models/model.bin", payload)
            val result = store.get("models/model.bin")
            assertIs<GetResult.Found>(result)
            assertEquals(payload.toList(), result.bytes.toList())
        }

    @Test
    fun `get on unknown key returns NotFound`() =
        runTest {
            assertEquals(GetResult.NotFound, store.get("missing/object.bin"))
        }

    @Test
    fun `put creates nested directories implicitly`() =
        runTest {
            store.put("a/b/c/d.bin", byteArrayOf(1, 2, 3))
            assertEquals(true, Files.exists(rootDir.resolve("a/b/c/d.bin")))
        }

    @Test
    fun `put overwrites existing key atomically`() =
        runTest {
            store.put("k", byteArrayOf(1))
            store.put("k", byteArrayOf(2, 2))
            val r = store.get("k")
            assertIs<GetResult.Found>(r)
            assertEquals(listOf<Byte>(2, 2), r.bytes.toList())
        }

    @Test
    fun `concurrent put and get on distinct keys completes without races`() =
        runTest {
            val payloads = (0 until 10).map { i -> "k$i" to ByteArray(1024) { (i * 7).toByte() } }
            coroutineScope {
                payloads
                    .map { (k, bytes) -> async { store.put(k, bytes) } }
                    .awaitAll()
            }
            val read =
                coroutineScope {
                    payloads.map { (k, _) -> async { k to store.get(k) } }.awaitAll()
                }
            for ((k, expected) in payloads) {
                val got = read.first { it.first == k }.second
                assertIs<GetResult.Found>(got)
                assertEquals(expected.toList(), got.bytes.toList())
            }
        }

    @Test
    fun `concurrent overwrites of same key always leave a valid full payload`() =
        runTest {
            val payloads = (0 until 10).map { i -> ByteArray(2048) { i.toByte() } }
            coroutineScope {
                payloads.map { p -> async { store.put("hot-key", p) } }.awaitAll()
            }
            val r = store.get("hot-key")
            assertIs<GetResult.Found>(r)
            // Atomicity property: whoever won the race left a full 2048-byte
            // payload identical to one of the inputs — never a torn write.
            assertEquals(2048, r.bytes.size)
            val firstByte = r.bytes[0]
            val match = payloads.any { it[0] == firstByte && it.toList() == r.bytes.toList() }
            assertEquals(true, match)
        }

    @Test
    fun `empty key is rejected`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { store.put("", byteArrayOf(1)) }
        }

    @Test
    fun `absolute key is rejected`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { store.put("/etc/passwd", byteArrayOf(1)) }
        }

    @Test
    fun `parent traversal in key is rejected`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { store.put("a/../b", byteArrayOf(1)) }
        }
}
