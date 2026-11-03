package dev.kitchenapp.data.importers.archive

/** Outcome of attempting to decompress an archive entry as GZIP. */
enum class GzipOutcome {
    /** Decompressed cleanly. */
    SUCCESS,

    /** Could not be decompressed as GZIP. */
    FAILED,

    /** Not attempted (directory entry, zero-size entry, etc.). */
    NOT_ATTEMPTED,
}

/** Structural summary of a single archive entry; never holds content. */
data class ArchiveEntrySummary(
    /** Full entry path inside the archive. */
    val name: String,
    val compressedSizeBytes: Long,
    val uncompressedSizeBytes: Long,
    val isDirectory: Boolean,
    val gzipOutcome: GzipOutcome,

    /** Sanitized failure reason (e.g. exception class name) when FAILED. */
    val gzipFailureReason: String? = null,
)
