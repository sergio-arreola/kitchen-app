# ADR 0001: Kotlin Multiplatform

## Status
Accepted

## Context
Core recipe, inventory, and matching logic must be portable across Android, desktop, and a later iOS target.

## Decision
Use Kotlin Multiplatform for domain/application/data libraries, with Android as the first production platform and JVM desktop as the development target.

## Consequences
Platform APIs must remain behind adapters. Shared modules cannot depend directly on Android UI or filesystem APIs.
