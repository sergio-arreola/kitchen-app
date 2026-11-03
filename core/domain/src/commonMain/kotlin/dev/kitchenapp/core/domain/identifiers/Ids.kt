package dev.kitchenapp.core.domain.identifiers

import kotlin.uuid.Uuid

/**
 * Typed identifier for durable domain entities (recipes, pantry items, ...).
 *
 * Wrapping [Uuid] in a distinct value class per entity kind prevents
 * accidentally mixing identifiers of different entities.
 */
@JvmInline
value class EntityId(val value: Uuid) {
    override fun toString(): String = value.toString()

    companion object {
        fun generate(): EntityId = EntityId(Uuid.random())
    }
}

/**
 * Typed identifier for work-in-progress "sketch" entities that never
 * become durable records (e.g. unsaved drafts).
 */
@JvmInline
value class SketchId(val value: Uuid) {
    override fun toString(): String = value.toString()

    companion object {
        fun generate(): SketchId = SketchId(Uuid.random())
    }
}
