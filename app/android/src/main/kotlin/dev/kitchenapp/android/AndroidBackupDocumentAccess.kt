package dev.kitchenapp.android

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Reads and writes backup documents selected through Android's Storage Access Framework. */
internal class AndroidBackupDocumentAccess(
    private val contentResolver: ContentResolver,
) {
    fun read(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected backup file.")

        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0

            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count > MAX_BACKUP_DOCUMENT_BYTES - totalBytes) {
                    throw IOException("The selected backup exceeds the 128 MiB size limit.")
                }
                output.write(buffer, 0, count)
                totalBytes += count
            }

            if (totalBytes == 0) {
                throw IOException("The selected backup file is empty.")
            }
            output.toByteArray()
        }
    }

    fun write(uri: Uri, bytes: ByteArray) {
        if (bytes.isEmpty()) throw IOException("Kitchen created an empty backup file.")
        if (bytes.size > MAX_BACKUP_DOCUMENT_BYTES) {
            throw IOException("The backup exceeds Android's 128 MiB size limit.")
        }
        val output = contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Could not create the selected backup file.")

        output.use { stream ->
            stream.write(bytes)
            stream.flush()
        }
    }

    private companion object {
        const val MAX_BACKUP_DOCUMENT_BYTES = 128 * 1024 * 1024
    }
}
