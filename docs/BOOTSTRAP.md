# Repository Bootstrap Guide

This is the exact setup order for the repository scaffold.

## 1. Install prerequisites

Use a current Android Studio release that supports AGP 9.2 and install the Kotlin Multiplatform plugin. The project is pinned to JDK 17 for reproducible CI, although newer JDKs may also run Gradle.

Install Android SDK Platform 37 and the SDK tools Android Studio requests during sync.

Verify Java:

```bash
java -version
```

## 2. Generate and commit the Gradle Wrapper

The source package does not fabricate `gradle-wrapper.jar`; generate the official Wrapper locally once.

If `gradle` is already available:

```bash
./scripts/init-wrapper.sh
```

Otherwise install a temporary system Gradle through SDKMAN or Homebrew, then run the script. The script pins the project Wrapper to Gradle 9.4.1. Once these files exist, commit them:

```text
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
```

After that, use `./gradlew`, not the system Gradle installation.

## 3. License

`LICENSE` contains the unmodified official MPL-2.0 license text from Mozilla. The project metadata records `SPDX-License-Identifier: MPL-2.0` in the README.

## 4. Open the project

Open the repository root, not an individual module, in Android Studio or IntelliJ IDEA.

Allow Gradle sync to complete. If the IDE proposes upgrading AGP independently, decline until compatibility is reviewed. Versions are deliberately centralized in `gradle/libs.versions.toml`.

## 5. Confirm module graph

Expected modules:

```text
core:domain
core:engine
core:application
data:database
data:importers
testkit
tools:dev-cli
app:shared-ui
app:android
app:desktop
```

The two application entry points are separate from shared Compose UI. This follows the current AGP 9/KMP structure: KMP libraries use `com.android.kotlin.multiplatform.library`; the Android executable remains its own `com.android.application` module.

## 6. First command-line verification

```bash
./gradlew --version
./gradlew projects
./gradlew check
```

Expected: every module configures and the bootstrap domain smoke test passes.

## 7. Verify developer CLI

```bash
./gradlew :tools:dev-cli:run --args="doctor"
```

Expected:

```text
Kitchen developer CLI: bootstrap OK
```

## 8. Verify desktop

```bash
./gradlew :app:desktop:run
```

Expected: a desktop window containing `Kitchen bootstrap OK`.

## 9. Verify Android

Either run the `app:android` configuration in Android Studio or build from the shell:

```bash
./gradlew :app:android:assembleDebug
```

Install/run on an emulator or device and confirm the same placeholder UI appears.

## 10. Initialize Git

If this folder is not already a repository:

```bash
git init
git add .
git commit -s -m "chore: bootstrap Kotlin Multiplatform repository"
```

The `-s` flag applies the DCO sign-off expected by `CONTRIBUTING.md`.

## 11. Create the remote repository

Create an empty GitHub repository. Do not ask GitHub to generate a README, `.gitignore`, or license because this scaffold already owns those files.

Then:

```bash
git branch -M main
git remote add origin <YOUR-REPOSITORY-URL>
git push -u origin main
```

## 12. Protect `main`

In GitHub branch/rules settings, require:

- pull request before merge;
- CI status check;
- branch up to date before merge once multiple contributors are active;
- no force pushes to `main`;
- no branch deletion.

Do not require multiple human approvals while you are the only maintainer; that would block your own work.

## 13. Verify CI

After the first push, CI should run:

```text
./gradlew check
:app:android:assembleDebug
:app:desktop:compileKotlin
:tools:dev-cli:installDist
```

If local builds pass and CI does not, fix environment assumptions rather than weakening the CI gate.

## 14. Tag the bootstrap checkpoint

Once local and CI checks pass:

```bash
git tag -a bootstrap-v0.1 -m "Repository bootstrap v0.1"
git push origin bootstrap-v0.1
```

That is the end of Slice 0.
