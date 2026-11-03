# ADR 0002: Separate app entry points

## Status
Accepted

## Context
AGP 9 uses the Android-KMP library plugin for shared KMP libraries. Executable Android and desktop applications have different packaging concerns.

## Decision
Use `app:shared-ui` for shared Compose UI, `app:android` for the Android entry point, and `app:desktop` for the JVM desktop entry point.

## Consequences
Business/UI logic is shared without forcing platform packaging configuration into the same module.
