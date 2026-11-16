# ADR 0008: Retain cooking history after recipe deletion

## Status

Accepted

## Context

A completed cook and its leftovers describe kitchen activity and food that may
still be in inventory. Permanently deleting a recipe should not erase that
history or implicitly undo the cook. The original recipe may no longer exist
when the history is viewed, exported, or restored. An active cooking session,
however, still depends on its recipe.

## Decision

When a user confirms permanent deletion, remove the recipe and its owned data,
including its image and recipe-specific ingredient, category, meal-type, and
equipment associations. Preserve canonical ingredients, current inventory,
completed cook events, their recorded consumption, prior inventory and undo
state, and leftover records. Deleting a recipe does not undo a cook or restore
its consumed inventory.

A completed cook event retains the original recipe ID as a historical
reference. That reference can point to a recipe that no longer exists and must
not require a live recipe row. The application must not recreate a deleted
recipe merely to satisfy the reference. Recipe details and image are not
preserved as a history snapshot by this decision.

The separate relationship from leftovers to their cook event remains valid and
is still checked. An active cooking session continues to require its recipe;
attempting to delete that active recipe must not discard or end the session.

V1 backup and restore preserve detached cook events and their leftovers. A
valid archive may therefore contain a cook event whose recipe ID has no matching
recipe in the archive. Continue validating the ID and all other required
references, including each leftover's cook event. Restore must not create a
placeholder recipe, and an archive made after deletion cannot reconstruct the
deleted recipe. Schema migrations must preserve existing cooking history and
its dependent records.

No manual, scheduled, or automatic history-purge policy is implemented by
this decision. Any future purge policy must be explicit, authorized, and
tested, including its effects on leftovers, inventory, and backups. This
retention rule governs recipe deletion; existing explicit cook undo and backup
Replace All operations retain their separate behavior.

## Consequences

History and leftovers survive recipe deletion, but the deleted recipe's details
may no longer be available from the event. A backup made before deletion can
still contain the recipe; protect or remove that backup separately according to
the user's wishes.

The V1 wire fields do not change, but accepting detached recipe IDs broadens
the validation semantics. Earlier builds that required every cook-event recipe
ID to resolve can reject a V1 archive containing detached history. The current
supported restore path must be verified with both attached and detached history;
do not infer that older readers accept the broadened case.
