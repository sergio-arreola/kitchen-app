package dev.kitchenapp.data.importers.archive

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a redistributable `.paprikarecipes`-shaped zip from the
 * self-authored fixture recipes in `jvmTest/resources/paprika-fixture`.
 * All recipe entries are GZIP-compressed; the photo payload is stored plain.
 */
object PaprikaFixture {

    val recipeEntries = listOf(
        "plain-recipe",
        "recipe-with-photo",
        "fractional-quantities",
        "unicode-fractions",
        "categories",
        "notes",
        "source-url",
        "multiline-directions",
        "missing-optional-metadata",
    )

    /** Builds the fixture archive inside [tempDir] and returns it. */
    fun buildZip(tempDir: File, name: String = "fixture.paprikarecipes"): File {
        val file = File(tempDir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            out.write("recipes/", ByteArray(0))

            recipeEntries.forEach { recipe ->
                out.write("recipes/$recipe.json", gzip(load("recipes/$recipe.json")))
            }

            // Photo payload for recipe-with-photo: stored uncompressed, like a real export.
            out.write("photos/fixture-0002.jpeg", "fixture photo bytes".encodeToByteArray())
        }
        return file
    }

    private fun load(resourcePath: String): ByteArray {
        val stream = requireNotNull(PaprikaFixture::class.java.classLoader.getResourceAsStream("paprika-fixture/$resourcePath")) {
            "Missing fixture resource: $resourcePath"
        }
        return stream.use { it.readBytes() }
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun ZipOutputStream.write(entryName: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(entryName))
        write(bytes)
        closeEntry()
    }
}
