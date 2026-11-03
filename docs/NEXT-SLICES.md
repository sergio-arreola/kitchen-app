# Guided Next Slices

This document intentionally guides rather than supplies the implementation.

## Slice 1 — Domain foundation

Implement these in `core:domain` first:

1. Typed IDs using Kotlin `Uuid` and `@JvmInline value class` wrappers.
2. `Rational` with normalization, denominator validation, comparison, addition/subtraction, and multiplication.
3. `UnitDimension` and `UnitCode`.
4. `TrackingMode`, `ApproximateLevel`, `IngredientImportance`, and normalization/readiness enums.
5. Small value objects before larger entities.

### Specific APIs/resources

- Kotlin `kotlin.uuid.Uuid` for durable identifiers.
- Kotlin `kotlin.time.Instant` / `Clock` for moments.
- `kotlinx-datetime` only for civil date/time concepts such as `LocalDate` and `TimeZone`.
- Kotlin `kotlin.test` for shared tests.

### Method-level guidance

For `Rational`, implement and test methods equivalent to:

```text
of(numerator, denominator)
plus(other)
minus(other)
times(other)
compareTo(other)
isZero()
isPositive()
```

Use `gcd` normalization in the constructor/factory; do not allow denominator zero; keep sign on the numerator.

Do not implement Recipe or Inventory entities until the primitives have table-driven tests.

### Gate

`./gradlew :core:domain:allTests` (or the target-specific test tasks Gradle exposes) must pass before moving on.

---

## Slice 2 — Paprika inspection harness

The first real CLI command should be:

```text
inspect-paprika <path>
```

Do not begin by writing the final importer.

### Components to create

In `data:importers`:

```text
ImportSource
ArchiveEntrySummary
PaprikaArchiveInspection
ArchiveReader (interface)
```

Put JVM archive access behind an implementation in `jvmMain` or another platform-specific source set rather than in common domain code.

The first implementation needs only to:

1. open `.paprikarecipes` as a ZIP archive;
2. enumerate entries;
3. report entry names, compressed/uncompressed sizes, and file count;
4. identify which entries can be decompressed as GZIP;
5. inspect JSON top-level keys without committing to final DTOs;
6. produce a sanitized machine-readable inspection report.

### JVM methods/classes to investigate

Use the JDK rather than adding an archive dependency initially:

```text
java.util.zip.ZipFile
java.util.zip.ZipEntry
java.util.zip.GZIPInputStream
java.io.InputStream
```

For JSON inspection, add `org.jetbrains.kotlin.plugin.serialization` and `kotlinx-serialization-json` only when the command reaches the JSON stage. Parse to `JsonElement` / `JsonObject` first; do not guess a `PaprikaRecipeDto` before seeing actual fixture keys.

### CLI behavior

Keep argument handling trivial for the first command. A full CLI framework is not justified yet.

Expected future shape:

```text
recipe-dev inspect-paprika path/to/export.paprikarecipes
```

Report:

```text
archive file count
entry naming pattern
GZIP success/failure
JSON top-level field names
null/missing field frequencies
image representation
category representation
date value examples
ingredient storage shape
direction storage shape
```

Never print full private recipe text by default. Inspection is schema-focused.

### Fixtures

Create a private working fixture exported from Paprika, but do not commit personal/copyrighted recipe collections.

Create a small redistributable public fixture yourself containing cases such as:

```text
plain recipe
recipe with photo
fractional quantities
Unicode fractions
categories
notes
source URL
multiline directions
missing optional metadata
```

### Paprika format resource

Paprika's official documentation describes `.paprikarecipes` as a ZIP containing gzipped JSON recipe files. Treat that statement only as the container contract; derive the actual JSON fields from your controlled export.

### Gate

Do not start `PaprikaRecipeDto` until the inspection report demonstrates the actual format used by your fixture.

---

## Slice 3 — Importer mapping

After inspection, define transport DTOs in `data:importers`; never deserialize directly into domain entities.

Use `kotlinx.serialization` with unknown-key tolerance. Separate:

```text
PaprikaRecipeDto
RecipeDraft
Recipe
```

Mapping path:

```text
PaprikaRecipeDto -> RecipeDraft -> later normalization -> Recipe
```

Keep raw ingredient lines intact in `RecipeDraft`; ingredient parsing belongs to a later slice.

Add per-record outcomes:

```text
SUCCESS
SUCCESS_WITH_WARNINGS
FAILED
SKIPPED
```

### Gate

A failed recipe must produce an explicit failure record. No archive entry may disappear silently.

---

## When to activate reserved dependencies

The version catalog already records likely dependencies so versions remain centralized, but do not add them to modules until required:

- SQLDelight: Slice 9/persistence.
- Ktor: URL import, later.
- coroutines: when application/repository async boundaries exist.
- `kotlinx-datetime`: when civil dates enter the actual domain.

Keeping a version in the catalog is not permission to import the library everywhere.
