package dev.kitchenapp.data.backup

internal expect fun zipBackupArchive(entries: List<BackupArchiveEntry>): ByteArray
internal expect fun sha256BackupBytes(bytes: ByteArray): String

internal data class BackupArchiveEntry(
    val path: String,
    val bytes: ByteArray,
)
