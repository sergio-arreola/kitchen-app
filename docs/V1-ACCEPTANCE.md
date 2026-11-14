# V1 acceptance evidence

This is the evidence record for [Slice 13.4 and the V1 acceptance gate](NEXT-SLICES.md). It is a working checklist. **Not yet recorded** means no result has been entered here; it is not a pass. Do not declare V1 complete until every applicable gate has reviewed evidence.

## Candidate and evidence protocol

| Item | Evidence |
|---|---|
| Candidate commit and branch | Commit `a95ee72a131702b1c7e68ec68fc9fcb759b0f406` on `feat/slice-13-4-v1-offline-acceptance`; PR [#33](https://github.com/sergio-arreola/kitchen-app/pull/33). |
| Acceptance date, reviewer, Android device/emulator and API level | 2026-11-13; `sdk_gphone64_arm64`, Android 17 / API 37; primary-agent review complete, independent reviewer pending. |
| JVM/Gradle environment and network state | Gradle wrapper; deterministic JVM suites and `./gradlew check` passed. Emulator checks explicitly used Airplane mode for offline flows; network was restored afterward. |
| Corpus revision and expected-outcomes review | `slice13-import-corpus-r1` and `slice13-matching-corpus-r1`; expected outcomes are self-authored and explicit. Independent corpus review pending. |
| Earlier V1 exporter commit, fixture provenance and archive SHA-256 | `98b3315c7361be93b60273930d3dde71eea884ec`; `historical-v1-13-1.kitchenbackup`; SHA-256 `214ce178f11d531538799dd6de40cfb4230ec79bc4c06142c210bfff2cf8c657`; two generations matched. |
| Targeted commands and results | `:data:importers:jvmTest --tests '*Slice13ImportAcceptanceTest'` passed; `:core:application:jvmTest --tests '*RecipeLibraryWorkflowsTest'` passed; `:core:domain:jvmTest :testkit:jvmTest --tests '*Slice13MatchingAcceptanceTest'` passed; `:data:backup:jvmTest --tests dev.kitchenapp.data.backup.HistoricalV1BackupRestoreTest` passed. `./gradlew check` passed. |
| `./gradlew check` and required PR CI results | `./gradlew check` passed after the migration fix (198 tasks); PR #33 `CI/build` passed on commit `a95ee72`; Android Studio `androidTest` completed successfully. |
| Independent review and remaining limitations | Sol/Luna/Terra reported findings and primary-agent review is complete; independent reviewer pending. [ADR 0008](decisions/0008-retain-detached-cooking-history.md) records the accepted retention decision. The migration was fixed and verified on Android; manual Paprika import, custom-category create/rename, and detached-history export/restore after deletion remain unverified on-device. |

Use self-authored or redistributable fixtures. Do not include personal recipe
text, images, backup contents, or private file paths in committed evidence.
Identify each case and record its expected and observed outcome. Record the
candidate commit, commands, environment, network state, and whether each
result came from an automated test or an Android interaction. Preserve failed,
skipped, and inaccessible cases instead of counting them as passes. Repeat
qualifying checks against the final candidate if relevant code or fixtures
change.

## Acceptance matrix

| Gate | Evidence required | Status |
|---|---|---|
| Paprika import success ≥95% | Reviewed corpus; successful and successful-with-warnings recipe records / eligible valid recipe records, with counts, case IDs, warnings, and malformed records reported separately. | **Pass:** 40/40 eligible records (100%); 3 warning cases; 2 malformed excluded from denominator; 2 non-recipe entries skipped. |
| Ingredient normalization accuracy ≥95% | Reviewed ingredient cases through import, parsing, resolution, save, and reload; expected vs observed identities or review states, numerator/denominator, failures, and resolved coverage reported separately. | **Pass:** 26/26 (100%) per Paprika, URL, and manual adapter; 19/26 resolved, with remaining unknown/ambiguous cases explicitly reviewed; file-backed SQLDelight close/reopen passed. |
| Matching: zero known quantity-related false “Ready Now” results | Named cases for insufficiency, compatible and incompatible units, uncertain and presence-only amounts, multiple lots, and scaling; record readiness, explanations, and shopping gaps without filters hiding candidates. | **Pass:** 22 quantity matrix cases, zero false Ready; repeated-row, mixed-unit, optional-row, unknown-quantity regressions pass after sequential stock allocation fix. |
| URL, Paprika, and manual import | Reviewable canonical recipes, source attribution, available images, metadata, warnings, and failures; distinguish deterministic fixtures from live URL smoke checks. | **Partial pass:** deterministic adapters and save/reload tests pass; Android URL import of Sugar Spun Run saved the recipe/image and rendered offline. Android Paprika picker import not exercised in this pass. |
| Android recipe library | Deletion, categories, Most Recent, supplied timing values, unknown values, and the cranberry sauce yield displayed once as 6 servings. | **Partial:** cranberry yield, prep/cook/chilling/total times, source links/image, Most Recent order, and Uncategorized filter verified offline. Deleting the cooked synthetic recipe succeeded after the migration fix; custom-category creation/rename not manually exercised. |
| Offline core workflows | After content is saved, exercise recipes, inventory, rules, equipment, matching/recommendations with explanations, cooking/leftovers, and shopping with networking disabled. | **Partial pass:** offline browse/details/image, inventory, allergen exclusion, recommendations, cook/leftover confirmation, shopping, and recipe deletion after the migration fix were exercised. Equipment remains unverified on-device. |
| Restart and restore persistence | Verify recipes, images, inventory, rules, equipment, cooking/leftover history, and shopping-list items and checked states after restart and restore. | **Partial pass:** populated offline backup restored; recipe/image, checked shopping item, and one-serving leftover inventory survived cold launch. Preview and restore counts showed one cook event and one leftover; direct post-restart history display is not available in current UI. |
| Recipe deletion with cooking history | Confirm a deleted recipe's completed cook events, consumption/undo state, and leftovers remain detached without undoing the cook; verify migration and backup/export/restore preserve them without recreating the recipe. Confirm an active cooking session is not discarded. | **Partial pass:** JVM persistence/migration/backup tests pass; Android schema migration succeeded over existing data, recipe deletion succeeded after the fix, and its leftover inventory remained. Detached-history export/restore after deletion and active-session behavior are not manually exercised on-device. |
| Offline backup and recovery | Export, validate, preview, cancel, confirm replacement, and recover with networking disabled; record invalid/unsupported and active-cook behavior at the layer actually exercised. | **Pass for exercised paths:** exported/restored a populated backup offline, observed replacement counts, confirmed Replace All, and verified restored state after cold launch. Invalid, unsupported, stale-preview, and active-cook cases rely on existing automated coverage; not repeated on-device here. |
| Earlier supported backup | Restore a populated V1 archive from the earlier 13.1 exporter. Record its producing commit, self-authored input, SHA-256, current candidate, and observed result. | **Pass:** exact 13.1 fixture validated and replaced data in JVM SQLite test; restored through Android picker offline; synthetic recipe/image and checked shopping state appeared. |
| Local database migration | Identify source and target schema versions and record migration test and recovery results separately from archive compatibility. | **Automated checks passed** within `./gradlew check`; migration coverage remains separate from V1 archive compatibility. |
| No account or subscription for core local use | Record the on-device offline workflows exercised without either. | **Pass for exercised flows:** no account or subscription was used for offline recipe, inventory, recommendation, cook, shopping, or backup/restore checks. |
| Recovery and privacy documentation | Independently review [backup and restore guidance](BACKUP-RESTORE.md), including unencrypted archives, network retrieval, protected storage, and failure recovery. | Documentation updated; independent review pending. |
| Repository and acceptance checks | Record targeted tests, Android acceptance, `./gradlew check`, required CI, and unresolved failures. | Targeted JVM tests and `./gradlew check` pass after the migration fix; Android assemble and existing-data migration pass. PR CI and independent review remain pending. |

## Android interaction log (2026-11-13)

Device: `sdk_gphone64_arm64`, Android 17 / API 37. All Kitchen content used in this pass was synthetic, except the public Sugar Spun Run recipe URL. Network was turned on for that import, then Airplane mode was enabled for offline details, backup/restore, and the historical archive test; Wi-Fi/mobile connectivity was restored at the end.

- Imported and saved Sugar Spun Run Homemade Cranberry Sauce online. Offline detail rendered the retained image and both source links; it showed servings once (`6 servings`), prep 5 min, cook 15 min, chilling 2 hr, and total 20 min.
- Saved `Slice13.4 Test Recipe` manually. Most Recent placed the later URL import first; Uncategorized showed the manual test recipe.
- Added presence-only egg stock in Pantry. Recommendations showed Ready Now; a temporary egg Allergen rule excluded the recipe and explained the household-rule block. The test rule was removed.
- Toggled/undid the cook checklist without changing inventory. End Cook then consumed the synthetic Pantry egg and recorded `Slice13.4 Test Leftovers` as 1 serving in the Refrigerator. Kitchen showed the new leftover inventory lot.
- Checked the shopping item and cold-launched; it remained checked. Exported a populated backup offline, unchecked the item, restored with Replace All, and cold-launched again. The app restored both recipes, the offline image/detail, the checked item, and the leftover inventory. Restore preview included one cooking event and one leftover.
- Imported the earlier 13.1 V1 archive offline using the system picker. Preview showed one recipe, image, inventory lot, shopping item, cooking event, and leftover. Replace All succeeded; `Synthetic bread`, its image, and the checked shopping item appeared. The populated 13.4 backup was restored afterward and cold-launched again.
- The first delete attempt for `Slice13.4 Test Recipe` after its cook event returned `FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)`. Migration 8 was corrected to clear staged child rows when SQLite foreign-key enforcement is off during migration. After installing the fix over existing emulator data, deletion succeeded, the recipe disappeared from the library, and its `egg` leftover remained as `1 serving · Refrigerator` in Kitchen. Automated tests separately verify retained cook/undo/leftover history and migration behavior.
- Not manually exercised on this device: Paprika archive import, custom-category create/rename, invalid/unsupported archive, active-cook restore blocking, or export/restore after deleting a cooked recipe. Existing automated tests cover archive rejection, active-session policy, and detached-history backup/restore; Android Paprika picker and post-delete archive paths remain unverified.

## Corpus results

- Paprika: **40/40 (100%)** valid recipe records; 3 warnings, 2 malformed records reported outside the denominator, 2 non-recipe entries skipped; failed valid case IDs: none. Corpus `slice13-import-corpus-r1`.
- Normalization: **26/26 (100%)** exact reviewed outcomes per adapter (Paprika, URL, manual); 19/26 resolved, 7 explicitly unresolved/ambiguous and review-assigned; failed case IDs: none.
- Matching: 22 matrix cases with **0** false Ready; four multi-line allocation regressions pass (same-unit, compatible-unit, optional, unknown-quantity). Corpus `slice13-matching-corpus-r1`.
- Historical V1 archive: exporter commit `98b3315c7361be93b60273930d3dde71eea884ec`; SHA-256 `214ce178f11d531538799dd6de40cfb4230ec79bc4c06142c210bfff2cf8c657`; generated twice with identical bytes.

## Closeout decision

**Pending.** Record the independent reviewer, evidence references, unresolved
limitations, and decision here. The target version is 1.0.0 only after the
Slice 13 and V1 acceptance gates pass. Version changes, tagging, and publication
require their own authorized workflow.
