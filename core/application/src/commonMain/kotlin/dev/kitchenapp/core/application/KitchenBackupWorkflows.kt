package dev.kitchenapp.core.application

/** Decodes and validates an archive into a request that can be previewed for restore. */
fun interface KitchenBackupArchiveDecoder {
    fun decode(bytes: ByteArray): KitchenBackupRestoreRequest
}

class DecodeKitchenBackupArchive(
    private val decoder: KitchenBackupArchiveDecoder,
) {
    fun decode(bytes: ByteArray): KitchenBackupRestoreRequest =
        decoder.decode(bytes)
}

/** Backup and restore use cases presented to the UI as one application boundary. */
data class KitchenBackupWorkflows(
    val export: ExportKitchenBackup,
    val decode: DecodeKitchenBackupArchive,
    val previewRestore: PreviewKitchenBackupRestore,
    val applyRestore: ApplyKitchenBackupRestore,
)
