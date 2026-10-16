package com.homefinance.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.data.local.HomeFinanceDatabase
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies how [HomeFinanceApp] wires the data layer together.
 *
 * This is the *application-scoped* half of the verification, and it deliberately never opens the
 * household's real database: reading `HomeFinanceApp.database` constructs the Room database
 * object but does not touch the file (see [HomeFinanceDatabase.build]), and nothing here issues a
 * query. The behavioural, read-and-write half of the proof runs against an isolated database in
 * `com.homefinance.app.data.repository.RepositoryDatabaseIntegrationTest`.
 *
 * The last test in this class is the one that holds the whole thing honest: if any of this ever
 * caused `home_finance.db` to appear on the device, it fails.
 */
@RunWith(AndroidJUnit4::class)
class HomeFinanceAppInitializationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val context: Context get() = instrumentation.targetContext

    private val app: HomeFinanceApp
        get() = instrumentation.targetContext.applicationContext as HomeFinanceApp

    /**
     * `by lazy` compiles the property into a `kotlin.Lazy` backing field named
     * `database$delegate`, and that is what keeps the database out of application start-up.
     * [everyThreadSeesTheSameDatabaseInstance] covers the other half of the guarantee - that the
     * default, synchronised mode really does hand every thread the same object.
     */
    @Test
    fun theDatabaseIsStillALazilyInitialisedProperty() {
        val field = HomeFinanceApp::class.java
            .getDeclaredField("database\$delegate")
            .apply { isAccessible = true }

        assertTrue(
            "the database must stay behind a `by lazy` so it cannot be created eagerly at " +
                "application start-up",
            Lazy::class.java.isAssignableFrom(field.type),
        )

        @Suppress("UNCHECKED_CAST")
        val delegate = field.get(app) as Lazy<HomeFinanceDatabase>
        assertSame("the property must still be backed by that lazy", app.database, delegate.value)
    }

    @Test
    fun theDatabaseIsCreatedOnceAndShared() {
        val first = app.database
        val second = app.database

        assertNotNull(first)
        assertSame("the application must hand out exactly one database instance", first, second)
    }

    /**
     * The `by lazy` property is synchronised, so concurrent first access cannot race two
     * initialisations into existence. Whichever thread arrives first, every thread must come away
     * with the identical object.
     */
    @Test
    fun everyThreadSeesTheSameDatabaseInstance() {
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val submitted = (0 until threads).map { pool.submit(Callable { app.database }) }
            pool.shutdown()
            assertTrue("the lookups should not have timed out", pool.awaitTermination(30, TimeUnit.SECONDS))

            val instances = submitted.map { it.get() }
            instances.forEach { assertSame(instances.first(), it) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun theDatabaseUsesTheProductionFileName() {
        assertEquals("home_finance.db", HomeFinanceDatabase.NAME)
        assertEquals(HomeFinanceDatabase.NAME, app.database.openHelper.databaseName)
    }

    /**
     * Proves both repositories were built from the application's one database rather than from
     * databases of their own. The DAOs each repository holds are read reflectively because the
     * alternative - issuing a query to observe the shared data - would open (and therefore
     * create) the household's real database, which a test must never do.
     */
    @Test
    fun bothRepositoriesAreWiredToTheApplicationDatabase() {
        val database = app.database

        assertSame(database.categoryDao(), daoCalled(app.categoryRepository))
        assertSame(database.transactionDao(), daoCalled(app.transactionRepository, "transactionDao"))
        assertSame(database.categoryDao(), daoCalled(app.transactionRepository, "categoryDao"))
    }

    @Test
    fun theRepositoriesAreDistinctSingletons() {
        assertSame(app.categoryRepository, app.categoryRepository)
        assertSame(app.transactionRepository, app.transactionRepository)
        assertNotSame(app.categoryRepository, app.transactionRepository)
    }

    /**
     * The safety net for every other test in the project: touching the application's lazy
     * database and repositories must not bring `home_finance.db` - or a `-wal` or `-journal`
     * sibling of it - into existence.
     */
    @Test
    fun nothingCreatesTheProductionDatabaseFile() {
        app.database
        app.categoryRepository
        app.transactionRepository

        val files = context.databaseList().toList()
        assertFalse(
            "tests must never create the household's database file, but found: $files",
            files.any {
                it == HomeFinanceDatabase.NAME || it.startsWith("${HomeFinanceDatabase.NAME}-")
            },
        )
    }

    /** Reads a private `val` off a repository, used only to check the wiring above. */
    private fun daoCalled(owner: Any, fieldName: String = "categoryDao"): Any? =
        owner.javaClass
            .getDeclaredField(fieldName)
            .apply { isAccessible = true }
            .get(owner)
}
