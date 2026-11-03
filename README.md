# Kitchen App (working codename)

Local-first recipe, kitchen inventory, and meal-decision application. Android is the first production target; Desktop/JVM is the development and inspection target.

> `kitchen-app` and `dev.kitchenapp` are working identifiers. Rename them before a public store release if the final product name changes.

## Bootstrap status

This repository intentionally contains architecture and build scaffolding only. Domain behavior, persistence schema, importer logic, and production UI are implemented in later slices.

## Required local tooling

- JDK 17 or newer (CI uses JDK 17)
- Android Studio with Kotlin Multiplatform support, or IntelliJ IDEA with the Kotlin Multiplatform plugin
- Android SDK API 37
- Gradle Wrapper generated and committed (see `docs/BOOTSTRAP.md`)

## First verification

After generating the Gradle Wrapper:

```bash
./gradlew --version
./gradlew check
./gradlew :app:android:assembleDebug
./gradlew :app:desktop:run
./gradlew :tools:dev-cli:run
```

See `docs/BOOTSTRAP.md` for the exact setup sequence and `docs/NEXT-SLICES.md` for guided implementation of the domain foundation and Paprika inspection harness.

## License

SPDX-License-Identifier: MPL-2.0

