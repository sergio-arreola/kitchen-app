package dev.kitchenapp.core.domain.ingredient

/**
 * Resolves ingredient names using exact normalized names and curated aliases.
 *
 * Ambiguous aliases return all matching ingredients in stable order. No
 * substitutions, plural stemming, or descriptive-word removal are inferred.
 */
public class IngredientResolver(ingredients: Iterable<Ingredient>) {
    private val byNormalizedName: Map<String, List<Ingredient>>

    init {
        val catalog = ingredients.toList()
        require(catalog.map { it.id }.distinct().size == catalog.size) {
            "Ingredient catalog contains duplicate ids"
        }

        byNormalizedName = buildMap<String, List<Ingredient>> {
            for (ingredient in catalog) {
                val keys = (ingredient.aliases + ingredient.name)
                    .map(IngredientNameNormalizer::normalize)
                    .filter(String::isNotEmpty)
                    .toSet()
                for (key in keys) {
                    put(key, get(key).orEmpty() + ingredient)
                }
            }
        }.mapValues { (_, matches) ->
            matches.distinctBy { it.id }.sortedWith(
                compareBy<Ingredient>({ it.normalizedName }, { it.id.toString() }),
            )
        }
    }

    public fun resolve(input: String): IngredientResolution {
        val key = IngredientNameNormalizer.normalize(input)
        val matches = byNormalizedName[key].orEmpty()
        return when (matches.size) {
            0 -> IngredientResolution.Unresolved(input, key)
            1 -> IngredientResolution.Resolved(input, matches.single())
            else -> IngredientResolution.NeedsReview(input, matches)
        }
    }
}
