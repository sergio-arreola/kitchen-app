# Backup restore service contract

This document describes the intended service behavior for PR 13.2. The Android
file picker and the screens for reviewing and confirming a restore are planned
for PR 13.3.

## Validate before changing data

The restore service reads a `.kitchenbackup` ZIP locally. Version 1 is the
currently defined format; unsupported format identifiers or versions must
produce an actionable error. Validation must finish before the service changes
stored data.

Validation covers the manifest and data document, required archive entries,
entry sizes and SHA-256 hashes, image references and paths, record identifiers,
and references between records. It rejects malformed or incomplete archives,
duplicate entries or IDs, unsafe paths, missing assets, and invalid data. Archive
reading must have limits so an oversized or compressed input cannot consume
unbounded resources. An error identifies the problem without exposing recipe
content unnecessarily.

## Preview and confirmation

A valid backup produces a preview with incoming and local record counts and a
clear warning that restoration replaces all local backup-managed data. The user
must explicitly choose **Replace all local backup-managed data** or cancel.
Version 1 does not offer per-record merge or keep-both choices. The service must
never apply an unresolved or unconfirmed restore.

A restore is blocked while a cooking session is active because version 1 does
not include that session. The preview must also warn that legacy
`inventory_transaction` records will be cleared: version 1 does not back them up.
The preview also warns if restored cook events cannot be undone because a
historical inventory location is absent. Shopping-list contents, checked states,
and source recipe links are restored from the backup as part of the replacement.

PR 13.2 supplies validation, preview, and application services. PR 13.3 will
show the preview and warnings, present the replacement or cancel choice, and
obtain explicit confirmation.

## Atomic application

Image assets are separate entries in the archive. Kitchen stores recipe image
bytes and media types in the SQLite `recipe_image` table. The service verifies
image asset hashes and checks that each image signature matches its declared
type, then inserts image bytes as SQLite BLOBs in the same transaction as the
other restored records. No filesystem image cleanup is needed. The transaction
also clears legacy `inventory_transaction` records after the warning is shown. Failure or cancellation before
commit must leave the existing database and images intact, without partial
records.

Export, validation, preview, and restore work without an account or network
connection. Before applying a confirmed restore, the service rechecks the
archive and snapshot fingerprints, local state fingerprint and record counts,
legacy transaction count, and active-session state against the preview. If any
value changed, it requires a refreshed preview and confirmation.
