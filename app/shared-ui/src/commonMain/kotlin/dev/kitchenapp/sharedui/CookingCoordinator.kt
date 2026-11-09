package dev.kitchenapp.sharedui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kitchenapp.core.application.ActiveCookingSession
import dev.kitchenapp.core.application.ActiveCookingSessionRepository
import dev.kitchenapp.core.application.CompleteCooking
import dev.kitchenapp.core.application.ConsumedInventory
import dev.kitchenapp.core.application.CookHistoryEntry
import dev.kitchenapp.core.application.CookingRepository
import dev.kitchenapp.core.application.DismissCookingReturnPrompt
import dev.kitchenapp.core.application.DismissLeftoverFollowUp
import dev.kitchenapp.core.application.FirstReturnState
import dev.kitchenapp.core.application.GetActiveCookingSession
import dev.kitchenapp.core.application.GetCookingHistory
import dev.kitchenapp.core.application.KitchenRepository
import dev.kitchenapp.core.application.KitchenSnapshot
import dev.kitchenapp.core.application.MarkCookingAway
import dev.kitchenapp.core.application.MarkCookingReturned
import dev.kitchenapp.core.application.PreparedLeftover
import dev.kitchenapp.core.application.RecipeRepository
import dev.kitchenapp.core.application.RecordLeftoversForCookEvent
import dev.kitchenapp.core.application.ResumeCooking
import dev.kitchenapp.core.application.StartCooking
import dev.kitchenapp.core.application.UpdateCookingChecklist
import dev.kitchenapp.core.domain.identifiers.RecipeIngredientId
import dev.kitchenapp.core.domain.recipe.Recipe
import dev.kitchenapp.core.domain.time.DomainLocalDate
import kotlinx.datetime.TimeZone

class CookingWorkflows(
    val sessions: ActiveCookingSessionRepository,
    val recipes: RecipeRepository,
    val kitchen: KitchenRepository,
    val cooking: CookingRepository,
)

internal fun shouldShowCookingReturnPrompt(active: ActiveCookingSession?): Boolean =
    active?.let { !it.isAway && it.firstReturnState == FirstReturnState.PROMPTED } == true

internal class CookingCoordinator(
    private val workflows: CookingWorkflows,
    private val platform: CookingPlatform,
) {
    var active by mutableStateOf<ActiveCookingSession?>(null)
        private set
    var recipe by mutableStateOf<Recipe?>(null)
        private set
    var snapshot by mutableStateOf(KitchenSnapshot())
        private set
    var history by mutableStateOf<List<CookHistoryEntry>>(emptyList())
        private set
    var showPrompt by mutableStateOf(false)
        private set
    var checkInMinutes by mutableStateOf<Int?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val focusAllowed: Boolean
        get() = active?.let {
            !it.isAway && !it.resumeRequired &&
                (it.firstReturnState == FirstReturnState.NOT_RETURNED ||
                    it.firstReturnState == FirstReturnState.CONFIRMED)
        } == true

    fun refresh() {
        try {
            active = workflows.sessions.getActive()
            showPrompt = shouldShowCookingReturnPrompt(active)
            recipe = active?.let { workflows.recipes.get(it.recipeId) }
            snapshot = workflows.kitchen.load()
            history = GetCookingHistory(workflows.cooking).get()
            checkInMinutes = active?.let { platform.checkInMinutes(it.sessionId) }
        } catch (e: Exception) {
            error = e.message ?: "Cooking data could not be loaded."
        }
    }

    fun start(recipe: Recipe): Boolean = attempt {
        StartCooking(workflows.sessions).start(recipe)
        refresh()
    }

    fun toggle(id: RecipeIngredientId, checked: Boolean) {
        val session = active ?: return
        attempt {
            UpdateCookingChecklist(workflows.sessions, workflows.recipes).setChecked(session.sessionId, id, checked)
            refresh()
        }
    }

    fun away() {
        val session = workflows.sessions.getActive() ?: return
        val generation = MarkCookingAway(workflows.sessions).markAway(session.sessionId)
        if (generation != null && session.deliveredCheckInGeneration != generation) {
            platform.checkInMinutes(session.sessionId)?.let {
                platform.scheduleCheckIn(session.sessionId, generation, it)
            }
        }
        showPrompt = false
        refresh()
    }

    fun returned() {
        val session = workflows.sessions.getActive() ?: return
        val returned = MarkCookingReturned(workflows.sessions).markReturned(session.sessionId)
        platform.cancelCheckIn(session.sessionId)
        if (returned != null) showPrompt = returned.showContinuePrompt
        refresh()
    }

    fun resume() {
        val session = active ?: return
        attempt {
            ResumeCooking(workflows.sessions).resume(session.sessionId)
            showPrompt = false
            refresh()
        }
    }

    fun dismissPrompt() {
        val session = active ?: return
        attempt {
            DismissCookingReturnPrompt(workflows.sessions).dismiss(session.sessionId)
            showPrompt = false
            refresh()
        }
    }

    fun onPlatformStatus(status: String) {
        refresh()
        message = status
    }

    fun selectCheckIn(minutes: Int?) {
        val session = active ?: return
        platform.setCheckInMinutes(session.sessionId, minutes) { available ->
            if (!available) message = "Notifications are unavailable. Cooking can continue without a reminder."
            else message = null
            checkInMinutes = platform.checkInMinutes(session.sessionId)
        }
    }

    fun complete(
        consumed: List<ConsumedInventory>,
        leftover: PreparedLeftover?,
        choice: LeftoverChoice,
        reminderMinutes: Int?,
    ) {
        val session = GetActiveCookingSession(workflows.sessions, workflows.recipes).get() ?: return
        attempt {
            val event = CompleteCooking(workflows.kitchen, workflows.cooking).complete(
                session = session,
                cookedOn = DomainLocalDate.today(TimeZone.currentSystemDefault()),
                consumed = consumed,
                leftovers = listOfNotNull(leftover),
                recordLeftoversLater = choice == LeftoverChoice.Later,
            )
            platform.cancelCheckIn(session.sessionId)
            showPrompt = false
            refresh()
            if (reminderMinutes != null && choice == LeftoverChoice.Later) {
                platform.scheduleLeftover(event.id, reminderMinutes) { available ->
                    message = if (available) "Leftover reminder scheduled."
                    else "The reminder could not be scheduled. Record leftovers later from Cook."
                }
            }
        }
    }

    fun recordLeftover(eventId: String, leftover: PreparedLeftover) {
        attempt {
            RecordLeftoversForCookEvent(workflows.kitchen, workflows.cooking).record(eventId, listOf(leftover))
            platform.cancelLeftover(eventId)
            refresh()
        }
    }

    fun dismissLeftover(eventId: String) {
        attempt {
            DismissLeftoverFollowUp(workflows.cooking).dismiss(eventId)
            platform.cancelLeftover(eventId)
            refresh()
        }
    }

    private inline fun attempt(block: () -> Unit): Boolean = try {
        error = null
        block()
        true
    } catch (e: Exception) {
        error = e.message ?: "The operation could not be completed."
        false
    }
}
