# Contributing

This project uses short-lived branches and pull requests into `main`.

## Baseline workflow

1. Create an issue when the change is behaviorally significant.
2. Before starting a branch, run `git fetch origin` and branch from the fetched `origin/main` (for slice work, for example, `git switch -c feat/slice-7-quantity-evaluation origin/main`). If using local `main`, fast-forward it first with `git switch main` and `git pull --ff-only origin main`. Use a matching `fix/`, `docs/`, or `chore/` prefix for other focused work.
3. Keep the branch focused on one implementation slice or one independently reviewable part of a large slice. It is fine to make several checkpoint commits on that branch.
4. Open a PR when that slice or part is complete and its acceptance gate is ready. Do not wait to bundle unrelated slices into one PR.
5. Add or update tests for changed domain behavior.
6. Run `./gradlew check` before opening a PR.
7. Use DCO sign-off on every commit: `git commit -s`.
8. Open a PR into `main` and describe its slice, behavior, verification, and architecture impact. Direct pushes to `main` are blocked.
9. Wait for the required GitHub Actions `build` check to pass on the latest PR commit before merging. If you push another commit, wait for the updated check.
10. After confirming the PR is merged, switch to `main` and fast-forward it with `git pull --ff-only origin main`. Then delete the local topic branch with `git branch -d <branch>`.

### Pull request size and cadence

Use one PR per completed slice as the default. A slice may use multiple commits, but keep the PR centered on that slice and its acceptance gate. Split a large slice into smaller PRs only when each part can be reviewed, verified, and merged independently. Keep dependent follow-up work in a later PR; avoid combining unrelated slices just to reduce PR count.

## Domain behavior rule

A bug fix to parsing, normalization, quantity handling, readiness, or ranking must first gain a regression fixture that demonstrates the bug.

## Architecture rule

Changes to module boundaries, persistence strategy, transaction behavior, or licensing require an ADR under `docs/decisions/`.
