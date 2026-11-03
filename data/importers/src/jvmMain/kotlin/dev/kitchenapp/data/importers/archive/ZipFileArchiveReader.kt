package dev.kitchenapp.data.importers.archive

import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** [ArchiveReader] backed by the JDK ZIP/GZIP support; no extra dependencies. */
class ZipFileArchiveReader : ArchiveReader {

    override fun inspect(source: ImportSource): PaprikaArchiveInspection =
        ZipFile(File(source.path)).use { zip ->
            val entries = zip.entries().asSequence().map { it.summarize(zip) }.toList()
            PaprikaArchiveInspection(
                source = source,
                fileCount = entries.count { !it.isDirectory },
                entries = entries,
            )
        }

    override fun readEntryContent(source: ImportSource, entryName: String): ByteArray? {
        ZipFile(File(source.path)).use { zip ->
            val entry = zip.getEntry(entryName) ?: return null
            val raw = zip.getInputStream(entry).use { it.readBytes() }
            return try {
                GZIPInputStream(raw.inputStream()).use { gzip -> gzip.readBytes() }
            } catch (e: Exception) {
                raw
            }
        }
    }

    private fun ZipEntry.summarize(zip: ZipFile): ArchiveEntrySummary {
        if (isDirectory) {
            return ArchiveEntrySummary(
                name = name,
                compressedSizeBytes = compressedSize,
                uncompressedSizeBytes = size,
                isDirectory = true,
                gzipOutcome = GzipOutcome.NOT_ATTEMPTED,
            )
        }
        val decompressedBytes = try {
            zip.getInputStream(this).use { input ->
                GZIPInputStream(input).use { gzip ->
                    var bytes = 0L
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = gzip.read(buffer)
                        if (read == -1) break
                        bytes += read
                    }
                    bytes
                }
            }
        } catch (e: Exception) {
            return ArchiveEntrySummary(
                name = name,
                compressedSizeBytes = compressedSize,
                uncompressedSizeBytes = size,
                isDirectory = false,
                gzipOutcome = GzipOutcome.FAILED,
                gzipFailureReason = e::class.simpleName,
            )
        }
        return ArchiveEntrySummary(
            name = name,
            compressedSizeBytes = compressedSize,
            uncompressedSizeBytes = decompressedBytes,
            isDirectory = false,
            gzipOutcome = GzipOutcome.SUCCESS,
        )
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
    }
}
