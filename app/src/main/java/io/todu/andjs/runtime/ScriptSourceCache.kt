package io.todu.andjs.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Private, bounded cache of converted source. No execution scopes or variable values are saved. */
internal class ScriptSourceCache(private val root: File) {
    companion object {
        private const val REVISION = "babel-7.28.5-lexical-v1"
        private const val MAX_ENTRY = 2 * 1024 * 1024
        private const val MAX_TOTAL = 8L * 1024 * 1024
        private val files = Regex("[0-9a-f]{64}\\.js")
        private val lock = Any()
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun key(source: String): String = digest((REVISION + "\u0000" + source).toByteArray(Charsets.UTF_8))

    fun read(key: String): String? = synchronized(lock) {
        try {
            val file = File(root, "$key.js")
            if (!file.isFile || file.length() !in 66..(MAX_ENTRY + 65).toLong()) return@synchronized null
            val bytes = file.readBytes()
            if (bytes.size !in 66..MAX_ENTRY + 65 || bytes[64] != '\n'.code.toByte()) return@synchronized null
            val body = bytes.copyOfRange(65, bytes.size)
            if (String(bytes, 0, 64, Charsets.US_ASCII) != digest(body)) return@synchronized null
            file.setLastModified(System.currentTimeMillis())
            String(body, Charsets.UTF_8)
        } catch (_: IOException) { null }
    }

    fun write(key: String, source: String) = synchronized(lock) {
        val body = source.toByteArray(Charsets.UTF_8)
        if (body.size > MAX_ENTRY) return@synchronized
        var temporary: File? = null
        try {
            if (!root.isDirectory && !root.mkdirs()) return@synchronized
            temporary = File.createTempFile("source-", ".tmp", root)
            temporary.outputStream().use { out ->
                out.write((digest(body) + "\n").toByteArray(Charsets.US_ASCII))
                out.write(body)
            }
            Files.move(temporary.toPath(), File(root, "$key.js").toPath(), StandardCopyOption.REPLACE_EXISTING)
            val entries = root.listFiles()?.filter { it.isFile && files.matches(it.name) }
                ?.sortedByDescending { it.lastModified() }.orEmpty()
            var size = 0L
            entries.forEachIndexed { index, file ->
                size += file.length()
                if (index >= 64 || size > MAX_TOTAL) file.delete()
            }
        } catch (_: IOException) {
            // A full or unavailable cache must not prevent script execution.
        } finally { temporary?.delete() }
    }
}
