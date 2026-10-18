package com.homefinance.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.data.local.HomeFinanceDatabase
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies how [HomeFinanceApp] wires the data layer together.
 *
 * This is the *application-scoped* half of the verification, and it is still deliberately query-free:
 * reading `HomeFinanceApp.database` constructs the Room database object but does not touch the file
 * (see [HomeFinanceDatabase.build]), and every assertion here is about identity and wiring rather
 * than about contents. The behavioural, read-and-write half of the proof runs against an isolated
 * database in `com.homefinance.app.data.repository.RepositoryDatabaseIntegrationTest`.
 *
 * ### The one thing that changed
 *
 * Application start-up now opens the database once, on a background dispatcher, to write the
 * categories the app ships with - see
 * [com.homefinance.app.data.repository.CategorySeeder]. So `home_finance.db` legitimately exists on
 * a device that has run the app, and the assertion this class used to make ("the production file
 * never appears") is neither true nor the point any more.
 *
 * It is not replaced by a file-listing assertion, because there is no honest one left to write: the
 * app's own file is created by the app, and neighbouring test classes keep databases of their own
 * (`migration-test.db`, `home_finance-test.db`) in the same directory while the run is in progress,
 * so a directory listing cannot say which test wrote what. What replaces it is the part of start-up
 * that was actually added: [theCategorySeederIsWiredToTheApplicationDatabase] proves the new wiring,
 * and the seeding itself - once only, nothing financial, and retried safely after a failure - is
 * proved against a real database in
 * `com.homefinance.app.data.repository.CategorySeederDatabaseIntegrationTest`.
 */
@RunWith(AndroidJUnit4::class)
class HomeFinanceAppInitializationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val app: HomeFinanceApp
        get() = instrumentation.targetContext.applicationContext as HomeFinanceApp

    /**
     * `by lazy` compiles the property into a `kotlin.Lazy` backing field named
     * `database$delegate`, and that is what keeps *constructing the application* from opening the
     * household's database: the one open at start-up is the seeder's, on a background dispatcher,
     * and it asks for this delegate rather than being driven by it.
     * [everyThreadSeesTheSameDatabaseInstance] covers the other half of the guarantee - that the
     * default, synchronised mode really does hand every thread the same object.
     */
    @Test
    fun theDatabaseIsStillALazilyInitialisedProperty() {
        val field = HomeFinanceApp::class.java
            .getDeclaredField("database\$delegate")
            .apply { isAccessible = true }

        assertTrue(
            "the database must stay behind a `by lazy` so that *constructing the application* " +
                "never opens the household's database",
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
     * alternative - issuing a query to observe the shared data - would write to the household's real
     * database rather than merely observe it, and a wiring test has no business doing that.
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
     * The safety net for every other test in the project, restated for a schema in which start-up
     * has work to do.
     *
     * This used to require that nothing here could bring `home_finance.db` into existence. The
     * application now opens its database once at start-up to write the categories it ships with, so
     * that claim is deliberately gone rather than worked around - the file is supposed to be there.
     * Starting the household's database from a test is no longer the thing being forbidden; the
     * things still forbidden are *writing the household's ledger from a test* and *letting another
     * test's database be mistaken for the application's*, and neither of those is decidable from a
     * list of file names, so neither is asserted here. The parts of the data layer this class touches
     * are instead checked to belong to the application's own database by identity, in
     * [theCategorySeederIsWiredToTheApplicationDatabase] and
     * [bothRepositoriesAreWiredToTheApplicationDatabase], and the rest is proved on isolated and
     * in-memory databases elsewhere.
     */
    @Test
    fun touchingTheApplicationDatabaseStillLeavesTheWiringIntact() {
        app.database
        app.categoryRepository
        app.transactionRepository

        // The same objects underneath, after all three have been asked for: nothing here rebuilt a
        // database of its own while reaching the one the application holds.
        assertSame(app.database, app.database)
        assertSame(app.categoryRepository, app.categoryRepository)
        assertEquals(HomeFinanceDatabase.NAME, app.database.openHelper.databaseName)
    }

    /**
     * The seeder is wired to the application's one database, the same way the repositories are.
     *
     * It is asserted through the DAOs it holds rather than through the rows it writes, so this stays
     * a wiring test with no query in it. The rows themselves - and the rollback that keeps the flag
     * and the categories together - are proved against a real database in
     * `com.homefinance.app.data.repository.CategorySeederDatabaseIntegrationTest`.
     */
    @Test
    fun theCategorySeederIsWiredToTheApplicationDatabase() {
        val database = app.database
        val seeder = seederOf(app)

        assertSame(database.categoryDao(), daoCalled(seeder, "categoryDao"))
        assertSame(database.appSettingDao(), daoCalled(seeder, "appSettingDao"))
    }

    /**
     * Reads the application's private `categorySeeder` out of its lazy delegate, without writing
     * anything: obtaining the seeder constructs it, which opens nothing.
     */
    private fun seederOf(app: HomeFinanceApp): Any {
        val delegate = HomeFinanceApp::class.java
            .getDeclaredField("categorySeeder\$delegate")
            .apply { isAccessible = true }
            .get(app)

        return requireNotNull((delegate as Lazy<*>).value) { "the seeder was never constructed" }
    }

    /** Reads a private `val` off a repository, used only to check the wiring above. */
    private fun daoCalled(owner: Any, fieldName: String = "categoryDao"): Any? =
        owner.javaClass
            .getDeclaredField(fieldName)
            .apply { isAccessible = true }
            .get(owner)
}
