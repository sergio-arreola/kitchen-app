package dev.kitchenapp.data.importers.archive

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaprikaFixtureTest {

    private val tempDir: File = Files.createTempDirectory("paprika-fixture").toFile()

    @Test
    fun fixtureArchiveInspectsCleanly() {
        val report = ZipFileArchiveReader().inspect(ImportSource(PaprikaFixture.buildZip(tempDir).path))

        assertEquals(PaprikaFixture.recipeEntries.size + 1, report.fileCount) // recipes + photo
        assertEquals(1, report.gzipFailureCount) // photo payload, stored plain

        val byName = report.entries.associateBy { it.name }
        PaprikaFixture.recipeEntries.forEach { recipe ->
            val entry = byName["recipes/$recipe.json"]
            assertTrue(entry != null && entry.gzipOutcome == GzipOutcome.SUCCESS, "recipe/$recipe")
        }

        // Photo payload is stored plain, so GZIP fails as expected.
        val photo = byName["photos/fixture-0002.jpeg"]
        assertEquals(GzipOutcome.FAILED, photo?.gzipOutcome)
    }

    @Test
    fun fixtureResourcesAreValidJsonObjects() {
        PaprikaFixture.recipeEntries.forEach { recipe ->
            val bytes = PaprikaFixture::class.java.classLoader
                .getResourceAsStream("paprika-fixture/recipes/$recipe.json")!!.readBytes()
            val text = bytes.decodeToString()
            assertTrue(text.trimStart().startsWith("{") && text.trimEnd().endsWith("}"), recipe)
        }
    }
}
