package dev.kitchenapp.data.importers.paprika

import dev.kitchenapp.data.importers.RecipeImportRecord

/** Per-record import outcome. */
enum class ImportOutcome {
    SUCCESS,
    SUCCESS_WITH_WARNINGS,
    NEEDS_REVIEW,
    FAILED,
    SKIPPED,
}

/** Result of importing one archive entry; exactly one record per entry. */
sealed interface PaprikaImportRecord : RecipeImportRecord {
    val entryName: String

    override val sourceKey: String
        get() = entryName

    override val draft: RecipeDraft?
        get() = null

    /** Entry imported cleanly. */
    data class Success(
        override val entryName: String,
        override val draft: RecipeDraft,
    ) : PaprikaImportRecord {
        override val outcome: ImportOutcome = ImportOutcome.SUCCESS
    }

    /** Entry imported, but with noted anomalies (missing uid, empty sections, ...). */
    data class SuccessWithWarnings(
        override val entryName: String,
        override val draft: RecipeDraft,
        override val warnings: List<String>,
    ) : PaprikaImportRecord {
        override val outcome: ImportOutcome = ImportOutcome.SUCCESS_WITH_WARNINGS
    }

    /** Entry was a recipe candidate but could not be imported. */
    data class Failure(
        override val entryName: String,
        val reason: String,
    ) : PaprikaImportRecord {
        override val outcome: ImportOutcome = ImportOutcome.FAILED
    }

    /** Entry intentionally not imported (photo payload, directory, unsupported type). */
    data class Skipped(
        override val entryName: String,
        val reason: String,
    ) : PaprikaImportRecord {
        override val outcome: ImportOutcome = ImportOutcome.SKIPPED
    }
}
