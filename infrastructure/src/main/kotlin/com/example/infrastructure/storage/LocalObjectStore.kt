package com.example.infrastructure.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Filesystem-backed [ObjectStorePort]. Each key maps to a file under [rootDir].
 *
 * Atomicity for [put] is achieved by writing to a sibling temp file then
 * `Files.move(..., ATOMIC_MOVE)`. ATOMIC_MOVE is supported on POSIX and
 * NTFS filesystems and falls back to non-atomic move on filesystems that
 * don't, but in our deployment (Linux ext4) it is atomic.
 *
 * Keys are interpreted as relative paths under [rootDir]; slash-separated
 * segments map to nested directories that are auto-created on write.
 */
class LocalObjectStore(
    private val rootDir: Path,
) : ObjectStorePort {
    init {
        Files.createDirectories(rootDir)
    }

    override suspend fun put(
        key: String,
        bytes: ByteArray,
    ): Unit =
        withContext(Dispatchers.IO) {
            val target = resolve(key)
            target.parent?.let(Files::createDirectories)
            // Sibling tmp file so the atomic move stays on the same filesystem.
            val tmp =
                Files.createTempFile(
                    target.parent ?: rootDir,
                    ".${target.fileName}.",
                    ".tmp",
                )
            try {
                Files.write(tmp, bytes)
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (t: Throwable) {
                Files.deleteIfExists(tmp)
                throw t
            }
        }

    override suspend fun get(key: String): GetResult =
        withContext(Dispatchers.IO) {
            val target = resolve(key)
            try {
                GetResult.Found(Files.readAllBytes(target))
            } catch (_: NoSuchFileException) {
                GetResult.NotFound
            }
        }

    private fun resolve(key: String): Path {
        require(key.isNotEmpty()) { "Object key must not be empty" }
        require(!key.startsWith('/')) { "Object key must be relative, got: $key" }
        require(key.split('/').none { it == ".." }) {
            "Object key must not contain '..' segments, got: $key"
        }
        return rootDir.resolve(key).normalize()
    }
}
