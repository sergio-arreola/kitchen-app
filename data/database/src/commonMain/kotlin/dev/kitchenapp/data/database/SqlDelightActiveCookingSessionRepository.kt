package dev.kitchenapp.data.database

import dev.kitchenapp.core.application.ActiveCookingSession
import dev.kitchenapp.core.application.ActiveCookingSessionRepository
import dev.kitchenapp.core.application.CookingReturn
import dev.kitchenapp.core.application.FirstReturnState
import dev.kitchenapp.core.domain.identifiers.RecipeId
import dev.kitchenapp.core.domain.identifiers.RecipeIngredientId
import dev.kitchenapp.data.database.generated.KitchenDatabase
import kotlin.uuid.Uuid

/** SQLDelight persistence for the single resumable cooking session. */
class SqlDelightActiveCookingSessionRepository(
    private val database: KitchenDatabase,
) : ActiveCookingSessionRepository {
    override fun getActive(): ActiveCookingSession? = database.recipeQueries.selectActiveCookingSession()
        .executeAsOneOrNull()?.let { row ->
            ActiveCookingSession(
                sessionId = row.id,
                recipeId = RecipeId(Uuid.parse(row.recipeId)),
                servings = row.servings?.toInt(),
                checkedIngredients = database.recipeQueries.selectCheckedCookingIngredients(row.id)
                    .executeAsList().map { RecipeIngredientId(Uuid.parse(it)) }.toSet(),
                firstReturnState = enumValueOf<FirstReturnState>(row.firstReturnState),
                awayGeneration = row.awayGeneration,
                isAway = row.isAway == 1L,
                deliveredCheckInGeneration = row.deliveredCheckInGeneration,
                resumeRequired = row.resumeRequired == 1L,
            )
        }

    override fun start(session: ActiveCookingSession) {
        database.transaction {
            val queries = database.recipeQueries
            require(queries.selectActiveCookingSession().executeAsOneOrNull() == null) {
                "A cooking session is already active"
            }
            require(queries.selectRecipeById(session.recipeId.toString()).executeAsOneOrNull() != null) {
                "Active cooking recipe does not exist"
            }
            queries.insertCookingSession(
                session.sessionId,
                session.recipeId.toString(),
                session.servings?.toLong(),
                session.firstReturnState.name,
                session.awayGeneration,
                session.isAway.toLong(),
                session.deliveredCheckInGeneration,
                session.resumeRequired.toLong(),
            )
            session.checkedIngredients.sortedBy { it.toString() }.forEach { ingredientId ->
                queries.insertCheckedCookingIngredient(session.sessionId, ingredientId.toString())
            }
        }
    }

    override fun setIngredientChecked(
        sessionId: String,
        ingredientId: RecipeIngredientId,
        checked: Boolean,
    ): ActiveCookingSession {
        database.transaction {
            val queries = database.recipeQueries
            require(queries.selectCookingSessionById(sessionId).executeAsOneOrNull() != null) {
                "Cooking session is no longer active"
            }
            if (checked) {
                queries.insertCheckedCookingIngredient(sessionId, ingredientId.toString())
            } else {
                queries.deleteCheckedCookingIngredient(sessionId, ingredientId.toString())
            }
        }
        return requireNotNull(getActive())
    }

    override fun dismissFirstReturnPrompt(sessionId: String) {
        database.transaction {
            val queries = database.recipeQueries
            queries.dismissCookingReturnPrompt(sessionId)
            check(queries.selectChangedRows().executeAsOne() == 1L) {
                "Cooking return prompt is no longer active"
            }
        }
    }

    override fun resume(sessionId: String): ActiveCookingSession {
        database.transaction {
            val queries = database.recipeQueries
            queries.resumeCookingSession(sessionId)
            check(queries.selectChangedRows().executeAsOne() == 1L) {
                "Cooking session is no longer active"
            }
        }
        return requireNotNull(getActive())
    }

    override fun markAway(sessionId: String): Long? {
        var generation: Long? = null
        database.transaction {
            val queries = database.recipeQueries
            val current = getActive() ?: return@transaction
            if (current.sessionId != sessionId || current.resumeRequired) return@transaction
            if (!current.isAway) {
                queries.markCookingSessionAway(sessionId)
            }
            generation = getActive()?.takeIf { it.sessionId == sessionId && it.isAway }?.awayGeneration
        }
        return generation
    }

    override fun markReturned(sessionId: String): CookingReturn? {
        var result: CookingReturn? = null
        database.transaction {
            val current = getActive() ?: return@transaction
            if (current.sessionId != sessionId || !current.isAway) return@transaction
            database.recipeQueries.markCookingSessionReturned(sessionId)
            val updated = getActive() ?: return@transaction
            result = CookingReturn(
                session = updated,
                showContinuePrompt = current.firstReturnState == FirstReturnState.NOT_RETURNED,
                resumeRequired = updated.resumeRequired,
            )
        }
        return result
    }

    override fun claimCheckInDelivery(sessionId: String, generation: Long): Boolean {
        var claimed = false
        database.transaction {
            val queries = database.recipeQueries
            queries.claimCookingCheckIn(generation, sessionId, generation, generation)
            claimed = queries.selectChangedRows().executeAsOne() == 1L
        }
        return claimed
    }

    override fun clear(sessionId: String) {
        database.recipeQueries.deleteActiveCookingSession(sessionId)
    }

    private fun Boolean.toLong(): Long = if (this) 1L else 0L
}
