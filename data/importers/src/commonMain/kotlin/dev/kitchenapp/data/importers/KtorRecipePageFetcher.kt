package dev.kitchenapp.data.importers

import dev.kitchenapp.core.application.RecipePage
import dev.kitchenapp.core.application.RecipePageFetchResult
import dev.kitchenapp.core.application.RecipePageFetcher
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.coroutines.cancellation.CancellationException

/** Ktor-backed page fetcher using the platform's configured default engine. */
class KtorRecipePageFetcher private constructor(
    private val client: HttpClient,
) : RecipePageFetcher {
    constructor() : this(HttpClient { expectSuccess = false })

    override suspend fun fetch(url: String): RecipePageFetchResult {
        val parsed = try {
            Url(url)
        } catch (_: Exception) {
            return RecipePageFetchResult.Failure(url, "Invalid URL.")
        }
        if (
            parsed.host.isBlank() ||
            parsed.user != null ||
            parsed.password != null ||
            (parsed.protocol != URLProtocol.HTTP && parsed.protocol != URLProtocol.HTTPS)
        ) {
            return RecipePageFetchResult.Failure(url, "Only HTTP or HTTPS recipe page URLs are supported.")
        }

        return try {
            val response = client.get(parsed)
            val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength != null && contentLength > MAX_PAGE_BYTES) {
                return RecipePageFetchResult.Failure(url, "Page is larger than the 1 MiB import limit.")
            }
            if (response.status.value !in 200..299) {
                return RecipePageFetchResult.Failure(url, "Page request returned HTTP ${response.status.value}.")
            }

            val contentType = response.headers[HttpHeaders.ContentType]
            if (contentType != null && !isSupportedContentType(contentType)) {
                return RecipePageFetchResult.Failure(url, "Unsupported page content type.")
            }
            val body = response.bodyAsText()
            if (body.encodeToByteArray().size > MAX_PAGE_BYTES) {
                return RecipePageFetchResult.Failure(url, "Page is larger than the 1 MiB import limit.")
            }
            RecipePageFetchResult.Success(
                RecipePage(
                    requestedUrl = url,
                    finalUrl = response.request.url.toString(),
                    statusCode = response.status.value,
                    contentType = contentType,
                    body = body,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            RecipePageFetchResult.Failure(url, "Page request failed (${failure::class.simpleName}).")
        }
    }

    fun close() = client.close()

    private fun isSupportedContentType(value: String): Boolean =
        value.substringBefore(';').trim().lowercase() in SUPPORTED_CONTENT_TYPES

    private companion object {
        const val MAX_PAGE_BYTES = 1_048_576
        val SUPPORTED_CONTENT_TYPES = setOf(
            "text/html",
            "application/xhtml+xml",
            "application/ld+json",
            "application/json",
        )
    }
}
