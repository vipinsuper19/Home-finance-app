package com.homefinance.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a category's mark is chosen, and which key wins when there is more than one candidate.
 *
 * The derivation is a guess drawn from words a household happened to use, so it is asserted directly
 * rather than through a rendered row: what matters is that the common names land on the right glyph,
 * and - more importantly - that an unrecognised name lands on the safe one rather than on a picture
 * that says something untrue.
 *
 * The second half of the file is about *precedence*, which is the rule the whole feature rests on:
 * a stored key is a fact and always wins; a missing, blank or unrecognised one is a question, and
 * the name answers it.
 */
class CategoryIconKeyTest {

    // ------------------------------------------------------------- derivation

    @Test
    fun aDairyCategoryIsMarkedAsMilk() {
        assertEquals(CategoryIconKey.MILK, derived("Milk", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.MILK, derived("Dairy", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.MILK, derived("Doodh", TransactionType.EXPENSE))
    }

    @Test
    fun aShoppingCategoryIsMarkedAsGroceries() {
        assertEquals(CategoryIconKey.GROCERIES, derived("Groceries", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.GROCERIES, derived("Kirana", TransactionType.EXPENSE))
    }

    @Test
    fun freshFoodIsMarkedAsVegetablesRatherThanAsAShop() {
        // The two are told apart on purpose: "Vegetables and fruits" is the market stall, and a
        // trolley for it would say "supermarket" where the household said "sabzi".
        assertEquals(CategoryIconKey.VEGETABLES, derived("Vegetables and fruits", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.VEGETABLES, derived("Food", TransactionType.EXPENSE))
    }

    @Test
    fun aPowerBillIsMarkedAsElectricity() {
        assertEquals(CategoryIconKey.ELECTRICITY, derived("Electricity bill", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.ELECTRICITY, derived("Power", TransactionType.EXPENSE))
    }

    @Test
    fun aCylinderIsMarkedAsGas() {
        assertEquals(CategoryIconKey.GAS, derived("Gas Cylinder", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.GAS, derived("LPG", TransactionType.EXPENSE))
    }

    @Test
    fun aConnectionIsMarkedAsInternet() {
        assertEquals(CategoryIconKey.INTERNET, derived("Internet", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.INTERNET, derived("Mobile recharge", TransactionType.EXPENSE))
    }

    @Test
    fun aDoctorIsMarkedAsMedical() {
        assertEquals(CategoryIconKey.MEDICAL, derived("Medical", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.MEDICAL, derived("Medicines", TransactionType.EXPENSE))
    }

    @Test
    fun faresAndFuelAreMarkedAsTransport() {
        assertEquals(CategoryIconKey.TRANSPORT, derived("Transport", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.TRANSPORT, derived("Petrol", TransactionType.EXPENSE))
    }

    @Test
    fun moneyPutAwayIsMarkedAsInvestments() {
        assertEquals(CategoryIconKey.INVESTMENTS, derived("Investments", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.INVESTMENTS, derived("Savings", TransactionType.EXPENSE))
    }

    @Test
    fun theHouseAndTheShopGetTwoDifferentRents() {
        // Both names contain "rent", which is exactly why the particular check has to come first.
        assertEquals(CategoryIconKey.SHOP_RENT, derived("Shop Rent", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.SHOP_RENT, derived("Store rent", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.HOME_RENT, derived("Home Rent", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.HOME_RENT, derived("House rent", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.HOME_RENT, derived("Rent", TransactionType.EXPENSE))
    }

    @Test
    fun anIncomeCategoryIsMarkedAsPayEvenWhenItsNameSaysNothing() {
        // Households name their income after a person or a client, not after money. A pay slip is
        // the right picture for an amount that arrived, so the type decides when the name cannot.
        assertEquals(CategoryIconKey.SALARY, derived("Freelance", TransactionType.INCOME))
        assertEquals(CategoryIconKey.SALARY, derived("Ravi", TransactionType.INCOME))
        assertEquals(CategoryIconKey.SALARY, derived("Salary", TransactionType.INCOME))
    }

    @Test
    fun anUnrecognisedExpenseFallsBackToTheGenericMark() {
        assertEquals(CategoryIconKey.OTHER, derived("Miscellaneous", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.OTHER, derived("", TransactionType.EXPENSE))
    }

    @Test
    fun matchingIgnoresCaseAndLooksInsideTheName() {
        assertEquals(CategoryIconKey.MILK, derived("MILK for the week", TransactionType.EXPENSE))
        assertEquals(CategoryIconKey.ELECTRICITY, derived("Quarterly ELECTRICITY", TransactionType.EXPENSE))
    }

    @Test
    fun aNameThatSaysWhatItIsBeatsTheDirectionOfTheMoney() {
        // A dairy sale recorded as income is still a dairy entry; the name is the more specific
        // fact and is the one the household will recognise.
        assertEquals(CategoryIconKey.MILK, derived("Milk sold", TransactionType.INCOME))
    }

    // ------------------------------------------------------------- precedence

    @Test
    fun aStoredKeyBeatsTheNameItSitsBeside() {
        // The whole point of storing one: "Kirana" would be guessed as groceries, and this
        // household has said it is milk.
        assertEquals(
            CategoryIconKey.MILK,
            keyFor(name = "Kirana", type = TransactionType.EXPENSE, stored = CategoryIconKey.MILK),
        )
    }

    @Test
    fun aStoredKeyIsMatchedWhateverCaseOrPaddingItCarries() {
        assertEquals(
            CategoryIconKey.HOME_RENT,
            keyFor(name = "Anything", type = TransactionType.EXPENSE, stored = "  HOME_RENT "),
        )
    }

    @Test
    fun aKeyThisBuildDoesNotKnowIsTreatedAsNoChoiceAtAll() {
        // A key from a newer build, or a value typed into the database by hand. Showing the generic
        // slip would be worse than answering the question the name was already asking.
        assertEquals(
            CategoryIconKey.MILK,
            keyFor(name = "Milk", type = TransactionType.EXPENSE, stored = "nonsense"),
        )
    }

    @Test
    fun aMissingKeyIsDerivedFromTheName() {
        assertEquals(
            CategoryIconKey.GROCERIES,
            keyFor(name = "Groceries", type = TransactionType.EXPENSE, stored = null),
        )
        assertEquals(
            CategoryIconKey.GROCERIES,
            keyFor(name = "Groceries", type = TransactionType.EXPENSE, stored = "   "),
        )
    }

    // --------------------------------------------------------------- the vocabulary

    @Test
    fun everyShippedCategoryNamesAKeyThisBuildCanDraw() {
        // A typo in the shipped list would be invisible until someone installed the app and looked
        // at a row, so it is asserted here instead.
        val unknown = DEFAULT_CATEGORIES.filterNot { CategoryIconKey.isKnown(it.iconKey) }
        assertTrue("unknown icon keys in the shipped categories: $unknown", unknown.isEmpty())
    }

    @Test
    fun theShippedCategoriesDoNotRepeatANameWithinAType() {
        // The seeder's insert-ignores-duplicates would hide a copy: the second row would simply
        // never be written, and the app would quietly ship eleven categories instead of twelve.
        val duplicates = DEFAULT_CATEGORIES
            .groupBy { it.type to it.name }
            .filterValues { it.size > 1 }
            .keys
        assertTrue("duplicate shipped categories: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun normalisingAKeyTrimsItAndFoldsItsCase() {
        assertEquals(CategoryIconKey.MILK, CategoryIconKey.normalize(" Milk "))
        assertEquals(CategoryIconKey.HOME_RENT, CategoryIconKey.normalize("Home_Rent"))
        assertNull(CategoryIconKey.normalize(null))
        assertNull(CategoryIconKey.normalize("   "))
    }

    @Test
    fun theKnownSetHoldsEveryKeyAndNothingElse() {
        assertTrue(CategoryIconKey.isKnown(CategoryIconKey.OTHER))
        assertTrue(CategoryIconKey.isKnown("interNET"))
        assertFalse(CategoryIconKey.isKnown("salary_slip"))
        assertFalse(CategoryIconKey.isKnown(null))
        assertEquals(13, CategoryIconKey.KNOWN.size)
    }

    private fun derived(name: String, type: TransactionType): String =
        deriveCategoryIconKey(categoryName = name, type = type)

    private fun keyFor(name: String, type: TransactionType, stored: String?): String =
        categoryIconKeyFor(categoryName = name, type = type, storedIconKey = stored)
}
