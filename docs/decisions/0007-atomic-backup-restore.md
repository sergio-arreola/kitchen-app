# ADR 0007: Atomic backup restore

## Status
Accepted

## Context
A versioned local backup represents a complete view of the backup-managed Kitchen data. Applying it must not leave recipes, images, inventory, cooking history, or shopping state partially replaced if validation or persistence fails. V1 does not archive an active cooking session or the legacy inventory transaction table.

## Decision
Restore V1 backups as an explicit all-or-nothing replacement of backup-managed local data. Preview incoming and current record counts and warnings first; require a separate Replace All decision before applying. Do not merge individual records or remap conflicting identifiers in V1.

Validate the archive before any database mutation. Block restore while a cooking session is active. Warn that legacy inventory transaction rows will be cleared because they are not included in V1 backups.

Apply all changes in one SQLite transaction. Recipe image bytes are stored in SQLite, so restore writes them as BLOBs in that transaction and performs no filesystem image writes. Bind the confirmation to the validated archive and current-state fingerprint, and reject application if the backup or local state changes after preview.

## Consequences
A failed restore leaves the prior database intact. The user must confirm replacement of all backup-managed local data, and can cancel without mutation. Restore cannot run during an active cook. V1 has no per-record merge or keep-both behavior; that requires a separately designed migration and identifier-remapping policy.
