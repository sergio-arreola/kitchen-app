package dev.kitchenapp.core.domain.enums

/**
 * Normalization state of a domain record.
 *
 * Imported data starts [RAW] and reaches [NORMALIZED] after the
 * normalization pass (unit canonization, ingredient parsing, ...).
 */
enum class NormalizationState(val value: Int) {
    /** Untouched input, e.g. straight from an import. */
    RAW(0),

    /** Passed normalization and is canonical for its kind. */
    NORMALIZED(1);

    companion object {
        fun fromValue(value: Int): NormalizationState? = entries.find { it.value == value }
    }
}
