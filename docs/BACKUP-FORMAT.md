# Kitchen backup format

Kitchen backups use a ZIP container with the `.kitchenbackup` extension.

## Version 1

The archive contains:

- `manifest.json`: format identifier, format version, export time, and hashes
  and byte sizes for the data document and each image asset.
- `data.json`: recipes and their full source metadata, canonical ingredients,
  categories, kitchen inventory and locations, household rules, equipment,
  shopping list items and checked states, cook history, and leftover records.
- `images/<recipe-id>.<extension>`: locally retained recipe image bytes.

The manifest format identifier is `dev.kitchenapp.backup`. The initial format
version is `1`. Data records retain their stable IDs; the payload references
image assets by normalized archive-relative paths. SHA-256 values are lowercase
hex strings and cover the exact uncompressed bytes stored for each payload or
asset.

Writers sort unordered collections and archive paths for repeatable output.
Recipe ingredient order, recipe category order, and shopping list creation time
are retained. Backups are generated from one local database transaction and
require no network access. Personal recipe content must not be committed as
fixtures.

Future format versions must preserve the version 1 meaning or increment the
manifest version. Restore validation and conflict behavior are defined in
PR 13.2.
