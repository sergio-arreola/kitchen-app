package dev.kitchenapp.data.importers

import dev.kitchenapp.core.application.RecipeImageLoader
import dev.kitchenapp.core.application.RecipeImportImageSource
import dev.kitchenapp.core.domain.recipe.RecipeImage
import dev.kitchenapp.data.importers.archive.ArchiveReader
import dev.kitchenapp.data.importers.archive.ImportSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.coroutines.cancellation.CancellationException

/** Loads one reviewed recipe image from a URL or a Paprika archive entry. */
class DefaultRecipeImageLoader(
    private val archiveReader: ArchiveReader,
    private val client: HttpClient = HttpClient { expectSuccess = false },
) : RecipeImageLoader {
    override suspend fun load(source: RecipeImportImageSource): RecipeImage? = try {
        when (source) {
            is RecipeImportImageSource.Url -> loadUrl(source.url)
            is RecipeImportImageSource.PaprikaArchiveEntry -> loadArchiveEntry(source)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    fun close() = client.close()

    private suspend fun loadUrl(value: String): RecipeImage? {
        val url = runCatching { Url(value) }.getOrNull() ?: return null
        if (
            url.host.isBlank() ||
            url.user != null ||
            url.password != null ||
            (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS)
        ) {
            return null
        }

        val response = client.get(url)
        if (response.status.value !in 200..299) return null
        val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length > MAX_IMAGE_BYTES) return null
        val declaredType = response.headers[HttpHeaders.ContentType]
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (declaredType != null && declaredType !in ALLOWED_MEDIA_TYPES) return null
        val bytes = readBoundedImage(response.bodyAsChannel()) ?: return null
        val mediaType = mediaTypeForBytes(bytes) ?: return null
        if (declaredType != null && declaredType != mediaType) return null
        return RecipeImage(bytes, mediaType)
    }

    private fun loadArchiveEntry(source: RecipeImportImageSource.PaprikaArchiveEntry): RecipeImage? {
        val bytes = archiveReader.readEntryContent(ImportSource(source.source), source.entryName)
            ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val mediaType = mediaTypeForBytes(bytes) ?: return null
        return RecipeImage(bytes, mediaType)
    }

    private suspend fun readBoundedImage(channel: io.ktor.utils.io.ByteReadChannel): ByteArray? {
        val buffer = ByteArray(MAX_IMAGE_BYTES + 1)
        var size = 0
        while (size < buffer.size) {
            val count = channel.readAvailable(buffer, size, buffer.size - size)
            if (count < 0) break
            if (count > 0) size += count
        }
        if (size == 0 || size > MAX_IMAGE_BYTES) return null
        return buffer.copyOf(size)
    }

    private fun mediaTypeForBytes(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0d.toByte() && bytes[5] == 0x0a.toByte() &&
            bytes[6] == 0x1a.toByte() && bytes[7] == 0x0a.toByte() -> "image/png"
        bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
        bytes.size >= 6 &&
            (bytes.copyOfRange(0, 6).decodeToString() == "GIF87a" ||
                bytes.copyOfRange(0, 6).decodeToString() == "GIF89a") -> "image/gif"
        else -> null
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        val ALLOWED_MEDIA_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}
