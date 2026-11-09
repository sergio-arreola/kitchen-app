package dev.kitchenapp.core.domain.inventory

import dev.kitchenapp.core.domain.identifiers.IngredientId
import dev.kitchenapp.core.domain.identifiers.KitchenLocationId
import dev.kitchenapp.core.domain.math.Rational
import dev.kitchenapp.core.domain.time.DomainLocalDate
import dev.kitchenapp.core.domain.units.UnitCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.datetime.LocalDate

class InventoryTest {

    private val ingredientId = IngredientId.generate()
    private val purchaseDate = date(2026, 11, 1)
    private val openingDate = date(2026, 11, 3)

    @Test
    fun `lots support presence approximate and exact tracking across locations`() {
        val pantry = KitchenLocation(name = "Pantry")
        val fridge = KitchenLocation(name = "Fridge")
        val inventory = KitchenInventory()
            .addLocation(pantry)
            .addLocation(fridge)
            .addLot(
                InventoryLot(
                    ingredientId = ingredientId,
                    locationId = pantry.id,
                ),
            )
            .addLot(
                InventoryLot(
                    ingredientId = ingredientId,
                    locationId = fridge.id,
                    amount = InventoryAmount.Approximate(
                        quantity = Rational.of(3, 2),
                        unit = UnitCode.CUP,
                    ),
                    purchasedOn = purchaseDate,
                    openedOn = openingDate,
                ),
            )
            .addLot(
                InventoryLot(
                    ingredientId = ingredientId,
                    locationId = fridge.id,
                    amount = InventoryAmount.Exact(
                        quantity = Rational.of(6),
                        unit = UnitCode.EGG,
                    ),
                ),
            )

        assertEquals(listOf(pantry, fridge), inventory.locations)
        assertEquals(3, inventory.lots.size)
        assertIs<InventoryAmount.PresenceOnly>(inventory.lots[0].amount)
        assertEquals(
            InventoryAmount.Approximate(
                Rational.of(3, 2),
                UnitCode.CUP,
            ),
            inventory.lots[1].amount,
        )
        assertEquals(
            InventoryAmount.Exact(Rational.of(6), UnitCode.EGG),
            inventory.lots[2].amount,
        )
        assertEquals(2, inventory.lots.count { it.locationId == fridge.id })
        assertEquals(openingDate, inventory.lots[1].openedOn)
    }

    @Test
    fun `locations can be renamed and only unused locations can be removed`() {
        val pantry = KitchenLocation(name = "Pantry")
        val fridge = KitchenLocation(name = "Fridge")
        val lot = InventoryLot(ingredientId = ingredientId, locationId = pantry.id)
        val inventory = KitchenInventory(listOf(pantry, fridge), listOf(lot))

        val renamed = inventory.renameLocation(pantry.id, "Dry goods")
        assertEquals("Dry goods", renamed.locations.first { it.id == pantry.id }.name)
        assertEquals(pantry.id, renamed.lots.single().locationId)
        assertFailsWith<IllegalArgumentException> { inventory.removeLocation(pantry.id) }
        assertEquals(listOf(pantry), inventory.discardLot(lot.id).removeLocation(fridge.id).locations)
        assertFailsWith<IllegalArgumentException> { inventory.addLocation(KitchenLocation(name = " pantry ")) }
        assertFailsWith<IllegalArgumentException> { inventory.renameLocation(pantry.id, "   ") }
    }

    @Test
    fun `updating a lot preserves identity and validates its location`() {
        val pantry = KitchenLocation(name = "Pantry")
        val fridge = KitchenLocation(name = "Fridge")
        val lot = InventoryLot(ingredientId = ingredientId, locationId = pantry.id)
        val inventory = KitchenInventory(listOf(pantry, fridge), listOf(lot))

        val updated = inventory.updateLot(lot.copy(locationId = fridge.id, amount = InventoryAmount.Exact(Rational.of(2))))

        assertEquals(fridge.id, updated.lots.single().locationId)
        assertFailsWith<IllegalArgumentException> {
            inventory.updateLot(lot.copy(locationId = KitchenLocationId.generate()))
        }
    }

    @Test
    fun `inventory operations add adjust move freeze thaw and discard lots`() {
        val fridge = KitchenLocation(name = "Fridge")
        val freezer = KitchenLocation(name = "Freezer")
        val originalAmount = InventoryAmount.Approximate(
            quantity = Rational.of(2),
            unit = UnitCode.CUP,
        )
        val lot = InventoryLot(
            ingredientId = ingredientId,
            locationId = fridge.id,
            amount = originalAmount,
            purchasedOn = purchaseDate,
            openedOn = openingDate,
        )
        val original = KitchenInventory()
            .addLocation(fridge)
            .addLocation(freezer)
            .addLot(lot)

        val adjusted = original.adjustAmount(
            lot.id,
            InventoryAmount.Exact(Rational.of(1), UnitCode.CUP),
        )
        assertEquals(originalAmount, original.lots.single().amount)
        assertEquals(InventoryAmount.Exact(Rational.of(1), UnitCode.CUP), adjusted.lots.single().amount)

        val moved = adjusted.moveLot(lot.id, freezer.id)
        assertEquals(freezer.id, moved.lots.single().locationId)
        assertEquals(adjusted.lots.single(), moved.lots.single().copy(locationId = fridge.id))

        val frozenOn = date(2026, 11, 4)
        val frozen = moved.freezeLot(lot.id, frozenOn)
        assertTrue(frozen.lots.single().isFrozen)
        assertEquals(frozenOn, frozen.lots.single().frozenOn)
        assertEquals(openingDate, frozen.lots.single().openedOn)
        assertEquals(lot.manufacturerUseBy, frozen.lots.single().manufacturerUseBy)

        val thawed = frozen.thawLot(lot.id)
        assertFalse(thawed.lots.single().isFrozen)
        assertNull(thawed.lots.single().frozenOn)

        val discarded = thawed.discardLot(lot.id)
        assertTrue(discarded.lots.isEmpty())
        assertEquals(1, thawed.lots.size)
    }

    @Test
    fun `freshness recommendation uses opening then purchase date without replacing entered dates`() {
        val manufacturerDate = date(2026, 11, 20)
        val userOverride = date(2026, 11, 15)
        val lot = InventoryLot(
            ingredientId = ingredientId,
            locationId = KitchenLocationId.generate(),
            purchasedOn = purchaseDate,
            openedOn = openingDate,
            manufacturerUseBy = manufacturerDate,
            userUseBy = userOverride,
        )
        val policy = FreshnessPolicy(
            daysAfterPurchase = 30,
            daysAfterOpening = 5,
        )

        val recommendation = policy.recommendFor(lot)
        assertEquals(date(2026, 11, 8), recommendation?.recommendedUseBy)
        assertEquals(FreshnessBasis.OPENING, recommendation?.basis)

        val guidance = policy.guidanceFor(lot)
        assertEquals(manufacturerDate, guidance.manufacturerUseBy)
        assertEquals(userOverride, guidance.userOverride)
        assertEquals(recommendation, guidance.recommendation)
        assertEquals(userOverride, guidance.effectiveUseBy)
        assertEquals(UseBySource.USER_OVERRIDE, guidance.effectiveSource)

        val withoutOverride = policy.guidanceFor(lot.copy(userUseBy = null))
        assertEquals(manufacturerDate, withoutOverride.effectiveUseBy)
        assertEquals(UseBySource.MANUFACTURER, withoutOverride.effectiveSource)

        val recommendationOnly = policy.guidanceFor(
            lot.copy(manufacturerUseBy = null, userUseBy = null),
        )
        assertEquals(recommendation?.recommendedUseBy, recommendationOnly.effectiveUseBy)
        assertEquals(UseBySource.RECOMMENDATION, recommendationOnly.effectiveSource)

        val unopenedLot = lot.copy(openedOn = null, userUseBy = null)
        assertEquals(
            date(2026, 12, 1),
            policy.recommendFor(unopenedLot)?.recommendedUseBy,
        )
        assertEquals(
            FreshnessBasis.PURCHASE,
            policy.recommendFor(unopenedLot)?.basis,
        )
    }

    @Test
    fun `frozen lots use the frozen window and fall back to opening after thaw`() {
        val frozenOn = date(2026, 11, 4)
        val lot = InventoryLot(
            ingredientId = ingredientId,
            locationId = KitchenLocationId.generate(),
            purchasedOn = purchaseDate,
            openedOn = openingDate,
            frozenOn = frozenOn,
        )
        val policy = FreshnessPolicy(
            daysAfterOpening = 5,
            daysAfterFreezing = 60,
        )

        val frozenRecommendation = policy.recommendFor(lot)
        assertEquals(date(2027, 1, 3), frozenRecommendation?.recommendedUseBy)
        assertEquals(FreshnessBasis.FREEZING, frozenRecommendation?.basis)
        assertNull(FreshnessPolicy(daysAfterOpening = 5).recommendFor(lot))

        val thawedRecommendation = policy.recommendFor(lot.copy(frozenOn = null))
        assertEquals(date(2026, 11, 8), thawedRecommendation?.recommendedUseBy)
        assertEquals(FreshnessBasis.OPENING, thawedRecommendation?.basis)
    }

    @Test
    fun `unknown dates produce no freshness recommendation`() {
        val lot = InventoryLot(
            ingredientId = ingredientId,
            locationId = KitchenLocationId.generate(),
        )
        val guidance = FreshnessPolicy(
            daysAfterPurchase = 7,
            daysAfterOpening = 3,
            daysAfterFreezing = 30,
        ).guidanceFor(lot)

        assertNull(guidance.recommendation)
        assertNull(guidance.effectiveUseBy)
        assertNull(guidance.effectiveSource)
    }

    @Test
    fun `inventory rejects invalid quantities dates and negative freshness windows`() {
        assertFailsWith<IllegalArgumentException> {
            InventoryAmount.Exact(Rational.of(-1), UnitCode.EGG)
        }
        assertFailsWith<IllegalArgumentException> {
            InventoryAmount.Approximate(
                quantity = Rational.of(1),
                level = dev.kitchenapp.core.domain.enums.ApproximateLevel.EXACT,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            InventoryLot(
                ingredientId = ingredientId,
                locationId = KitchenLocationId.generate(),
                purchasedOn = openingDate,
                openedOn = purchaseDate,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FreshnessPolicy(daysAfterOpening = -1)
        }
    }

    private fun date(year: Int, month: Int, day: Int): DomainLocalDate =
        DomainLocalDate(LocalDate(year, month, day))
}
