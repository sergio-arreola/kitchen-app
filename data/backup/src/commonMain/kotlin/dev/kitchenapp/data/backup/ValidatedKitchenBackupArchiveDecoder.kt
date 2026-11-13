package dev.kitchenapp.data.backup

import dev.kitchenapp.core.application.KitchenBackupArchiveDecoder
import dev.kitchenapp.core.application.KitchenBackupRestoreRequest

class ValidatedKitchenBackupArchiveDecoder(
    private val validator: KitchenBackupArchiveValidator = KitchenBackupArchiveValidator(),
) : KitchenBackupArchiveDecoder {
    override fun decode(bytes: ByteArray): KitchenBackupRestoreRequest =
        validator.validate(bytes).toRestoreRequest()
}
