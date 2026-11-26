package com.thedirepack.constructioncalc.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The one way app data files are replaced: write a temp file next to [target], fsync it, then move it
 * over [target] atomically. A crash leaves either the old file or the new one, never a truncated mix,
 * and there is no delete-then-rename window in which neither exists.
 */
object AtomicFiles {
    fun write(target: File, payload: ByteArray, tempPrefix: String = ".atomic-") =
        write(target, tempPrefix) { it.write(payload) }

    fun write(target: File, tempPrefix: String = ".atomic-", write: (OutputStream) -> Unit) {
        val directory = target.absoluteFile.parentFile ?: throw IOException("No folder for ${target.name}.")
        val temp = File.createTempFile(tempPrefix, ".tmp", directory)
        try {
            FileOutputStream(temp).use { output ->
                // Writers such as ZipOutputStream close what they wrap; keep the real stream open to sync it.
                write(object : OutputStream() {
                    override fun write(b: Int) = output.write(b)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
                    override fun flush() = output.flush()
                })
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }
}
