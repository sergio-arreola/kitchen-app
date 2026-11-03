package dev.kitchenapp.data.importers.paprika

import dev.kitchenapp.data.importers.archive.ArchiveReader
import dev.kitchenapp.data.importers.archive.GzipOutcome
import dev.kitchenapp.data.importers.archive.ImportSource
import dev.kitchenapp.data.importers.archive.PaprikaArchiveInspection
import dev.kitchenapp.data.importers.ingredient.IngredientLineParser
import kotlinx.serialization.json.Json

/** Maps a `.paprikarecipes` archive to per-entry [PaprikaImportRecord]s with [RecipeDraft] payloads. */
class PaprikaRecipeImporter(private val reader: ArchiveReader) {

    /** Imports every entry of the archive; returns one record per entry. */
    fun import(source: ImportSource): List<PaprikaImportRecord> {
        val report = reader.inspect(source)
        return report.entries.flatMap { importEntry(source, report, it.name) }
    }

    private fun importEntry(
        source: ImportSource,
        report: PaprikaArchiveInspection,
        entryName: String,
    ): List<PaprikaImportRecord> {
        val summary = report.entries.firstOrNull { it.name == entryName } ?: return listOf(
            PaprikaImportRecord.Failure(entryName, "missing from inspection report"),
        )
        if (summary.isDirectory) {
            return listOf(PaprikaImportRecord.Skipped(entryName, "directory entry"))
        }
        if (!entryName.endsWith(JSON_SUFFIX)) {
            return listOf(PaprikaImportRecord.Skipped(entryName, "not a recipe JSON entry"))
        }
        if (summary.gzipOutcome != GzipOutcome.SUCCESS) {
            return listOf(
                PaprikaImportRecord.Skipped(
                    entryName,
                    "not gzip-compressed (${summary.gzipFailureReason ?: summary.gzipOutcome})",
                ),
            )
        }

        val bytes = reader.readEntryContent(source, entryName)
            ?: return listOf(PaprikaImportRecord.Failure(entryName, "entry unreadable"))

        val dto = try {
            json.decodeFromString<PaprikaRecipeDto>(bytes.decodeToString())
        } catch (e: Exception) {
            return listOf(PaprikaImportRecord.Failure(entryName, "json: ${e::class.simpleName}"))
        }

        val (draft, warnings) = mapToDraft(entryName, dto)
        return when {
            draft == null -> listOf(PaprikaImportRecord.Failure(entryName, "missing name"))
            warnings.isEmpty() -> listOf(PaprikaImportRecord.Success(entryName, draft))
            else -> listOf(PaprikaImportRecord.SuccessWithWarnings(entryName, draft, warnings))
        }
    }

    /** Returns the draft plus warnings; null draft means unmappable. */
    private fun mapToDraft(entryName: String, dto: PaprikaRecipeDto): Pair<RecipeDraft?, List<String>> {
        val warnings = mutableListOf<String>()
        val name = dto.name?.trim().orEmpty()
        if (name.isEmpty()) return null to warnings

        val lines = dto.ingredients.orEmpty().split('\n')
            .map { line -> line.trimEnd('\r') }
            .filter { it.isNotBlank() }

        if (dto.uid.isNullOrBlank()) warnings += "missing uid"
        if (dto.ingredients.isNullOrBlank()) warnings += "missing ingredients"
        if (dto.directions.isNullOrBlank()) warnings += "missing directions"

        val draft = RecipeDraft(
            sourceEntryName = entryName,
            externalId = dto.uid,
            name = name,
            servings = dto.servings,
            ingredientLines = lines,
            parsedIngredients = lines.map(IngredientLineParser::parse),
            directionsText = dto.directions.orEmpty(),
            notes = dto.notes,
            categories = dto.categories,
            sourceName = dto.source,
            sourceUrl = dto.sourceUrl,
            photoFileName = dto.photoName,
            photoHash = dto.photoHash,
        )
        return draft to warnings
    }

    private companion object {
        const val JSON_SUFFIX = ".json"

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}
