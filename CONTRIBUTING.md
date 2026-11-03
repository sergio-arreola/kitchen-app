# Contributing

This project uses short-lived branches and pull requests into `main`.

## Baseline workflow

1. Create an issue when the change is behaviorally significant.
2. Branch from current `main`.
3. Keep the change focused.
4. Add or update tests for changed domain behavior.
5. Run `./gradlew check` before opening a PR.
6. Use DCO sign-off on every commit: `git commit -s`.
7. Open a PR and describe the behavior changed, tests added, and any architecture impact.

## Domain behavior rule

A bug fix to parsing, normalization, quantity handling, readiness, or ranking must first gain a regression fixture that demonstrates the bug.

## Architecture rule

Changes to module boundaries, persistence strategy, transaction behavior, or licensing require an ADR under `docs/decisions/`.
