package dev.kitchenapp.core.application

data class RecipePage(
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String?,
    val body: String,
)

sealed interface RecipePageFetchResult {
    data class Success(val page: RecipePage) : RecipePageFetchResult
    data class Failure(val requestedUrl: String, val reason: String) : RecipePageFetchResult
}

/** Application boundary for fetching a recipe page from a user-provided URL. */
fun interface RecipePageFetcher {
    suspend fun fetch(url: String): RecipePageFetchResult
}
