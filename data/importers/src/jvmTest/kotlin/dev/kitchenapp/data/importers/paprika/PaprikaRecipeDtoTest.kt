package dev.kitchenapp.data.importers.paprika

import dev.kitchenapp.data.importers.archive.PaprikaFixture
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaprikaRecipeDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun decodeFixture(name: String): PaprikaRecipeDto {
        val bytes = PaprikaFixture::class.java.classLoader
            .getResourceAsStream("paprika-fixture/recipes/$name.json")!!.readBytes()
        return json.decodeFromString<PaprikaRecipeDto>(bytes.decodeToString())
    }

    @Test
    fun decodesPlainRecipe() {
        val dto = decodeFixture("plain-recipe")
        assertEquals("fixture-0001", dto.uid)
        assertEquals("Fixture Scrambled Eggs", dto.name)
        assertEquals("4", dto.servings)
        assertEquals("2 eggs\nPinch of salt", dto.ingredients)
    }

    @Test
    fun decodesOptionalFields() {
        val photo = decodeFixture("recipe-with-photo")
        assertEquals("fixture-0002.jpeg", photo.photoName)
        assertEquals("00000000000000000000000000000000", photo.photoHash)

        val categorized = decodeFixture("categories")
        assertEquals(listOf("Breakfast", "Fixture"), categorized.categories)

        val sourced = decodeFixture("source-url")
        assertEquals("Fixture Kitchen", sourced.source)
        assertEquals("https://example.com/roasted-chickpeas", sourced.sourceUrl)

        val noted = decodeFixture("notes")
        assertEquals("Rinse the rice first. Keeps 4 days refrigerated.", noted.notes)
    }

    @Test
    fun toleratesMissingOptionalMetadata() {
        val dto = decodeFixture("missing-optional-metadata")
        assertEquals("fixture-0009", dto.uid)
        assertEquals("Fixture Boiled Water", dto.name)
        assertNull(dto.servings)
        assertNull(dto.notes)
        assertNull(dto.photoName)
        assertEquals(emptyList(), dto.categories)
    }

    @Test
    fun ignoresUnknownKeysFromRealExports() {
        val text = """
            {
              "uid": "extra-1",
              "name": "Extra Recipe",
              "on_favorites": true,
              "nutritional_info": {"calories": "100"},
              "created": "2024-01-01 00:00:00"
            }
        """.trimIndent()
        val dto = json.decodeFromString<PaprikaRecipeDto>(text)
        assertEquals("extra-1", dto.uid)
        assertEquals("Extra Recipe", dto.name)
        assertNull(dto.ingredients)
    }
}
