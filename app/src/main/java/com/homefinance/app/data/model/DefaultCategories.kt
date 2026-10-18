package com.homefinance.app.data.model

/**
 * One category the app offers before the household has written any of its own.
 *
 * @param name the display name. It is passed through the same normalisation every category name is
 *   ([com.homefinance.app.data.local.entity.CategoryEntity.create]), so "Gas Cylinder" is stored
 *   exactly as it reads here.
 * @param type which side of the ledger it belongs to.
 * @param iconKey the mark it is drawn with, from [CategoryIconKey].
 */
data class DefaultCategory(
    val name: String,
    val type: TransactionType,
    val iconKey: String,
)

/**
 * The categories a household almost always starts with, written once by
 * [com.homefinance.app.data.repository.CategorySeeder].
 *
 * ### Why the app ships names at all
 *
 * A brand-new ledger with no categories cannot record anything: every entry has to be filed under
 * one, so the first thing a household would face is typing a list of their own before they can write
 * down a single expense. This list is that list, already filled in and already wearing a mark, so the
 * first thing the app can do is record a figure.
 *
 * ### What these are not
 *
 * They are **categories, not transactions**. Not one of them carries a name, an amount or a date, and
 * nothing here can affect a total, because a category holds no money at all. Seeding invents no
 * financial history and no expected payment - the ledger still starts empty, and the only rows this
 * produces are the empty buckets to file things into.
 *
 * The list is deliberately short and ordinary: milk, the groceries, the two rents, power, gas, a
 * connection, vegetables, a doctor, travel and savings, plus the wage that pays for them. Anything the
 * household does not use is retired from the picker with one tap, and anything missing is added
 * through the picker exactly as it always was.
 */
val DEFAULT_CATEGORIES: List<DefaultCategory> = listOf(
    DefaultCategory("Milk", TransactionType.EXPENSE, CategoryIconKey.MILK),
    DefaultCategory("Groceries", TransactionType.EXPENSE, CategoryIconKey.GROCERIES),
    DefaultCategory("Vegetables", TransactionType.EXPENSE, CategoryIconKey.VEGETABLES),
    DefaultCategory("Home Rent", TransactionType.EXPENSE, CategoryIconKey.HOME_RENT),
    DefaultCategory("Shop Rent", TransactionType.EXPENSE, CategoryIconKey.SHOP_RENT),
    DefaultCategory("Electricity", TransactionType.EXPENSE, CategoryIconKey.ELECTRICITY),
    DefaultCategory("Gas Cylinder", TransactionType.EXPENSE, CategoryIconKey.GAS),
    DefaultCategory("Internet", TransactionType.EXPENSE, CategoryIconKey.INTERNET),
    DefaultCategory("Medical", TransactionType.EXPENSE, CategoryIconKey.MEDICAL),
    DefaultCategory("Transport", TransactionType.EXPENSE, CategoryIconKey.TRANSPORT),
    DefaultCategory("Investments", TransactionType.EXPENSE, CategoryIconKey.INVESTMENTS),
    DefaultCategory("Salary", TransactionType.INCOME, CategoryIconKey.SALARY),
)
