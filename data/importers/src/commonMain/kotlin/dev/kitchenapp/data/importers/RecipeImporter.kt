package dev.kitchenapp.data.importers

import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.data.importers.paprika.ImportOutcome
import dev.kitchenapp.data.importers.paprika.RecipeDraft

/** One source adapter maps transport input into explicit per-record results. */
interface RecipeImporter<in Source, out Record : RecipeImportRecord> {
    fun import(source: Source): List<Record>
}

interface RecipeImportRecord {
    val sourceKey: String
    val outcome: ImportOutcome
    val draft: RecipeDraft?
    val warnings: List<String> get() = emptyList()
}

data class RecipeImportPreview(
    val record: RecipeImportRecord,
    /** Existing recipe ID or the source key of an earlier record in this batch. */
    val duplicateOf: String? = null,
)

/** Adds duplicate hints without removing or rewriting any import outcome. */
class RecipeImportPreviewer {
    fun preview(
        records: List<RecipeImportRecord>,
        existingRecipes: List<Recipe>,
    ): List<RecipeImportPreview> {
        val existingBySource = existingRecipes
            .filter { !it.sourceUrl.isNullOrBlank() }
            .associateBy { normalizeUrl(requireNotNull(it.sourceUrl)) }
        val existingByName = existingRecipes.associateBy { normalizeName(it.name) }
        val seenBySource = mutableMapOf<String, String>()
        val seenByName = mutableMapOf<String, String>()

        return records.map { record ->
            val draft = record.draft
            if (draft == null) {
                RecipeImportPreview(record)
            } else {
                val source = draft.sourceUrl?.takeIf(String::isNotBlank)?.let(::normalizeUrl)
                val name = normalizeName(draft.name)
                val duplicate = source?.let(existingBySource::get)
                    ?: existingByName[name]
                val duplicateKey = duplicate?.id?.toString()
                    ?: source?.let(seenBySource::get)
                    ?: seenByName[name]
                val original = duplicateKey ?: record.sourceKey
                source?.let { seenBySource.putIfAbsent(it, original) }
                seenByName.putIfAbsent(name, original)
                RecipeImportPreview(record, duplicateKey)
            }
        }
    }

    private fun normalizeName(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun normalizeUrl(value: String): String = value.trim().trimEnd('/')
}
