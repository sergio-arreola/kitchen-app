package dev.kitchenapp.data.importers.archive

/** Platform-specific archive access; the only place archive I/O is allowed. */
interface ArchiveReader {
    /**
     * Opens the archive behind [source] and builds its inspection report.
     *
     * @throws Exception on unrecoverable I/O failure; per-entry problems
     *   are captured in the report instead of thrown.
     */
    fun inspect(source: ImportSource): PaprikaArchiveInspection

    /**
     * Returns the named entry's content, GZIP-decompressed when the entry
     * is GZIP-compressed; null when the entry does not exist.
     */
    fun readEntryContent(source: ImportSource, entryName: String): ByteArray?
}
