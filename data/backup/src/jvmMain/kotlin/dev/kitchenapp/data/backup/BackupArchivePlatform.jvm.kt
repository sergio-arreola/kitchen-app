package dev.kitchenapp.data.backup

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal actual fun zipBackupArchive(entries: List<BackupArchiveEntry>): ByteArray =
    ByteArrayOutputStream().use { buffer ->
        ZipOutputStream(buffer).use { zip ->
            entries.sortedBy(BackupArchiveEntry::path).forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.path).apply { time = 0L })
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
        buffer.toByteArray()
    }

internal actual fun sha256BackupBytes(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
