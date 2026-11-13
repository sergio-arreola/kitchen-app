package dev.kitchenapp.data.backup

internal const val MAX_BACKUP_ARCHIVE_BYTES = 128 * 1024 * 1024
internal const val MAX_BACKUP_ENTRY_BYTES = 64 * 1024 * 1024
internal const val MAX_BACKUP_TOTAL_UNCOMPRESSED_BYTES = 128 * 1024 * 1024
internal const val MAX_BACKUP_ENTRY_COUNT = 10_000
internal const val MAX_BACKUP_MANIFEST_BYTES = 1024 * 1024
internal const val MAX_BACKUP_PAYLOAD_BYTES = 64 * 1024 * 1024
internal const val MAX_BACKUP_IMAGE_BYTES = 8 * 1024 * 1024
internal const val BACKUP_ARCHIVE_OVERHEAD_RESERVE = 4 * 1024 * 1024

internal expect fun zipBackupArchive(entries: List<BackupArchiveEntry>): ByteArray
internal expect fun unzipBackupArchive(bytes: ByteArray): List<BackupArchiveEntry>
internal expect fun sha256BackupBytes(bytes: ByteArray): String

internal data class BackupArchiveEntry(
    val path: String,
    val bytes: ByteArray,
)

internal fun imageMediaTypeFromBytes(bytes: ByteArray): String? = when {
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
        bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte() &&
        bytes[4] == 0x0d.toByte() && bytes[5] == 0x0a.toByte() &&
        bytes[6] == 0x1a.toByte() && bytes[7] == 0x0a.toByte() -> "image/png"
    bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
        bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
    bytes.size >= 6 &&
        (bytes.copyOfRange(0, 6).decodeToString() == "GIF87a" ||
            bytes.copyOfRange(0, 6).decodeToString() == "GIF89a") -> "image/gif"
    else -> null
}
