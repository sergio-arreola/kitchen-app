# Implementation Slices and Gates

This document records implementation scope and acceptance gates. Slices 1–11 are implemented; Slice 12 is in progress. Completed slice descriptions remain as a record of scope and acceptance criteria.

## Roadmap status

| Slice | Scope | Status |
|---|---|---|
| 0 | Repository/bootstrap verification | Complete; see [BOOTSTRAP.md](BOOTSTRAP.md) |
| 1 | Domain primitives | Implemented |
| 2 | Paprika archive inspection | Implemented |
| 3 | Paprika recipe-draft import | Implemented |
| 4 | Ingredient-line parsing | Implemented |
| 5 | Canonical ingredients and recipe domain | Implemented |
| 6 | Kitchen inventory and freshness | Implemented |
| 7 | Quantity, equipment, and exclusion evaluation | Implemented |
| 8 | Explainable matching, ranking, and simulation fixtures | Implemented |
| 9 | Local SQLite persistence | Implemented |
| 10 | Application workflows and cooking transactions | Implemented |
| 11 | URL/JSON-LD and manual recipe import | Implemented |
| 12 | Android-first product UI and desktop engineering UI | In progress |
| 13 | Backup/export, offline acceptance, and V1 closeout | Planned; target release 1.0.0 |

The sequence keeps domain decisions and the recommendation engine testable before growing the UI. The product target is Android; Desktop/JVM remains the development and inspection target. iOS portability is an architectural goal, not a V1 delivery requirement. External recipe discovery, barcode/photo/receipt recognition, cloud sync, household sharing, and AI recipe generation are later work.

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

## Slice 4 — Ingredient line parsing

Consume the Slice 1 primitives (`Rational`, `UnitCode`, `UnitDimension`) by
parsing the raw ingredient lines that Slice 3 deliberately left intact in
`RecipeDraft`.

### Components to create

In `data:importers`, `ingredient` package, common pure Kotlin (no platform
or serialization dependencies):

```text
ParsedIngredient
IngredientLineParser
```

`ParsedIngredient` fields:

- `name` (never blank)
- `quantity: Rational?`
- `quantityHigh: Rational?` (upper bound of a range)
- `unit: UnitCode?` (consumers may default to `PIECE`/`UNKNOWN` by dimension)
- `preparation: String?` (trailing `, chopped` clause)
- `importance: IngredientImportance`
- `approximateLevel: ApproximateLevel`

### Method-level guidance

- `parse(line)` is a pure function; it never throws and never drops content.
- Quantity forms: integers, decimals, ASCII fractions (`1/2`), mixed numbers
  (`1 1/2`), attached Unicode fractions (`1½`, `½`), ranges (`2-3`, `1 to 2`).
- Units: match tokens against a conservative variant table. Ambiguous single
  letters (`c`, `t`, `T`, `in`, `m`) are excluded deliberately — temperature
  codes and prepositions must not swallow ingredient words.
- `~` / `about` prefix → `ApproximateLevel.MODERATE`; missing quantity →
  `ROUGH`; otherwise `EXACT`.
- `(optional)` → `IngredientImportance.OPTIONAL`; default is `CRITICAL`.
- Degrade, never drop: a line that resists parsing keeps its full text as
  `name` with `quantity = null`; a blank remainder after a recognized unit
  falls back to the unit display name or the original line.

### Wiring

- `RecipeDraft` gains `parsedIngredients` (default `emptyList()`);
  `ingredientLines` stays raw for traceability.
- The Paprika importer populates it during DTO→draft mapping. No DTO changes.

### Out of scope

Unit conversion/scaling, importance heuristics beyond `(optional)`, and
persistence (Slice 9).

### Gate

`./gradlew :data:importers:allTests` passes with table-driven parser tests
covering the fixture cases (fractional quantities, Unicode fractions,
missing quantities, preparation clauses).

---

## Slice 5 — Canonical ingredient and recipe domain

Turn imported drafts into source-independent domain records. Paprika DTOs and
format-specific concepts stay at the importer boundary; all recipe sources use
the same canonical model.

### Components

- `Ingredient`, `Recipe`, and `RecipeIngredient` identities/value objects.
- Normalized ingredient names and explicit resolved, unresolved, or
  review-needed states.
- A deterministic ingredient resolver backed by curated aliases.
- Mapping from `RecipeDraft` to a reviewable canonical recipe draft.
- Raw ingredient lines retained beside parsed and resolved data.

Do not silently infer substitutions or ingredient-specific mass/volume
conversions. Ambiguous matches remain unresolved for review.

### Gate

Reviewed fixtures cover aliases, punctuation, descriptive text, unknown
ingredients, and ambiguous matches. Aim for at least 95% normalization
accuracy on a reviewed corpus; uncertain inputs remain visible for review.

---

## Slice 6 — Kitchen inventory and freshness

Model the kitchen across configurable locations rather than as a pantry
boolean. Support mixed precision: presence for staples, approximate amounts for
opened packages, and exact amounts for items such as eggs or meat.

### Components

- Inventory items/lots tied to canonical ingredients and locations.
- Presence, approximate, and exact tracking modes with quantities and units
  where known.
- Purchase, opened, frozen, and user-provided use-by dates.
- Advisory recommended-use windows for perishables, with user overrides and
  frozen-state handling.
- Explicit add, adjust, move, freeze, and discard operations.

Keep manufacturer dates, user dates, and calculated recommendations distinct.
Freshness guidance is advisory and must not be presented as a food-safety
guarantee. Shelf-stable items are trackable when the user wants them.

### Gate

Tests cover presence-only staples, approximate opened packages, exact
quantities, multiple locations, user overrides, frozen items, and unknown
dates. A recommendation never overwrites a user-entered date.

---

## Slice 7 — Quantity, equipment, and exclusion evaluation

Implement deterministic evaluation primitives used by matching. Prevent
quantity false positives such as treating two eggs as enough when a recipe
requires six.

### Components

- `UnitConverter` for compatible dimensions and explicit conversion rules.
- Ingredient-specific conversions only where a reviewed rule exists; never
  assume a universal cup-to-grams conversion.
- `QuantityEvaluator` for exact, approximate, and presence-only tracking plus
  ingredient importance.
- Readiness states with itemized reasons for missing or uncertain amounts.
- Hard dietary/allergen exclusions and configurable soft preferences.
- Equipment requirements classified as required, preferred, substitutable,
  or irrelevant; missing preferred equipment creates a warning or penalty.

### Gate

Fixtures cover insufficient quantities, compatible/incompatible units,
presence-only spices, uncertain amounts, hard exclusions, soft preferences,
and equipment substitutes. The benchmark must contain zero known
quantity-related false “Ready Now” results.

---

## Slice 8 — Explainable matching, ranking, and kitchen simulation

Build the deterministic meal-decision engine before production UI. Given the
same recipes, inventory, equipment, household rules, and query, it returns the
same readiness, ranking, and explanation.

### Components

- `MealQuery` for structured filters such as meal, available time, servings,
  categories, and required ingredients.
- `RecipeMatcher` combining readiness, exclusions, equipment, and query
  constraints.
- `RecipeRanker` with a documented score breakdown.
- Simulation metadata supplies estimated time, serving capacity, and meal
  types absent from the current recipe model.
- Shopping quantities aggregate only when units match or a reviewed conversion
  exists; unknown quantities stay visible and incompatible units remain separate.
- Freshness/use-soon signals and ingredient-importance weighting.
- Explanations for available, missing, uncertain, expiring, and
  equipment-constrained ingredients.
- Reusable kitchen simulation fixtures for recipes, ingredients, inventory,
  equipment, and expected outcomes.
- `ShoppingAggregator` for missing ingredients across selected recipes.

Ranking stays deterministic. The score adds 100/50/0 for ready,
needs-confirmation, and not-ready; up to 20 for required-ingredient coverage; a
capped use-soon bonus weighted by ingredient importance; and subtracts
soft-preference and equipment-warning penalties. Exact weights are exposed in
`ScoreBreakdown` and documented in `RecipeRanker`. A requested time or serving
filter rejects candidates with unknown metadata rather than guessing. A future
intent interpreter may translate natural language into a structured query, but
it does not replace the matcher or ranker.

### Gate

Reviewed scenarios cover sufficiency, use-soon ranking, exclusions, equipment
warnings, time/serving filters, and shopping aggregation. Every result is
explainable and reproducible; there are zero known quantity-related false
“Ready Now” outcomes.

---

## Slice 9 — Local SQLite persistence

Persist canonical recipes and kitchen state locally with SQLDelight. The
device database is authoritative for V1; core features require no account,
server, or connection.

### Components

- SQLDelight schema for recipes, ingredients, recipe ingredients, inventory,
  locations, equipment, household rules, cook events, transactions, and
  leftovers.
- Repository interfaces in `core:application`; SQLDelight implementations in
  `data:database`.
- Explicit database/domain mappers and versioned migrations.
- Atomic transaction boundaries for operations that update multiple records.
- Android and JVM drivers, isolated from domain code.

### Gate

Migration tests start from the prior schema. Repository tests cover round
trips and observed updates. Transaction tests prove failed multi-record
operations leave no partial state. Core read/write workflows work with
networking disabled.

---

## Slice 10 — Application workflows and cooking transactions

Connect repositories and deterministic engines through use cases. Cooking is
an explicit user-confirmed event; browsing or starting a recipe never changes
inventory.

### Components

- Use cases for import/review, search/suggestions, inventory editing,
  equipment/rule setup, and shopping-list creation.
- `StartCooking` as a non-mutating session, followed by explicit
  `CompleteCooking` confirmation.
- Cook event data for recipe, servings, time, and actual consumption.
- Atomic inventory depletion with a reversible transaction/undo path.
- Optional leftover capture as prepared food linked to its recipe, amount, and
  prepared date.
- Leftovers included in later inventory and meal suggestions.

Do not infer that cooking finished from elapsed time or app state. Let the user
adjust quantities when actual cooking differs from the recipe.

### Gate

Use-case tests verify that browsing and starting do not deplete stock;
completion updates tracked quantities atomically; undo restores prior state;
and optional leftovers persist across reload and can appear in suggestions.

---

## Slice 11 — Additional recipe import adapters

Extend the source-independent importer boundary without changing canonical
recipe models.

### Components

- A `RecipeImporter` abstraction with Paprika, URL/JSON-LD, and manual
  adapters.
- URL fetching behind an application interface, with a Ktor implementation
  in the importer/data layer.
- JSON-LD/Schema.org `Recipe` extraction first, supported structured HTML
  next, and a clear review/manual fallback.
- Import preview, field-level warnings, duplicate detection, and explicit
  per-record outcomes.
- Manual recipe creation/editing through the canonical draft path.

Broad web scraping and paywall bypass are not requirements. Unsupported or
ambiguous pages should yield a useful manual-review path.

### Gate

Fixtures cover structured markup, incomplete markup, malformed pages, network
failures, duplicates, and manual correction. Failures remain visible; no
source entry disappears silently.

---

## Slice 12 — Product UI and developer UI

After the domain, simulation gate, persistence, and application workflows are
stable, build the Compose flows. Android is the V1 product. Desktop is mainly
for importing and inspecting larger libraries and testing kitchen scenarios.
Implement this slice as the ordered PRs below. Each PR must receive independent
review, pass its listed verification and repository check/CI gate, and be
merged before work begins on the next PR. Keep each PR focused on its own user
flow; do not carry unreviewed follow-up work into the next branch.

Shared UI consumes application state/use cases, not SQLDelight queries. Keep
Android and desktop entry points separate while sharing practical UI and
presentation logic. Structured filters remain available offline even if a
future optional natural-language interpreter is added.

### PR 12.1 — Compose shells and navigation

- Establish shared Compose presentation conventions and navigation structure.
- Keep Android and Desktop launch/entry points platform-specific while making
  shared UI components available to both where practical.
- Add the basic app shell and reusable loading, empty, error, review, and
  success state presentation.

**Independent review and verification:** Review module boundaries and platform
entry points. Run the relevant shared and platform compilation/check tasks and
./gradlew check; launch both targets and verify the shell plus each common
state. Merge before PR 12.2.

### PR 12.2 — Recipe library, import, and review

- Browse saved recipes offline and view recipe details.
- Import through the existing Paprika, URL, and manual application workflows.
- Show per-record outcomes, field warnings, duplicate review, and manual
  correction; support editing saved recipes through the application layer.

**Independent review and verification:** Review that UI depends on application
workflows rather than database queries and that failures remain reviewable.
Run importer/application tests and ./gradlew check; exercise successful,
partial, failed, duplicate, and offline browse/edit flows on Android. Merge
before PR 12.3.

### PR 12.3 — Recipe library management and imported metadata

Complete the V1 recipe-library actions and preserve useful metadata as recipes
move from source import into local storage.

- Permanently delete a recipe after explicit confirmation. Trash and restore
  are post-V1 features.
- Organize recipes using All Recipes, Most Recent, custom categories, and
  Uncategorized views. Allow users to create, rename, apply, and remove
  category labels; removing a label must not delete its recipes.
- Record an imported-at timestamp when a URL, Paprika, or manual recipe is
  successfully saved. Most Recent is ordered newest-first by that timestamp;
  later edits do not change import recency. Legacy recipes with no known import
  time remain un-timestamped and follow timestamped entries; migration time is
  not presented as their import time.
- Capture and display prep, cook, and total times when a source supplies them.
  Preserve missing values as unknown; do not infer prep or total time from
  cook time. Retain distinct source-provided chilling/rest time where present.
- Preserve source images for offline recipe details. For URL imports, capture
  the Schema.org image URL and retain a local image copy when it can be fetched.
  For Paprika imports, copy the referenced archive photo into app-managed
  storage. Keep the original recipe URL and image source URL; make the displayed
  image open its source and provide a separate link to the original recipe.
  If an image cannot be fetched or copied, keep its source link and show a
  reviewable warning.
- Normalize duplicate yield values and avoid adding a second servings label in
  recipe details. Cover both new imports and legacy stored values: the cranberry
  sauce source shows one canonical value (6 servings), not “Serves 6, 6
  servings,” while meaningful ranges such as 4–6 servings remain intact.

Use the following pages as import acceptance cases without committing their
recipe text or image files:

- Sugar Spun Run Homemade Cranberry Sauce:
  https://sugarspunrun.com/homemade-cranberry-sauce/
  Expected source metadata: prep 5 minutes, cook 15 minutes, total 20 minutes,
  chilling 2 hours, and 6 servings. Chilling remains distinct from the stated
  total time.
- Ballymaloe Homemade Sausage Rolls:
  https://ballymaloefoods.ie/recipes/homemade-sausage-rolls-with-ballymaloe-original-relish/
  Expected page metadata includes 45 minutes cooking time and 8 servings;
  do not invent prep or total values when absent.
- Additional URL import cases:
  https://cooking.nytimes.com/recipes/1020828-lemony-shrimp-and-bean-stew
  https://food52.com/recipes/78419-sheet-pan-croquembouche
  Use small, self-authored structured-data fixtures for deterministic tests and
  live pages for manual smoke checks when accessible.

**Independent review and verification:** Review recipe deletion and category
use cases, metadata persistence/migrations, source-image ownership, and offline
display. Run importer, application, and database tests plus ./gradlew check.
On Android, verify deletion confirmation, category assignment/filtering,
Uncategorized and Most Recent results across URL/Paprika/manual imports, detail
times, offline image display and source links, image-failure review, and the
servings regression. Merge before PR 12.4.

### PR 12.4 — Kitchen setup and inventory

- Build and edit inventory across pantry, refrigerator, freezer, and custom
  locations, with presence-only, approximate, and exact tracking.
- Keep the canonical ingredient catalog separate from stocked inventory.
  Allow ingredient deletion only when no recipe, stock lot, or household rule
  refers to it.
- Add and remove equipment.
- Manage an explicit household-rule list. Users manually add an ingredient
  rule and choose Allergen, Avoid, or Dislike; stocked or imported ingredients
  do not become rules automatically. Allergen and Avoid are hard exclusions;
  Dislike is a soft ranking penalty. Allow deleting a rule independently from
  the canonical ingredient.
- For V1, these rules apply to the household as a whole.

**Independent review and verification:** Review that edits use application
use cases, preserve tracking/rule semantics, and reject unsafe ingredient
deletion. Run affected tests and ./gradlew check; exercise inventory add, edit,
move, and removal; ingredient/equipment/rule add and delete; and settings flows
on Android, including empty and error states. Merge before PR 12.5.

### PR 12.5 — Recommendations and shopping

- Capture recipe-level meal types and required equipment during recipe review.
  Use these fields in offline filters and equipment explanations; do not infer
  them from recipe text or categories.
- Ask what can be made using structured filters available offline.
- Show ranked recipes with matcher-grounded explanations for readiness, missing
  or uncertain ingredients, freshness, exclusions, and equipment constraints.
- Select eligible recipes, review missing ingredients, and create a locally saved
  shopping list. Allow checking items off and clearing the list. Require explicit
  confirmation before replacing an existing list and its checked state.

**Independent review and verification:** Review recommendation explanations
against matcher output; never present an unexplained score. Run matching,
application, shopping, and metadata migration and round-trip tests plus
./gradlew check. On Android, exercise metadata review, filtered and no-result
states, explanations, and shopping-list creation, check-off, clear, and
replacement. Verify the saved list and item check-off survive an app restart and
work offline. Merge before PR 12.6.

### PR 12.6 — Cooking and leftovers

- Start a non-mutating cooking session, confirm consumption explicitly, and
  optionally record leftovers.
- Keep browsing or starting a recipe from changing inventory.

**Independent review and verification:** Review explicit confirmation and
application transaction boundaries. Run cooking workflow tests and
./gradlew check; exercise cancel, confirm, quantity adjustment, and optional
leftover flows on Android, including persisted state after app restart. Merge
before PR 12.7.

### PR 12.7 — Desktop engineering workspace

- Provide Desktop flows for importing and inspecting larger recipe libraries
  and testing kitchen scenarios using the shared application/presentation
  boundary where practical.
- Keep Desktop focused on development and inspection rather than treating it
  as the V1 product target.

**Independent review and verification:** Review platform separation and shared
logic choices. Run Desktop/JVM tests and ./gradlew check; launch Desktop and
exercise library import/inspection and kitchen scenario flows.

### Slice 12 completion gate

After all PRs have been independently reviewed, verified, and merged, exercise
end-to-end user journeys on Android and engineering/import flows on Desktop.
Verify loading, empty, error, review, and success states throughout. Confirm
recipe delete/category/recency and metadata flows work offline, recommendations
show their reasons, and structured filters remain available offline.

---

## Slice 13 — Backup, offline acceptance, and V1 closeout

Make the local-first contract dependable before calling the first release
complete. The V1 release target is 1.0.0 after this slice passes its acceptance
gate.

### Components

- Versioned export/import for recipes, categories, import timestamps, timing
  metadata, image assets and their source links, inventory, rules, equipment,
  and cook/leftover history.
- Restore preview, validation, and explicit duplicate/conflict behavior.
- Data-integrity checks and documented migration/backup recovery procedures.
- End-to-end acceptance corpus spanning import, normalization, image/time
  metadata, category organization, inventory, recommendations, cooking,
  depletion, leftovers, and shopping.
- Clear privacy disclosure for optional network-backed URL and image retrieval
  and any future interpretation service.

Cloud sync, household sharing, external recipe discovery, barcode/photo/receipt
recognition, nutrition optimization, and generated recipes remain outside V1.

### V1 acceptance gate

- Core recipe, inventory, matching, cooking, and shopping workflows work
  offline.
- Paprika and URL/manual imports produce reviewable canonical recipes with
  preserved source attribution and available source images.
- Recipe deletion, category organization, imported-at Most Recent, and source
  prep/cook/total times work on Android.
- A missing source value stays unknown rather than being guessed. The cranberry
  sauce yield displays once as 6 servings.
- Reviewed corpora reach at least 95% Paprika import success and 95%
  ingredient normalization accuracy.
- The matching benchmark has zero known quantity-related false “Ready Now”
  outcomes.
- Recommendations explain their fit and constraints.
- Recipes, images, inventory, leftovers, and backup/restore survive app restart.
- No account or subscription is required for the core local experience.

---

## Post-V1 refinements and versioning

After Slice 13, the planned V1 release is 1.0.0. Backward-compatible feature
releases use the MINOR component (beginning with 1.1.0); backward-compatible
bug fixes use PATCH; incompatible changes to the declared public contract use
MAJOR, following [Semantic Versioning 2.0.0](https://semver.org/).

The following compatible refinements are deferred to 1.1.0 and later:

- Per-member Allergens, Avoid, and Dislike rules with meal participant context.
  An allergen hard-blocks a meal when the affected member is included; it does
  not block a single-member meal when that member is not joining.
- Ratings and Top Rated views.
- Favorites.
- Named Cookbook collections such as Thanksgiving, Christmas, and Hosting.
- Trash and restore instead of permanent deletion.
- List/Grid view switching.
- The full sort menu: Name, Rating, Most Recent, Prep Time, Cook Time, Total
  Time, and Servings, each ascending or descending. The V1 Most Recent view
  remains available; the later work adds it to the general sort menu.

Other compatible refinements can be scheduled into later MINOR releases as
their scope is agreed.

## When to activate reserved dependencies

The version catalog already records likely dependencies so versions remain centralized, but do not add them to modules until required:

- SQLDelight: Slice 9/persistence.
- Ktor: URL import, later.
- coroutines: when application/repository async boundaries exist.
- `kotlinx-datetime`: when civil dates enter the actual domain.

Keeping a version in the catalog is not permission to import the library everywhere.
