package dev.kitchenapp.data.importers.archive

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaprikaJsonInspectorTest {

    private val tempDir: File = Files.createTempDirectory("paprika-json").toFile()
    private val fixture = PaprikaFixture.buildZip(tempDir)
    private val reader = ZipFileArchiveReader()
    private val source = ImportSource(fixture.path)

    private fun summaries(): List<EntryJsonSummary> =
        reader.inspect(source).entries
            .filter { it.gzipOutcome == GzipOutcome.SUCCESS }
            .mapNotNull { reader.readEntryContent(source, it.name) }
            .map { PaprikaJsonInspector().summarize("entry", it) }

    @Test
    fun parsesAllFixtureRecipes() {
        val all = summaries()
        assertEquals(PaprikaFixture.recipeEntries.size, all.size)
        all.forEach { assertNull(it.parseError) }
    }

    @Test
    fun reportsTypesNullsAndMissing() {
        val byKey = summaries().aggregateJsonKeys().associateBy { it.key }

        val uid = byKey.getValue("uid")
        assertEquals(PaprikaFixture.recipeEntries.size, uid.presentCount)
        assertEquals(mapOf("string" to PaprikaFixture.recipeEntries.size), uid.typeCounts)

        // categories only on one recipe; the rest miss the key entirely.
        val categories = byKey.getValue("categories")
        assertEquals(1, categories.presentCount)
        assertEquals(PaprikaFixture.recipeEntries.size - 1, categories.missingCount)
        assertEquals("array", categories.typeCounts.keys.single())
        assertEquals(1, categories.examples.size)

        // photo is null on all but recipe-with-photo.
        val photo = byKey.getValue("photo")
        assertEquals(1, photo.presentCount)
        assertEquals(0, photo.nullCount)
    }

    @Test
    fun describesShapesInsteadOfContent() {
        val all = summaries().aggregateJsonKeys().associateBy { it.key }

        // Ingredients and directions are single multiline strings (as in real exports).
        val ingredients = all.getValue("ingredients")
        assertTrue(
            ingredients.examples.filterNotNull().all { it.startsWith("string with ") && it.endsWith(" lines") },
            "ingredients examples: ${ingredients.examples}",
        )
        val directions = all.getValue("directions")
        assertTrue(
            directions.examples.filterNotNull().all { it.startsWith("string with ") && it.endsWith(" lines") },
            "directions examples: ${directions.examples}",
        )

        // Arrays are described by member type and size.
        val categories = all.getValue("categories")
        assertTrue(categories.examples.filterNotNull().all { it.startsWith("array[string]×") })

        // Scalar values are represented by type, never by their contents.
        val servingsLike = all.getValue("servings")
        assertEquals("string", servingsLike.examples.single())
    }

    @Test
    fun nonObjectJsonIsRecordedAsParseError() {
        val summary = PaprikaJsonInspector().summarize("bad", "[1,2]".encodeToByteArray())
        assertEquals("NotJsonObject", summary.parseError)
        assertTrue(summary.keys.isEmpty())
    }
}
