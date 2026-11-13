package dev.kitchenapp.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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

internal actual fun unzipBackupArchive(bytes: ByteArray): List<BackupArchiveEntry> =
    readZipBackupArchive(bytes)

internal actual fun sha256BackupBytes(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

private fun readZipBackupArchive(bytes: ByteArray): List<BackupArchiveEntry> {
    require(bytes.size <= MAX_BACKUP_ARCHIVE_BYTES) { "Backup archive exceeds the maximum allowed size" }
    val entries = mutableListOf<BackupArchiveEntry>()
    var totalBytes = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val entry = zip.nextEntry ?: break
            require(!entry.isDirectory) { "Backup archive contains a directory entry" }
            require(entries.size < MAX_BACKUP_ENTRY_COUNT) { "Backup archive contains too many entries" }
            if (entry.size > MAX_BACKUP_ENTRY_BYTES) {
                throw IllegalArgumentException("Backup archive entry exceeds the maximum allowed size")
            }
            val output = ByteArrayOutputStream()
            var entryBytes = 0L
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                entryBytes += count
                totalBytes += count
                require(entryBytes <= MAX_BACKUP_ENTRY_BYTES) {
                    "Backup archive entry exceeds the maximum allowed size"
                }
                require(totalBytes <= MAX_BACKUP_TOTAL_UNCOMPRESSED_BYTES) {
                    "Backup archive expands beyond the maximum allowed size"
                }
                output.write(buffer, 0, count)
            }
            entries += BackupArchiveEntry(entry.name, output.toByteArray())
            zip.closeEntry()
        }
    }
    require(entries.isNotEmpty()) { "Backup archive is empty or is not a ZIP file" }
    return entries
}
