# Kitchen App (working codename)

Local-first recipe, kitchen inventory, and meal-decision application. Android is the first production target; Desktop/JVM is used for development and inspection.

> `kitchen-app` and `dev.kitchenapp` are working identifiers. Rename them before a public store release if the product name changes.

## Current state

The repository has completed its domain foundation, Paprika archive inspection and recipe-draft import, and ingredient-line parsing slices. It includes shared domain/parser tests and JVM importer tests.

The Android and desktop entry points currently display a placeholder. Recipe persistence, the deterministic kitchen engine, application workflows, and production UI are not implemented yet. See [the architecture map](docs/architecture/module-map.md), [the implementation history and slice gates](docs/NEXT-SLICES.md), and [the bootstrap setup guide](docs/BOOTSTRAP.md).

## Local development

Requirements: JDK 17 or newer, Android Studio with Kotlin Multiplatform support, and Android SDK Platform 37 for Android builds.

Useful commands:

```bash
./gradlew check
./gradlew :app:android:assembleDebug
./gradlew :app:desktop:run
./gradlew :tools:dev-cli:run --args="inspect-paprika path/to/export.paprikarecipes"
```

The Paprika inspection command reports archive and JSON structure. It does not output scalar recipe values.

## License

SPDX-License-Identifier: MPL-2.0
