# Historical V1 backup fixture provenance

- Fixture: `historical-v1-13-1.kitchenbackup`
- SHA-256: `214ce178f11d531538799dd6de40cfb4230ec79bc4c06142c210bfff2cf8c657`
- Producing Kitchen commit: `98b3315c7361be93b60273930d3dde71eea884ec` (Slice 13.1 exporter)
- Archive export timestamp: `2026-01-04T12:00:00Z`
- Generator: `historical-v1-13-1-generator.kt.txt` beside this file

The fixture was produced by adding the generator as a JVM test in a detached
checkout of the producing commit and running the command below. The exporter
and its dependencies came from that commit; the current exporter was not used.
The input is entirely synthetic, with fixed IDs and times. It includes a valid
decodable PNG, recipe and source metadata, a category, ingredients, inventory,
allergen and equipment, a checked shopping item, cooking history, and a
leftover whose ingredient and quantity are non-null as required by 13.1.

To reproduce, create a detached checkout of the producing commit, place the
adjacent generator text in its `data/backup/src/jvmTest/kotlin/dev/kitchenapp/data/backup/`
directory as `HistoricalV1FixtureGenerator.kt`, then run from that checkout:

```sh
KITCHEN_HISTORICAL_V1_OUTPUT=/private/tmp/historical-v1-13-1.kitchenbackup ./gradlew :data:backup:jvmTest --tests dev.kitchenapp.data.backup.HistoricalV1FixtureGenerator.generate --rerun-tasks
```

Choose an output path that does not already exist; the generator refuses to
overwrite one. Compare the resulting SHA-256 with the value above before using
it as this fixture. This is one populated V1 compatibility case; it does not
show that every archive the earlier exporter could emit meets the current
validator's size, image, or semantic checks. Local database schema migration is
a separate compatibility path.

The generator test passed twice at commit
`98b3315c7361be93b60273930d3dde71eea884ec` in the same environment, producing
the same SHA-256 both times. The working-tree validation and SQLite replacement
test passed with
`./gradlew :data:backup:jvmTest --tests dev.kitchenapp.data.backup.HistoricalV1BackupRestoreTest`.
Record the final-candidate result in the V1 acceptance evidence.
