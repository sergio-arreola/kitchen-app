package dev.kitchenapp.data.importers.archive

/** Sanitized, machine-readable inspection report for a `.paprikarecipes` archive. */
data class PaprikaArchiveInspection(
    val source: ImportSource,
    /** Number of non-directory entries. */
    val fileCount: Int,
    val entries: List<ArchiveEntrySummary>,
) {
    val gzipSuccessCount: Int
        get() = entries.count { it.gzipOutcome == GzipOutcome.SUCCESS }

    val gzipFailureCount: Int
        get() = entries.count { it.gzipOutcome == GzipOutcome.FAILED }
}
