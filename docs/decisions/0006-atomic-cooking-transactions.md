# ADR 0006: Atomic cooking transactions

## Status
Accepted

## Context
Starting or browsing a recipe must not change kitchen stock. A user-confirmed completion must update stock, record actual consumption, and optionally save leftovers together. The operation must be reversible without replacing unrelated kitchen state.

## Decision
Keep cooking lifecycle orchestration in the application layer and persistence in the database layer. Starting cooking is non-mutating; only explicit completion creates a cook event.

Persist completion as one SQLite transaction containing the updated kitchen snapshot, cook event, actual consumed quantities, pre-completion snapshots of affected inventory lots, and optional recipe-linked leftovers. Undo is a separate transaction: restore only affected lots, remove leftovers created by that event, and record that the event was undone.

## Consequences
A failed completion or undo leaves both inventory and event records unchanged. Undo preserves the cook event as history and can run only once. It requires the original inventory locations to remain available. Leftovers are stored as inventory lots so existing inventory and suggestion logic can use them.
