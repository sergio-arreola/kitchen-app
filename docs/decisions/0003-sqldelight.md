# ADR 0003: SQLDelight for persistence

## Status
Accepted, dependency activation deferred

## Context
The application is local-first and requires explicit migrations, SQLite portability, and typed database access.

## Decision
Use SQLDelight when persistence is implemented. Do not activate the plugin merely for bootstrap scaffolding.

## Consequences
SQL remains visible and versioned. Database records must be mapped to domain objects rather than escaping into domain APIs.
