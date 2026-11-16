package dev.kitchenapp.data.importers.archive

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Builds a redistributable archive from self-authored recipes and a generated image. */
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

    val photoBytes: ByteArray = ByteArrayOutputStream().also { output ->
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 0x5a7f3a)
        check(ImageIO.write(image, "jpeg", output))
    }.toByteArray()

    fun buildZip(
        tempDir: File,
        name: String = "fixture.paprikarecipes",
        extraRecipeJson: Map<String, String> = emptyMap(),
    ): File {
        val file = File(tempDir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            out.write("recipes/", ByteArray(0))
            recipeEntries.forEach { recipe ->
                out.write("recipes/$recipe.json", gzip(load("recipes/$recipe.json")))
            }
            extraRecipeJson.forEach { (entryName, json) ->
                out.write(entryName, gzip(json.encodeToByteArray()))
            }
            out.write("photos/fixture-0002.jpeg", photoBytes)
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
