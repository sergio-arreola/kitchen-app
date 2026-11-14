# Backup and restore contract

This document describes the local backup service behavior and its Android
workflow. PR 13.2 supplies validation, preview, and transactional restore;
PR 13.3 adds the Android file picker and screens for reviewing and confirming
a restore.

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

Under [ADR 0008](decisions/0008-retain-detached-cooking-history.md), a completed
cook event may retain the ID of a permanently deleted recipe. V1 validation
must allow that detached historical reference while still validating the ID and
the other required references, including each leftover's cook event. A backup
made after deletion preserves the detached history and leftovers; it does not
recreate the deleted recipe. A backup made before deletion can still contain
and restore the recipe. Earlier builds that required every cook-event recipe ID
to resolve may reject an archive containing detached history.

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

The Android backup screen opens the system document picker to export or import
a `.kitchenbackup` file. It shows incoming and current counts, all preview
warnings, and explicit **Replace all local backup-managed data** and **Cancel
restore** actions. Picker cancellation, invalid files, and failed or stale
restores do not apply data. If local state changes after preview, the app
refreshes the preview and requires another confirmation.

## Atomic application

Image assets are separate entries in the archive. Kitchen stores recipe image
bytes and media types in the SQLite `recipe_image` table. The service verifies
image asset hashes and checks that each image signature matches its declared
type, then inserts image bytes as SQLite BLOBs in the same transaction as the
other restored records. No filesystem image cleanup is needed. The transaction
also clears legacy `inventory_transaction` records after the warning is shown. Failure or cancellation before
commit must leave the existing database and images intact, without partial
records.

Android reads selected files with a 128 MiB streaming limit before allocating
the archive byte array. Export opens the selected document in write-and-truncate
mode and reports success only after the stream closes. Export, validation,
preview, and restore work without an account or network connection. Before
applying a confirmed restore, the service rechecks the archive and snapshot
fingerprints, local state fingerprint and record counts, legacy transaction
count, and active-session state against the preview. If any value changed, it
requires a refreshed preview and confirmation. Picker state survives Activity
configuration changes; if process recreation loses the pending operation, the
app reports that it was interrupted and does not apply a restore.

## Export and protect a backup

1. Open **Backup** in Kitchen and choose **Export backup**.
2. In the Android file picker, choose a destination and a new filename. Keep an
   existing backup separate: writing to a selected file can truncate it, and a
   failed export may leave that destination incomplete.
3. Wait for **Backup saved** before relying on the new file. Keep a separate
   copy in protected storage that you can access when recovering your device.

A `.kitchenbackup` is an ordinary, unencrypted ZIP containing recipe and
kitchen data, including locally retained images and source links. Anyone with
access to the file can read its contents. Protect the file and any copies or
cloud-provider storage you choose. The manifest's SHA-256 hashes detect
inconsistent contents; they do not encrypt the archive or authenticate its
creator. The picker may offer storage providers outside the device; their
handling of the selected file is governed by those providers and may require
a network connection.

## Restore a backup

1. If possible, export and protect a separate backup of the current kitchen
   first. End any active cooking session before restoring; active sessions are
   not included in V1 backups.
2. Open **Backup**, select **Import backup**, and choose the file in the system
   picker. Wait for validation and the restore preview.
3. Compare the incoming and current counts and read the warnings. Choose
   **Cancel restore** to leave the current data unchanged, or explicitly choose
   **Replace all local backup-managed data** to apply the backup. Restore does
   not merge individual records.
4. After success, check the restored recipes and kitchen data. Some historical
   cooking events may no longer be undoable when their earlier inventory
   locations are missing; the preview warns when this applies.

If the picker is canceled, validation fails, or the preview becomes stale, no
restore is applied. Review a refreshed preview and confirm again if the app
requests it. If a restore fails, retain both the original backup and any
separate pre-restore copy. A failed database transaction leaves the previous
state intact. An unsupported or corrupt archive needs a supported, intact copy;
do not delete the only copy while investigating an error.

## Archive compatibility and database migration

The backup archive format and the app's local database schema are separate
versions. The initial and currently supported archive format is V1; the app
rejects unsupported archive versions. A V1 file produced by an earlier build
is a useful compatibility case, but its success must be verified with that
actual file. The Slice 13.4 acceptance record identifies the earlier exporter
commit, the self-authored fixture, the archive hash, and the restore result.
This does not imply support for an older archive format.

The app also has SQLDelight migrations for local databases. A successful archive
restore does not prove an old on-device database migration, and a successful
schema migration does not prove an older archive can be restored. Test and
record those paths independently.

Before updating, export a separate backup if the existing app still opens. If
the app cannot open after an update, preserve the device and any existing
backup; do not clear app data or uninstall as a first recovery step. A working
build that supports the backup format can import a known-good copy using the
restore steps above. Without a usable backup, importing cannot reconstruct data
that is no longer available; retain the existing app data for diagnosis.

## Network and privacy

A URL recipe import sends an HTTP or HTTPS request to the supplied page. During
import review, Kitchen also requests a source image when the candidate has an
image URL. Recipe and image hosts can receive the requested URL and connection
metadata such as the device's IP address. Network intermediaries can observe
connection metadata; HTTP does not protect the request or response in transit.
Opening a recipe or
image source link delegates to another app, which may contact that destination.
Paprika archive and manual import use local input; opening an external source
link is a separate action.

Saved source URLs and successfully retained image bytes are stored with the
recipe and can be included in backups. If an image cannot be retained, its
source link can remain without an offline image copy. After content has been
saved, core local workflows and backup export/import do not require a network
connection or an account.
