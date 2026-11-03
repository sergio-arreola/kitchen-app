package dev.kitchenapp.data.importers.paprika

import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.units.UnitCode
import dev.kitchenapp.data.importers.archive.ImportSource
import dev.kitchenapp.data.importers.archive.PaprikaFixture
import dev.kitchenapp.data.importers.archive.ZipFileArchiveReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaprikaRecipeImporterTest {

    private val tempDir: File = Files.createTempDirectory("paprika-import").toFile()

    private fun importFixture(): List<PaprikaImportRecord> {
        val zip = PaprikaFixture.buildZip(tempDir)
        return PaprikaRecipeImporter(ZipFileArchiveReader()).import(ImportSource(zip.path))
    }

    @Test
    fun producesOneRecordPerArchiveEntry() {
        val records = importFixture()
        // 9 recipe entries + photo payload + recipes/ directory entry;
        // nothing disappears silently.
        assertEquals(PaprikaFixture.recipeEntries.size + 2, records.size)
        assertEquals(
            records.map { it.entryName }.distinct().size,
            records.size,
            "entry names must be unique",
        )
    }

    @Test
    fun importsAllFixtureRecipesSuccessfully() {
        val records = importFixture()
        val failures = records.filterIsInstance<PaprikaImportRecord.Failure>()
        assertEquals(emptyList(), failures.map { "${it.entryName}: ${it.reason}" })

        val byName = records.filterIsInstance<PaprikaImportRecord.Success>()
            .associateBy { it.draft.sourceEntryName }
        assertEquals(PaprikaFixture.recipeEntries.size, byName.size)

        val plain = byName["recipes/plain-recipe.json"]!!.draft
        assertEquals("fixture-0001", plain.externalId)
        assertEquals("Fixture Scrambled Eggs", plain.name)
        assertEquals("4", plain.servings)
        assertEquals(listOf("2 eggs", "Pinch of salt"), plain.ingredientLines)
        assertEquals("Whisk the eggs.\nCook over low heat.", plain.directionsText)
        assertNull(plain.photoFileName)
    }

    @Test
    fun parsesIngredientLinesIntoStructuredDraftIngredients() {
        val records = importFixture()
        val plain = records.filterIsInstance<PaprikaImportRecord.Success>()
            .first { it.draft.sourceEntryName == "recipes/plain-recipe.json" }.draft

        assertEquals(listOf("2 eggs", "Pinch of salt"), plain.ingredientLines)
        assertEquals(plain.ingredientLines.size, plain.parsedIngredients.size)

        val eggs = plain.parsedIngredients[0]
        assertEquals(Rational.of(2), eggs.quantity)
        assertEquals(UnitCode.EGG, eggs.unit)
        assertEquals("egg", eggs.name)

        val salt = plain.parsedIngredients[1]
        assertNull(salt.quantity)
        assertEquals("Pinch of salt", salt.name)
    }

    @Test
    fun skipsNonRecipeEntries() {
        val records = importFixture()
        val skipped = records.filterIsInstance<PaprikaImportRecord.Skipped>()
        assertEquals(
            setOf("recipes/", "photos/fixture-0002.jpeg"),
            skipped.map { it.entryName }.toSet(),
        )
        skipped.forEach { assertTrue(it.reason.isNotBlank()) }
    }

    @Test
    fun keepsRawIngredientLinesAndPhotoRefs() {
        val records = importFixture()
        val drafts = records.filterIsInstance<PaprikaImportRecord.Success>()
            .filter { it.draft.sourceEntryName.endsWith("recipe-with-photo.json") }
            .map { it.draft }

        val photo = drafts.single()
        assertEquals("fixture-0002.jpeg", photo.photoFileName)
        assertEquals("00000000000000000000000000000000", photo.photoHash)
        assertEquals(listOf("1 slice bread", "1 tsp butter"), photo.ingredientLines)

        val unicode = records.filterIsInstance<PaprikaImportRecord.Success>()
            .single { it.draft.sourceEntryName.endsWith("unicode-fractions.json") }
            .draft
        assertEquals("½ cup strawberries", unicode.ingredientLines.first())
    }

    @Test
    fun warnsWhenCoreFieldsAreMissing() {
        val zip = buildZip(
            tempDir,
            "recipes/no-uid.json" to gzip("""{"name": "No Uid", "ingredients": "1 x"}"""),
        )
        val records = PaprikaRecipeImporter(ZipFileArchiveReader()).import(ImportSource(zip.path))

        val warned = assertIs<PaprikaImportRecord.SuccessWithWarnings>(records.single())
        assertTrue("missing uid" in warned.warnings)
        assertEquals(null, warned.draft.externalId)
    }

    @Test
    fun failsExplicitlyOnMalformedJson() {
        val zip = buildZip(tempDir, "recipes/broken.json" to gzip("{not json"))
        val records = PaprikaRecipeImporter(ZipFileArchiveReader()).import(ImportSource(zip.path))

        val failed = assertIs<PaprikaImportRecord.Failure>(records.single())
        assertEquals("recipes/broken.json", failed.entryName)
        assertTrue(failed.reason.startsWith("json: "))
    }

    @Test
    fun failsExplicitlyOnRecipeWithoutName() {
        val zip = buildZip(tempDir, "recipes/nameless.json" to gzip("""{"uid": "x"}"""))
        val records = PaprikaRecipeImporter(ZipFileArchiveReader()).import(ImportSource(zip.path))

        val failed = assertIs<PaprikaImportRecord.Failure>(records.single())
        assertEquals("recipes/nameless.json", failed.entryName)
    }

    @Test
    fun failsExplicitlyOnNonObjectJson() {
        val zip = buildZip(tempDir, "recipes/array.json" to gzip("""["nope"]"""))
        val records = PaprikaRecipeImporter(ZipFileArchiveReader()).import(ImportSource(zip.path))

        assertIs<PaprikaImportRecord.Failure>(records.single())
    }

    private fun buildZip(tempDir: File, vararg entries: Pair<String, ByteArray>): File {
        val file = File(tempDir, "custom-${System.nanoTime()}.paprikarecipes")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.encodeToByteArray()) } }
            .toByteArray()
}
