package dev.kitchenapp.data.importers

import dev.kitchenapp.core.application.RecipePage
import dev.kitchenapp.data.importers.ingredient.IngredientLineParser
import dev.kitchenapp.data.importers.paprika.ImportOutcome
import dev.kitchenapp.data.importers.paprika.RecipeDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

sealed interface UrlRecipeImportRecord : RecipeImportRecord {
    data class Success(
        override val sourceKey: String,
        override val draft: RecipeDraft,
    ) : UrlRecipeImportRecord {
        override val outcome = ImportOutcome.SUCCESS
    }

    data class SuccessWithWarnings(
        override val sourceKey: String,
        override val draft: RecipeDraft,
        override val warnings: List<String>,
    ) : UrlRecipeImportRecord {
        override val outcome = ImportOutcome.SUCCESS_WITH_WARNINGS
    }

    data class NeedsManualReview(
        override val sourceKey: String,
        val reason: String,
        val suggestedName: String? = null,
        override val warnings: List<String> = listOf(reason),
    ) : UrlRecipeImportRecord {
        override val outcome = ImportOutcome.NEEDS_REVIEW
        override val draft: RecipeDraft? = null
    }

    data class Failure(
        override val sourceKey: String,
        val reason: String,
    ) : UrlRecipeImportRecord {
        override val outcome = ImportOutcome.FAILED
        override val draft: RecipeDraft? = null
    }
}

/** Parses Schema.org JSON-LD first, then supported Recipe microdata. */
class UrlRecipeImporter : RecipeImporter<RecipePage, UrlRecipeImportRecord> {
    override fun import(source: RecipePage): List<UrlRecipeImportRecord> =
        listOf(parse(source))

    private fun parse(page: RecipePage): UrlRecipeImportRecord {
        val jsonScripts = if (page.contentType.orEmpty().contains("application/ld+json", ignoreCase = true)) {
            listOf(page.body)
        } else {
            JSON_LD_SCRIPT.findAll(page.body).map { it.groupValues[1] }.toList()
        }
        var malformedJsonLd = false
        val candidates = jsonScripts.flatMap { script ->
            try {
                findRecipes(json.parseToJsonElement(script))
            } catch (_: Exception) {
                malformedJsonLd = true
                emptyList()
            }
        }

        if (candidates.size == 1) {
            return candidates.single().toDraft(page, emptyList())
        }
        if (candidates.size > 1) {
            return UrlRecipeImportRecord.NeedsManualReview(
                sourceKey = page.requestedUrl,
                reason = "Page contains multiple recipe records.",
            )
        }

        val microdata = parseMicrodata(page)
        if (microdata != null) {
            return microdata.toDraft(
                page,
                if (malformedJsonLd) listOf("Malformed JSON-LD was ignored; imported Schema.org microdata instead.") else emptyList(),
            )
        }
        val title = TITLE.find(page.body)?.groupValues?.get(1)?.toPlainText()?.takeIf(String::isNotBlank)
        val reason = when {
            malformedJsonLd -> "Recipe JSON-LD is malformed; review the page manually."
            else -> "No supported Schema.org Recipe data was found; review the page manually."
        }
        return UrlRecipeImportRecord.NeedsManualReview(page.requestedUrl, reason, title)
    }

    private fun findRecipes(element: JsonElement): List<RecipeFields> = when (element) {
        is JsonArray -> element.flatMap(::findRecipes)
        is JsonObject -> {
            val own = if (element.isRecipeType()) listOfNotNull(element.toRecipeFields()) else emptyList()
            own + element.values.flatMap(::findRecipes)
        }
        else -> emptyList()
    }

    private fun JsonObject.isRecipeType(): Boolean {
        val type = this["@type"] ?: return false
        val values = when (type) {
            is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(type.contentOrNull)
            else -> emptyList()
        }
        return values.any { it.substringAfterLast('/').substringAfterLast('#').equals("Recipe", ignoreCase = true) }
    }

    private fun JsonObject.toRecipeFields(): RecipeFields? {
        val name = this["name"].asText().trim().takeIf(String::isNotBlank) ?: return null
        val ingredients = this["recipeIngredient"].asTextList()
        val directions = this["recipeInstructions"].instructionText()
        val categories = this["recipeCategory"].asTextList()
        val warnings = buildList {
            if (ingredients.isEmpty()) add("missing ingredients")
            if (directions.isBlank()) add("missing directions")
        }
        return RecipeFields(
            name = name,
            servings = this["recipeYield"].asTextList().joinToString(", ").ifBlank { null },
            ingredients = ingredients,
            directions = directions,
            notes = this["description"].asText().ifBlank { null },
            categories = categories,
            warnings = warnings,
        )
    }

    private fun JsonElement?.asText(): String =
        (this as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonElement?.asTextList(): List<String> = when (this) {
        is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        is JsonPrimitive -> listOfNotNull(contentOrNull?.takeIf(String::isNotBlank))
        else -> emptyList()
    }

    private fun JsonElement?.instructionText(): String = when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonArray -> mapNotNull { it.instructionText().takeIf(String::isNotBlank) }.joinToString("\n")
        is JsonObject -> {
            val text = this["text"].asText()
            if (text.isNotBlank()) text else this["itemListElement"].instructionText()
        }
        else -> ""
    }

    private fun parseMicrodata(page: RecipePage): RecipeFields? {
        if (!RECIPE_ITEMTYPE.containsMatchIn(page.body)) return null
        val props = mutableMapOf<String, MutableList<String>>()
        OPENING_TAG.findAll(page.body).forEach { match ->
            val tag = match.groupValues[1].lowercase()
            val attributes = match.groupValues[2]
            val property = ITEMPROP.find(attributes)?.groupValues?.get(1) ?: return@forEach
            val content = CONTENT.find(attributes)?.groupValues?.get(1)
            val value = content ?: if (tag in VOID_TAGS) {
                ""
            } else {
                val closing = Regex("(?is)</$tag\\s*>").find(page.body, match.range.last + 1) ?: return@forEach
                page.body.substring(match.range.last + 1, closing.range.first).toPlainText()
            }
            if (value.isNotBlank()) props.getOrPut(property) { mutableListOf() } += value
        }
        val name = props["name"]?.firstOrNull()?.takeIf(String::isNotBlank) ?: return null
        val ingredients = props["recipeIngredient"].orEmpty()
        val directions = props["recipeInstructions"].orEmpty().joinToString("\n")
        val warnings = buildList {
            if (ingredients.isEmpty()) add("missing ingredients")
            if (directions.isBlank()) add("missing directions")
        }
        return RecipeFields(
            name = name,
            servings = props["recipeYield"]?.joinToString(", ")?.ifBlank { null },
            ingredients = ingredients,
            directions = directions,
            notes = props["description"]?.firstOrNull(),
            categories = props["recipeCategory"].orEmpty(),
            warnings = warnings,
        )
    }

    private fun RecipeFields.toDraft(page: RecipePage, extraWarnings: List<String>): UrlRecipeImportRecord {
        val warnings = this.warnings + extraWarnings
        val draft = RecipeDraft(
            sourceEntryName = page.requestedUrl,
            externalId = null,
            name = name,
            servings = servings,
            ingredientLines = ingredients,
            parsedIngredients = ingredients.map(IngredientLineParser::parse),
            directionsText = directions,
            notes = notes,
            categories = categories,
            sourceName = page.finalUrl.substringAfter("://").substringBefore('/').ifBlank { null },
            sourceUrl = page.finalUrl,
            photoFileName = null,
            photoHash = null,
        )
        return if (warnings.isEmpty()) {
            UrlRecipeImportRecord.Success(page.requestedUrl, draft)
        } else {
            UrlRecipeImportRecord.SuccessWithWarnings(page.requestedUrl, draft, warnings)
        }
    }

    private fun String.toPlainText(): String = TAG.replace(this, " ")
        .replace("&nbsp;", " ")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .trim()
        .replace(Regex("\\s+"), " ")

    private data class RecipeFields(
        val name: String,
        val servings: String?,
        val ingredients: List<String>,
        val directions: String,
        val notes: String?,
        val categories: List<String>,
        val warnings: List<String>,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val JSON_LD_SCRIPT = Regex("(?is)<script\\b[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script\\s*>")
        val TITLE = Regex("(?is)<title\\b[^>]*>(.*?)</title\\s*>")
        val TAG = Regex("<[^>]*>")
        val RECIPE_ITEMTYPE = Regex("(?is)itemtype\\s*=\\s*[\"'][^\"']*(?:schema\\.org|data-vocabulary\\.org)/Recipe[\"']")
        val OPENING_TAG = Regex("(?is)<([a-z][\\w:-]*)\\b([^>]*)>")
        val ITEMPROP = Regex("(?is)\\bitemprop\\s*=\\s*[\"']([^\"']+)[\"']")
        val CONTENT = Regex("(?is)\\bcontent\\s*=\\s*[\"']([^\"']*)[\"']")
        val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    }
}
