package dev.kitchenapp.data.importers.archive

/** Reference to an import archive; resolved by the platform [ArchiveReader]. */
@JvmInline
value class ImportSource(val path: String) {
    init {
        require(path.isNotBlank()) { "Import source path must not be blank" }
    }

    /** File name component of the path, regardless of separator style. */
    val fileName: String
        get() = path.substringAfterLast('/').substringAfterLast('\\')
}
