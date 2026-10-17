package com.homefinance.app.feature.transactions

import com.homefinance.app.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a category's mark is chosen from its name.
 *
 * The choice is a guess drawn from words a household happened to use, so it is asserted directly
 * rather than through a rendered row: what matters is that the common names land on the right
 * glyph, and - more importantly - that an unrecognised name lands on a safe one rather than on a
 * picture that says something untrue.
 */
class CategoryIconTest {

    @Test
    fun aDairyCategoryIsMarkedWithTheMilkBottle() {
        assertEquals(CategoryIconKind.MILK, kind("Milk", TransactionType.EXPENSE))
        assertEquals(CategoryIconKind.MILK, kind("Dairy", TransactionType.EXPENSE))
    }

    @Test
    fun aShoppingCategoryIsMarkedWithTheTrolley() {
        assertEquals(CategoryIconKind.GROCERIES, kind("Groceries", TransactionType.EXPENSE))
        assertEquals(CategoryIconKind.GROCERIES, kind("Vegetables and fruits", TransactionType.EXPENSE))
    }

    @Test
    fun aRecurringBillIsMarkedWithTheBolt() {
        assertEquals(CategoryIconKind.BILLS, kind("Electricity bill", TransactionType.EXPENSE))
        assertEquals(CategoryIconKind.BILLS, kind("Mobile recharge", TransactionType.EXPENSE))
    }

    @Test
    fun anIncomeCategoryIsMarkedWithABanknoteEvenWhenItsNameSaysNothing() {
        // Households name their income after a person or a client, not after money. A receipt is
        // the wrong picture for an amount that arrived, so the type decides when the name cannot.
        assertEquals(CategoryIconKind.INCOME, kind("Freelance", TransactionType.INCOME))
        assertEquals(CategoryIconKind.INCOME, kind("Ravi", TransactionType.INCOME))
    }

    @Test
    fun anUnrecognisedExpenseIsMarkedWithAGenericSlip() {
        assertEquals(CategoryIconKind.OTHER, kind("Miscellaneous", TransactionType.EXPENSE))
    }

    @Test
    fun matchingIgnoresCaseAndLooksInsideTheName() {
        assertEquals(CategoryIconKind.MILK, kind("MILK for the week", TransactionType.EXPENSE))
        assertEquals(CategoryIconKind.BILLS, kind("Quarterly ELECTRICITY", TransactionType.EXPENSE))
    }

    @Test
    fun aNameThatSaysWhatItIsBeatsTheDirectionOfTheMoney() {
        // A dairy sale recorded as income is still a dairy entry; the name is the more specific
        // fact and is the one the household will recognise.
        assertEquals(CategoryIconKind.MILK, kind("Milk sold", TransactionType.INCOME))
    }

    private fun kind(name: String, type: TransactionType): CategoryIconKind =
        categoryIconKindFor(categoryName = name, type = type)
}
