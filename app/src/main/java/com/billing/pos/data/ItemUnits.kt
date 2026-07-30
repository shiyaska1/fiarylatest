package com.billing.pos.data

import kotlin.math.round

/**
 * Dual-unit helpers.
 *
 * An item's [Item.price] and its stock are always expressed in the PRIMARY unit ([Item.unit]).
 * [Item.secondaryUnit] is a smaller alternate unit, and [Item.conversionFactor] says how many
 * secondary units fit in one primary unit (e.g. BOX / PCS / 12).
 */

/** A unit the user can bill an item in, with the rate and stock-conversion that go with it. */
data class UnitChoice(
    /** Display name, e.g. "BOX" or "PCS". */
    val unit: String,
    /** Rate per one [unit]. */
    val price: Double,
    /** Primary units in one [unit] — 1.0 for the primary, 1/factor for the secondary. */
    val primaryPerUnit: Double
) {
    val isSecondary: Boolean get() = primaryPerUnit != 1.0
}

/** True when this item is genuinely sold in two different units. */
val Item.hasTwoUnits: Boolean
    get() = conversionFactor > 0 &&
        secondaryUnit.isNotBlank() &&
        !secondaryUnit.equals(unit, ignoreCase = true)

private fun round2(v: Double): Double = round(v * 100.0) / 100.0

/** The primary unit choice: the item's own price, one-to-one with stock. */
fun Item.primaryChoice(): UnitChoice = UnitChoice(unit.ifBlank { "PCS" }, price, 1.0)

/**
 * The secondary unit choice: price divided by the conversion factor, rounded to two decimals.
 * One secondary unit is 1/factor of a primary unit for stock purposes.
 */
fun Item.secondaryChoice(): UnitChoice {
    val f = if (conversionFactor > 0) conversionFactor else 1.0
    return UnitChoice(secondaryUnit.ifBlank { "PCS" }, round2(price / f), 1.0 / f)
}

/** Both choices when the item has two units, otherwise just the primary one. */
fun Item.unitChoices(): List<UnitChoice> =
    if (hasTwoUnits) listOf(primaryChoice(), secondaryChoice()) else listOf(primaryChoice())

// ---- Buying side (purchase entry / purchase order) --------------------------------------
/** The rate to buy at: the item's purchase price, or its sales price when none is set. */
val Item.costRate: Double get() = if (purchasePrice > 0.0) purchasePrice else price

/** The primary unit priced at [costRate]. */
fun Item.primaryCostChoice(): UnitChoice = UnitChoice(unit.ifBlank { "PCS" }, costRate, 1.0)

/** The secondary unit priced at [costRate] / factor. */
fun Item.secondaryCostChoice(): UnitChoice {
    val f = if (conversionFactor > 0) conversionFactor else 1.0
    return UnitChoice(secondaryUnit.ifBlank { "PCS" }, round2(costRate / f), 1.0 / f)
}

/** Unit choices for buying — same units as [unitChoices], but at the purchase rate. */
fun Item.costUnitChoices(): List<UnitChoice> =
    if (hasTwoUnits) listOf(primaryCostChoice(), secondaryCostChoice()) else listOf(primaryCostChoice())
