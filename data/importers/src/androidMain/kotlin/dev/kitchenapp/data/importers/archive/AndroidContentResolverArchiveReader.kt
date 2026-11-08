package dev.kitchenapp.data.importers.archive

import android.content.ContentResolver
import android.net.Uri
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Reads Paprika archives selected through Android's document picker. */
class AndroidContentResolverArchiveReader(
    private val contentResolver: ContentResolver,
) : ArchiveReader {
    override fun inspect(source: ImportSource): PaprikaArchiveInspection {
        val summaries = mutableListOf<ArchiveEntrySummary>()
        openArchive(source).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.isDirectory) {
                    summaries += ArchiveEntrySummary(
                        name = entry.name,
                        compressedSizeBytes = entry.compressedSize,
                        uncompressedSizeBytes = entry.size,
                        isDirectory = true,
                        gzipOutcome = GzipOutcome.NOT_ATTEMPTED,
                    )
                } else {
                    var decompressedSize = entry.size
                    var failureReason: String? = null
                    val outcome = try {
                        decompressedSize = GZIPInputStream(NonClosingInputStream(zip)).use { gzip ->
                            var count = 0L
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = gzip.read(buffer)
                                if (read == -1) break
                                count += read
                            }
                            count
                        }
                        GzipOutcome.SUCCESS
                    } catch (failure: Exception) {
                        failureReason = failure::class.simpleName
                        GzipOutcome.FAILED
                    }
                    zip.closeEntry()
                    summaries += ArchiveEntrySummary(
                        name = entry.name,
                        compressedSizeBytes = entry.compressedSize,
                        uncompressedSizeBytes = decompressedSize,
                        isDirectory = false,
                        gzipOutcome = outcome,
                        gzipFailureReason = failureReason,
                    )
                }
                entry = zip.nextEntry
            }
        }
        return PaprikaArchiveInspection(
            source = source,
            fileCount = summaries.count { !it.isDirectory },
            entries = summaries,
        )
    }

    override fun readEntryContent(source: ImportSource, entryName: String): ByteArray? =
        openArchive(source).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == entryName) {
                    val bytes = zip.readBytes()
                    return@use try {
                        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
                    } catch (_: Exception) {
                        bytes
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            null
        }

    private fun openArchive(source: ImportSource): ZipInputStream {
        val stream = contentResolver.openInputStream(Uri.parse(source.path))
            ?: error("Could not open selected archive")
        return ZipInputStream(stream)
    }

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
    }
}
