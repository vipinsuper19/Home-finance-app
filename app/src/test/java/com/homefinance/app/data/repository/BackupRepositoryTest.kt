package com.homefinance.app.data.repository

import com.homefinance.app.data.local.entity.AppSettingEntity
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.model.BackupDocument
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.SettingsKeys
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup file's two directions: what it writes, and what it refuses.
 *
 * The interesting cases are the refusals. A valid file round-tripping is the easy half; a file that
 * names a category it does not contain, repeats a due date, or claims a newer format must be rejected
 * *before* a row is deleted - otherwise "restore" would be a way to lose data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRepositoryTest {

    private val instant = Instant.parse("2026-11-05T10:15:30Z")

    private val groceries = CategoryEntity
        .create("Groceries", TransactionType.EXPENSE, "cart", instant)
        .copy(id = 10L)

    private val salary = CategoryEntity
        .create("Salary", TransactionType.INCOME, null, instant)
        .copy(id = 11L)

    private val dinner = TransactionEntity(
        id = 100L,
        type = TransactionType.EXPENSE,
        amountPaise = 25_050L,
        categoryId = 10L,
        description = "Milk, bread",
        notes = "Weekly \"big\" shop",
        quantityThousandths = 1_500L,
        ratePaise = 16_700L,
        transactionDate = LocalDate.of(2026, 11, 5),
        createdAt = instant,
        updatedAt = instant,
    )

    private val pay = TransactionEntity(
        id = 101L,
        type = TransactionType.INCOME,
        amountPaise = 5_000_000L,
        categoryId = 11L,
        description = "November",
        notes = null,
        quantityThousandths = null,
        ratePaise = null,
        transactionDate = LocalDate.of(2026, 11, 1),
        createdAt = instant,
        updatedAt = instant,
    )

    private val rent = RecurringScheduleEntity(
        id = 200L,
        name = "Shop rent",
        description = null,
        categoryId = 10L,
        amountPaise = 1_500_000L,
        unit = null,
        ratePaise = null,
        frequency = RecurrenceFrequency.MONTHLY,
        intervalCount = 1,
        startDate = LocalDate.of(2026, 10, 5),
        endDate = null,
        status = RecurringScheduleStatus.ACTIVE,
        generatedThrough = LocalDate.of(2026, 12, 5),
        createdAt = instant,
        updatedAt = instant,
    )

    private val paidRent = RecurringOccurrenceEntity(
        id = 300L,
        scheduleId = 200L,
        dueDate = LocalDate.of(2026, 11, 5),
        amountPaise = 1_500_000L,
        status = RecurringOccurrenceStatus.PAID,
        transactionId = 100L,
        createdAt = instant,
        updatedAt = instant,
    )

    private class Store(
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        schedules: List<RecurringScheduleEntity>,
        occurrences: List<RecurringOccurrenceEntity>,
        settings: Map<String, String>,
    ) {
        val categoryDao = FakeCategoryDao(categories)
        val transactionDao = FakeTransactionDao(
            categoryNames = categories.associate { it.id to it.name },
            initial = transactions,
        )
        val recurringDao = FakeRecurringDao(
            categoryNames = categories.associate { it.id to it.name },
            initialSchedules = schedules,
            initialOccurrences = occurrences,
        )
        val settingDao = FakeAppSettingDao(settings)

        val repository = BackupRepository(
            categoryDao = categoryDao,
            transactionDao = transactionDao,
            recurringDao = recurringDao,
            appSettingDao = settingDao,
            transactionRunner = DirectTransactionRunner,
            clock = { Instant.parse("2026-11-05T12:00:00Z") },
        )
    }

    private fun fullStore() = Store(
        categories = listOf(groceries, salary),
        transactions = listOf(dinner, pay),
        schedules = listOf(rent),
        occurrences = listOf(paidRent),
        settings = mapOf(
            SettingsKeys.THEME_MODE to "DARK",
            SettingsKeys.SHOW_BALANCE_ON_HOME to "false",
            SettingsKeys.LAST_BACKUP_AT to "2026-10-01T09:00:00Z",
            SettingsKeys.LAST_BACKUP_BYTES to "999",
        ),
    )

    private fun emptyStore() = Store(
        categories = emptyList(),
        transactions = emptyList(),
        schedules = emptyList(),
        occurrences = emptyList(),
        settings = emptyMap(),
    )

    // ------------------------------------------------------------------ what is written

    @Test
    fun theFileNamesItsFormatAndVersionAndCarriesEveryTable() = runTest {
        val json = fullStore().repository.create(appVersion = "1.0 (1)")

        val document = BackupDocument.json.decodeFromString(BackupDocument.serializer(), json)

        assertEquals(BackupDocument.FORMAT, document.format)
        assertEquals(1, document.version)
        assertEquals("2026-11-05T12:00:00Z", document.createdAt)
        assertEquals("1.0 (1)", document.appVersion)
        assertEquals(listOf(10L, 11L), document.categories.map { it.id }.sorted())
        assertEquals(listOf(100L, 101L), document.transactions.map { it.id }.sorted())
        assertEquals(listOf(200L), document.schedules.map { it.id })
        assertEquals(listOf(300L), document.occurrences.map { it.id })
    }

    @Test
    fun theFileCarriesThePreferencesButNotThisDevicesBackupHistory() = runTest {
        val json = fullStore().repository.create(appVersion = "1.0 (1)")
        val document = BackupDocument.json.decodeFromString(BackupDocument.serializer(), json)

        val keys = document.settings.map { it.key }

        assertTrue(SettingsKeys.THEME_MODE in keys)
        assertTrue(SettingsKeys.SHOW_BALANCE_ON_HOME in keys)
        assertTrue(SettingsKeys.LAST_BACKUP_AT !in keys)
        assertTrue(SettingsKeys.LAST_BACKUP_BYTES !in keys)
    }

    // ------------------------------------------------------------------ reading it back

    @Test
    fun aFileTheAppWroteInspectsAsValidAndSaysWhatItHolds() = runTest {
        val json = fullStore().repository.create(appVersion = "1.0 (1)")

        val inspection = emptyStore().repository.inspect(json)

        val valid = inspection as? RestoreInspection.Valid
        assertNotNull("the app's own file must be valid", valid)
        assertEquals(
            RestoreSummary(categories = 2, transactions = 2, schedules = 1, occurrences = 1, settings = 2),
            valid?.summary,
        )
    }

    @Test
    fun aRoundTripRestoresRowsWithTheirIdsAndReferences() = runTest {
        val source = fullStore()
        val json = source.repository.create(appVersion = "1.0 (1)")

        val target = emptyStore()
        val outcome = target.repository.restore(
            (target.repository.inspect(json) as RestoreInspection.Valid).document,
        )

        assertTrue(outcome is RestoreOutcome.Restored)
        assertEquals(
            RestoreSummary(categories = 2, transactions = 2, schedules = 1, occurrences = 1, settings = 2),
            (outcome as RestoreOutcome.Restored).summary,
        )

        val restoredCategories = target.categoryDao.getAll()
        assertEquals(listOf(10L, 11L), restoredCategories.map { it.id }.sorted())
        assertEquals("Groceries", restoredCategories.first { it.id == 10L }.name)
        assertEquals("cart", restoredCategories.first { it.id == 10L }.iconKey)

        val restoredTransactions = target.transactionDao.getAll()
        assertEquals(listOf(100L, 101L), restoredTransactions.map { it.id }.sorted())
        val restoredDinner = restoredTransactions.first { it.id == 100L }
        assertEquals(10L, restoredDinner.categoryId)
        assertEquals(25_050L, restoredDinner.amountPaise)
        assertEquals("Milk, bread", restoredDinner.description)
        assertEquals(1_500L, restoredDinner.quantityThousandths)
        assertEquals(LocalDate.of(2026, 11, 5), restoredDinner.transactionDate)

        val restoredSchedules = target.recurringDao.getAllSchedules()
        assertEquals(listOf(200L), restoredSchedules.map { it.id })

        val restoredOccurrences = target.recurringDao.getAllOccurrences()
        assertEquals(1, restoredOccurrences.size)
        assertEquals(200L, restoredOccurrences.single().scheduleId)
        assertEquals(100L, restoredOccurrences.single().transactionId)
        assertEquals(RecurringOccurrenceStatus.PAID, restoredOccurrences.single().status)

        assertEquals("DARK", target.settingDao.stored[SettingsKeys.THEME_MODE])
    }

    @Test
    fun restoringReplacesWhatWasThereRatherThanAddingToIt() = runTest {
        val target = emptyStore()
        target.categoryDao.insert(CategoryEntity.create("Old bucket", TransactionType.EXPENSE).copy(id = 90L))
        target.settingDao.put(AppSettingEntity(SettingsKeys.THEME_MODE, "SYSTEM"))

        val json = fullStore().repository.create(appVersion = "1.0 (1)")
        target.repository.restore((target.repository.inspect(json) as RestoreInspection.Valid).document)

        assertEquals(listOf(10L, 11L), target.categoryDao.getAll().map { it.id }.sorted())
        assertNull(target.categoryDao.getById(90L))
        assertEquals("DARK", target.settingDao.stored[SettingsKeys.THEME_MODE])
    }

    @Test
    fun restoringKeepsThisDevicesOwnBackupHistory() = runTest {
        val target = emptyStore()
        target.settingDao.putAll(
            listOf(
                AppSettingEntity(SettingsKeys.LAST_BACKUP_AT, "2026-10-20T08:00:00Z"),
                AppSettingEntity(SettingsKeys.LAST_BACKUP_BYTES, "12345"),
            ),
        )

        val json = fullStore().repository.create(appVersion = "1.0 (1)")
        target.repository.restore((target.repository.inspect(json) as RestoreInspection.Valid).document)

        // The file's own device keys were never written, and the phone's are still its own: a restore
        // must not claim a backup happened on this device when it did not.
        assertEquals("2026-10-20T08:00:00Z", target.settingDao.stored[SettingsKeys.LAST_BACKUP_AT])
        assertEquals("12345", target.settingDao.stored[SettingsKeys.LAST_BACKUP_BYTES])
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    fun somethingThatIsNotJsonIsRefusedWithAPlainSentence() {
        val inspection = emptyStore().repository.inspect("this is not a backup")

        val invalid = inspection as RestoreInspection.Invalid
        assertEquals(1, invalid.errors.size)
        assertTrue(invalid.errors.single().contains("not a readable Home Finance backup"))
    }

    @Test
    fun aFileFromAnotherAppIsRefused() = runTest {
        val json = fullStore().repository.create(appVersion = "1.0 (1)")
            .replace("\"home-finance-backup\"", "\"someone-elses-export\"")

        val invalid = emptyStore().repository.inspect(json) as RestoreInspection.Invalid

        assertTrue(invalid.errors.single().contains("not a Home Finance backup"))
    }

    @Test
    fun aNewerFormatIsRefusedAndSaysWhichVersionItIs() = runTest {
        val json = fullStore().repository.create(appVersion = "1.0 (1)")
        val document = BackupDocument.json.decodeFromString(BackupDocument.serializer(), json)
        val newer = BackupDocument.json.encodeToString(
            BackupDocument.serializer(),
            document.copy(version = 7),
        )

        val invalid = emptyStore().repository.inspect(newer) as RestoreInspection.Invalid

        val message = invalid.errors.single()
        assertTrue(message.contains("format 7"))
        assertTrue(message.contains("reads 1"))
    }

    @Test
    fun anUnknownKeyIsRefusedRatherThanIgnored() = runTest {
        val json = fullStore().repository.create(appVersion = "1.0 (1)")
            .replaceFirst("\"appVersion\"", "\"somethingNew\": true,\n  \"appVersion\"")

        // Strict decoding: a field this build does not understand could mean something it would get
        // wrong, so the whole file is refused instead of read partially.
        assertTrue(emptyStore().repository.inspect(json) is RestoreInspection.Invalid)
    }

    @Test
    fun aTransactionNamingACategoryTheFileDoesNotContainIsRefused() = runTest {
        val json = fullStore().repository.create(appVersion = "1.0 (1)")
        val document = BackupDocument.json.decodeFromString(BackupDocument.serializer(), json)
        val broken = document.copy(transactions = document.transactions.map { it.copy(categoryId = 999L) })

        val invalid = emptyStore().repository.inspect(
            BackupDocument.json.encodeToString(BackupDocument.serializer(), broken),
        ) as RestoreInspection.Invalid

        assertTrue(invalid.errors.any { it.contains("Transaction 100") })
    }

    @Test
    fun twoOccurrencesClaimingTheSameDueDateAreRefused() = runTest {
        val json = fullStore().repository.create(appVersion = "1.0 (1)")
        val document = BackupDocument.json.decodeFromString(BackupDocument.serializer(), json)
        val duplicated = document.copy(
            occurrences = document.occurrences + document.occurrences.single().copy(id = 301L),
        )

        val invalid = emptyStore().repository.inspect(
            BackupDocument.json.encodeToString(BackupDocument.serializer(), duplicated),
        ) as RestoreInspection.Invalid

        assertTrue(invalid.errors.any { it.contains("Recurring occurrence 301") })
    }

    @Test
    fun anAmountThatIsNotPositiveIsRefused() = runTest {
        val json = fullStore().repository.create(appVersion = "1.0 (1)")
        val document = BackupDocument.json.decodeFromString(BackupDocument.serializer(), json)
        val broken = document.copy(
            transactions = document.transactions.map { it.copy(amountPaise = 0L) },
        )

        val invalid = emptyStore().repository.inspect(
            BackupDocument.json.encodeToString(BackupDocument.serializer(), broken),
        ) as RestoreInspection.Invalid

        assertTrue(invalid.errors.any { it.contains("Transaction 100") })
    }

    @Test
    fun restoringAnInvalidDocumentWritesNothing() = runTest {
        val target = emptyStore()
        target.categoryDao.insert(CategoryEntity.create("Keep me", TransactionType.EXPENSE).copy(id = 1L))
        val document = BackupDocument(
            format = BackupDocument.FORMAT,
            version = 99,
        )

        val outcome = target.repository.restore(document)

        assertTrue(outcome is RestoreOutcome.Rejected)
        assertEquals(listOf("Keep me"), target.categoryDao.getAll().map { it.name })
    }
}
