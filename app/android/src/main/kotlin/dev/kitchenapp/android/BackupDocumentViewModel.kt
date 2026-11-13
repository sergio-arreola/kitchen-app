package dev.kitchenapp.android

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kitchenapp.sharedui.BackupDocumentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BackupDocumentViewModel : ViewModel() {
    private enum class Operation {
        EXPORT,
        IMPORT,
    }

    private var pendingOperation: Operation? = null
    private var pendingExportBytes: ByteArray? = null

    private val _result = MutableStateFlow<BackupDocumentResult?>(null)
    val result = _result.asStateFlow()

    val isPickerPending: Boolean
        get() = pendingOperation != null

    val isExportPending: Boolean
        get() = pendingOperation == Operation.EXPORT

    val isImportPending: Boolean
        get() = pendingOperation == Operation.IMPORT

    fun beginExport(bytes: ByteArray): Boolean {
        if (isPickerPending) return false
        pendingOperation = Operation.EXPORT
        pendingExportBytes = bytes
        _result.value = null
        return true
    }

    fun exportBytes(): ByteArray? =
        pendingExportBytes.takeIf { pendingOperation == Operation.EXPORT }

    fun writeExport(uri: Uri, access: AndroidBackupDocumentAccess) {
        val bytes = exportBytes()
        if (bytes == null) {
            reportInterrupted("Export")
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { access.write(uri, bytes) }
            }
            completeExport(result)
        }
    }

    fun completeExport(result: Result<Unit>?) {
        if (pendingOperation != Operation.EXPORT) {
            reportInterrupted("Export")
            return
        }
        clearPending()
        _result.value = when {
            result == null -> BackupDocumentResult.PickerCancelled
            result.isSuccess -> BackupDocumentResult.ExportSucceeded
            else -> BackupDocumentResult.Failed(
                result.exceptionOrNull()?.message ?: "Could not save the backup file.",
            )
        }
    }

    fun beginImport(): Boolean {
        if (isPickerPending) return false
        pendingOperation = Operation.IMPORT
        _result.value = null
        return true
    }

    fun readImport(uri: Uri, access: AndroidBackupDocumentAccess) {
        viewModelScope.launch {
            val result: Result<ByteArray?> = runCatching {
                withContext(Dispatchers.IO) { access.read(uri) }
            }
            completeImport(result)
        }
    }

    fun completeImport(result: Result<ByteArray?>) {
        if (pendingOperation != Operation.IMPORT) {
            reportInterrupted("Import")
            return
        }
        clearPending()
        _result.value = result.fold(
            onSuccess = { bytes ->
                bytes?.let(BackupDocumentResult::ImportSelected)
                    ?: BackupDocumentResult.PickerCancelled
            },
            onFailure = { error ->
                BackupDocumentResult.Failed(
                    error.message ?: "Could not read the selected backup file.",
                )
            },
        )
    }

    fun reportInterrupted(operation: String) {
        clearPending()
        _result.value = BackupDocumentResult.Failed(
            "$operation was interrupted while the app was restarting. No restore was applied. Please try again.",
        )
    }

    fun consumeResult() {
        _result.value = null
    }

    override fun onCleared() {
        clearPending()
    }

    private fun clearPending() {
        pendingOperation = null
        pendingExportBytes = null
    }
}
