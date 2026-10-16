package com.homefinance.app.core.common

/**
 * TEMPORARY sample data for the Task 1.1 UI shell only.
 *
 * Nothing here is persisted, calculated or derived from a database: the values exist purely
 * so the Home screen can be reviewed visually while the storage layer does not exist yet.
 *
 * All amounts are held as [Long] **paise** (1 rupee = 100 paise) so that money is never
 * represented with floating point. When the Room-backed repository lands, every constant
 * below is deleted and the Home screen reads from the database instead.
 */
object PlaceholderData {

    /** Monthly household income: ₹48,500.00 */
    const val incomeInPaise: Long = 4_850_000L

    /** Monthly household expenses: ₹27,340.50 */
    const val expenseInPaise: Long = 2_734_050L

    /** Income minus expenses: ₹21,159.50 */
    const val balanceInPaise: Long = incomeInPaise - expenseInPaise
}
