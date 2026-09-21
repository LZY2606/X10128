package rci.store

import rci.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 本地文件型存储：整个库序列化为一个 JSON 文件。
 * 写入走临时文件 + 原子 move，发布过程中即使失败也不会留下半个版本文件。
 */
class FileStore(private val path: Path) {
    private val lock = ReentrantReadWriteLock()

    init {
        path.toFile().parentFile?.mkdirs()
        if (!Files.exists(path)) Files.writeString(path, "{}")
    }

    fun load(): Map<String, Any?> = lock.read {
        val text = Files.readString(path)
        if (text.isBlank()) emptyMap() else @Suppress("UNCHECKED_CAST") (Json.parse(text) as Map<String, Any?>)
    }

    fun save(data: Map<String, Any?>) = lock.write {
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json.stringify(data, indent = true))
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 在唯一写入锁内做读-改-写，保证发布原子性。 */
    fun <T> mutate(block: (MutableMap<String, Any?>) -> T): T = lock.write {
        val text = Files.readString(path)
        @Suppress("UNCHECKED_CAST")
        val data: MutableMap<String, Any?> =
            (if (text.isBlank()) emptyMap() else Json.parse(text) as Map<String, Any?>)
                .toMutableMap()
        val result = block(data)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json.stringify(data, indent = true))
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        result
    }
}
