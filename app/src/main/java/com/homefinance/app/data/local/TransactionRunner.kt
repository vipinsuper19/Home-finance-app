package com.homefinance.app.data.local

import androidx.room.withTransaction

/**
 * Runs a block of data-layer work as **one** database transaction.
 *
 * ### Why this seam exists
 *
 * Recording a recurring payment is two writes that must both happen or neither: an actual expense is
 * inserted into the ledger, and the occurrence it settles is linked to it. If the second write failed
 * on its own the household would be left with an expense nothing points at - or, if the order were
 * reversed, with a schedule claiming a bill was paid while no money is recorded anywhere. Neither is
 * a state the app should be able to reach, and neither can be prevented by writing the two statements
 * more carefully: they have to be one atomic unit.
 *
 * Room can do that ([androidx.room.withTransaction]), but only something holding the database can ask
 * for it, and the repositories deliberately hold *DAOs* so their rules can be tested without a
 * device. This one-method interface is the smallest thing that squares those two: production passes
 * [RoomTransactionRunner], a test passes something that either runs the block directly or fails it on
 * purpose, and both are a `suspend` function that returns the block's result.
 *
 * It is deliberately **not** a general-purpose transaction API. There are two callers, and each has a
 * reason that would be a bug without one: the recurring payment path, where a single household action
 * has to write to two tables, and [com.homefinance.app.data.repository.CategorySeeder], where the
 * seeded rows and the flag that says the run happened have to land together or not at all. Anything
 * else that only writes one row does not need this.
 */
interface TransactionRunner {

    /**
     * Runs [block], committing it as a unit and rolling the whole thing back if it throws.
     *
     * @throws Exception whatever [block] threw, after the rollback.
     */
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/**
 * The production [TransactionRunner]: Room's own transaction scope on the application's database.
 *
 * `RoomDatabase.withTransaction` runs the block on the database's transaction dispatcher, so the DAO
 * calls inside it join the same SQLite transaction rather than each opening their own. An exception
 * anywhere in the block rolls back everything it wrote.
 */
class RoomTransactionRunner(private val database: HomeFinanceDatabase) : TransactionRunner {

    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}
