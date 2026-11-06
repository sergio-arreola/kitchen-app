package dev.kitchenapp.core.application

import dev.kitchenapp.core.domain.time.DomainLocalDate

class UndoCooking(private val repository: CookingRepository) {
    fun undo(eventId: String, undoneOn: DomainLocalDate): KitchenSnapshot? =
        repository.undo(eventId, undoneOn)
}
