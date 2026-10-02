package dev.agentle.core.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Atomic small-file writes: a sibling temp file, `fsync`, then an atomic rename over the target. A failure at any
 * point leaves the previous content (or no file) and removes the temp file, so readers never see a partial file.
 */
object AtomicFiles {
    const val TEMP_SUFFIX: String = ".tmp"

    fun write(target: File, bytes: ByteArray) = write(target) { it.write(bytes) }

    fun write(target: File, writer: (OutputStream) -> Unit) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("target has no parent directory")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create directory")
        val temp = File(dir, target.name + TEMP_SUFFIX)
        var moved = false
        try {
            FileOutputStream(temp).use { out ->
                writer(out)
                out.flush()
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            moved = true
        } finally {
            if (!moved) temp.delete()
        }
    }

    /** Deletes [file] and a leftover temp file of it. Returns true if [file] no longer exists. */
    fun delete(file: File): Boolean {
        File(file.path + TEMP_SUFFIX).delete()
        return !file.exists() || file.delete()
    }
}
