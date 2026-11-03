# Paprika fixtures

## Public fixture (committed)

Self-authored, redistributable recipe JSON lives in
`data/importers/src/jvmTest/resources/paprika-fixture/recipes/` and is zipped +
gzipped at test time by `PaprikaFixture` (jvmTest). Cases covered:

- plain recipe
- recipe with photo (photo stored as a separate plain entry)
- fractional quantities (`1 1/2`, `1/2`, `3/4`)
- Unicode fractions (`½`, `⅓`, `¼`)
- categories
- notes
- source URL
- multiline directions
- missing optional metadata

All content is invented; no real or copyrighted recipes.

## Private fixture (never committed)

Export a small `.paprikarecipes` archive from your own Paprika library for
format validation against real data. Keep it outside the repository and run:

```text
./tools/dev-cli/build/install/dev-cli/bin/dev-cli inspect-paprika /path/to/export.paprikarecipes
```

Only the resulting inspection report (schema facts, no recipe text) should ever
be shared or quoted in the repo.
