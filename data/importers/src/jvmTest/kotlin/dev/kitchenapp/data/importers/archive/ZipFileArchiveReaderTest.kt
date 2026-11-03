package dev.kitchenapp.data.importers.archive

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZipFileArchiveReaderTest {

    private val tempDir: File = Files.createTempDirectory("paprika-test").toFile()

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun writeZip(entries: List<Pair<String, ByteArray>>): File {
        val file = File(tempDir, "export.paprikarecipes")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (entryName, bytes) ->
                out.putNextEntry(ZipEntry(entryName))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun reportsEntriesSizesAndGzipOutcomes() {
        val json = """{"uid":"1","name":"Pancakes"}""".encodeToByteArray()
        val file = writeZip(
            listOf(
                "recipes/" to ByteArray(0),
                "recipes/one.json" to gzip(json),
                "recipes/photo.jpg" to "not gzip".encodeToByteArray(),
            ),
        )

        val report = ZipFileArchiveReader().inspect(ImportSource(file.path))

        assertEquals(2, report.fileCount)
        assertEquals(1, report.gzipSuccessCount)
        assertEquals(1, report.gzipFailureCount)

        val directory = report.entries.first { it.isDirectory }
        assertEquals("recipes/", directory.name)
        assertEquals(GzipOutcome.NOT_ATTEMPTED, directory.gzipOutcome)

        val gzipped = report.entries.first { it.name == "recipes/one.json" }
        assertEquals(GzipOutcome.SUCCESS, gzipped.gzipOutcome)
        assertEquals(json.size.toLong(), gzipped.uncompressedSizeBytes)

        val plain = report.entries.first { it.name == "recipes/photo.jpg" }
        assertEquals(GzipOutcome.FAILED, plain.gzipOutcome)
        assertEquals("ZipException", plain.gzipFailureReason)
    }

    @Test
    fun missingArchiveThrows() {
        val missing = File(tempDir, "nope.paprikarecipes")
        assertFailsWith<Exception> {
            ZipFileArchiveReader().inspect(ImportSource(missing.path))
        }
    }

    @Test
    fun blankSourcePathIsRejected() {
        assertFailsWith<IllegalArgumentException> { ImportSource("  ") }
        assertEquals("export.paprikarecipes", ImportSource("a/export.paprikarecipes").fileName)
    }
}
