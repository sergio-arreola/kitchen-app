package dev.kitchenapp.data.importers

import dev.kitchenapp.core.application.RecipeImportImageSource
import dev.kitchenapp.data.importers.archive.ArchiveReader
import dev.kitchenapp.data.importers.archive.ImportSource
import dev.kitchenapp.data.importers.archive.PaprikaArchiveInspection
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultRecipeImageLoaderTest {
    @Test
    fun loadsPaprikaPhotoBytesFromTheArchiveEntry() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val reader = FixedArchiveReader(bytes)
        val loader = DefaultRecipeImageLoader(reader)

        val image = runSuspend {
            loader.load(RecipeImportImageSource.PaprikaArchiveEntry("archive", "photos/photo.jpg"))
        }

        assertContentEquals(bytes, image?.bytes)
        assertEquals("image/jpeg", image?.mediaType)
        assertEquals("photos/photo.jpg", reader.lastEntryName)
        loader.close()
    }

    @Test
    fun rejectsNonHttpImageUrlsWithoutFetchingThem() {
        val loader = DefaultRecipeImageLoader(FixedArchiveReader(null))

        val image = runSuspend {
            loader.load(RecipeImportImageSource.Url("file:///private/image.jpg"))
        }

        assertNull(image)
        loader.close()
    }

    private class FixedArchiveReader(private val bytes: ByteArray?) : ArchiveReader {
        var lastEntryName: String? = null

        override fun inspect(source: ImportSource) =
            PaprikaArchiveInspection(source, fileCount = 0, entries = emptyList())

        override fun readEntryContent(source: ImportSource, entryName: String): ByteArray? {
            lastEntryName = entryName
            return bytes
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completion: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                completion = result
            }
        })
        return checkNotNull(completion).getOrThrow()
    }
}
