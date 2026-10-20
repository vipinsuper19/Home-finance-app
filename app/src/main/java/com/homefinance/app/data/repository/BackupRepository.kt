package com.homefinance.app.data.repository

import com.homefinance.app.data.local.TransactionRunner
import com.homefinance.app.data.local.dao.AppSettingDao
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.RecurringDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.model.BackupDocument
import com.homefinance.app.data.model.BackupSetting
import com.homefinance.app.data.model.BackupValidator
import com.homefinance.app.data.model.toAppSettingEntity
import com.homefinance.app.data.model.toBackupCategory
import com.homefinance.app.data.model.toBackupOccurrence
import com.homefinance.app.data.model.toBackupSchedule
import com.homefinance.app.data.model.toBackupTransaction
import com.homefinance.app.data.model.toCategoryEntity
import com.homefinance.app.data.model.toOccurrenceEntity
import com.homefinance.app.data.model.toScheduleEntity
import com.homefinance.app.data.model.toTransactionEntity
import java.time.Instant
import kotlinx.serialization.SerializationException

/** What a restore actually replaced. Shown in the summary after a successful restore. */
data class RestoreSummary(
    val categories: Int,
    val transactions: Int,
    val schedules: Int,
    val occurrences: Int,
    val settings: Int,
)

/** The result of checking a chosen file, before anything is written. */
sealed interface RestoreInspection {

    /** The file is complete and valid; [document] is exactly what a confirmation would install. */
    data class Valid(val document: BackupDocument, val summary: RestoreSummary) : RestoreInspection

    /**
     * Nothing can be written. [errors] are sentences for the household, not stack traces: a restore
     * that cannot proceed must say what is wrong with the file rather than offer a generic apology.
     */
    data class Invalid(val errors: List<String>) : RestoreInspection
}

/** The two honest answers a confirmed restore can give. */
sealed interface RestoreOutcome {

    /** The database now holds exactly what the document described. */
    data class Restored(val summary: RestoreSummary) : RestoreOutcome

    /** Nothing was written; the household's existing data is untouched. */
    data class Rejected(val errors: List<String>) : RestoreOutcome
}

/**
 * Writes and reads the versioned backup file, and replaces the database from one.
 *
 * ### The file is built in one transaction
 *
 * Five tables are read to make one document. Reading them one after another outside a transaction
 * would let a payment land between the occurrence read and the transaction read, producing a file
 * whose occurrence points at a transaction the file does not contain - a file that then fails its own
 * validation. [TransactionRunner] gives the reads one snapshot, so the document is a consistent
 * picture of the household's data.
 *
 * ### Inspect first, replace second
 *
 * [inspect] decodes and validates without touching the database, so the screen can state exactly what
 * a file would install and require a confirmation. [restore] then re-validates the same document and
 * replaces everything inside one transaction. If anything fails - a duplicate id, a constraint, a full
 * disk - Room rolls the whole thing back and the household's existing data is exactly as it was. The
 * app never "half restores", and it never uses a destructive migration to do it.
 *
 * Ids are preserved deliberately. A transaction's `category_id`, and an occurrence's `schedule_id` and
 * `transaction_id`, are the referential integrity of the data; renumbering them on restore would mean
 * rewriting every reference and hoping nothing was missed.
 */
class BackupRepository(
    private val categoryDao: CategoryDao,
    private val transactionDao: TransactionDao,
    private val recurringDao: RecurringDao,
    private val appSettingDao: AppSettingDao,
    private val transactionRunner: TransactionRunner,
    private val clock: () -> Instant = Instant::now,
) {

    /**
     * Builds the backup for the app at [appVersion], as the UTF-8 JSON text to write to the file.
     *
     * The caller writes the text to a destination the household chose and only then records the
     * backup's size and time; this method never touches a file and cannot report a success that did
     * not happen.
     */
    suspend fun create(appVersion: String): String = transactionRunner.inTransaction {
        val document = BackupDocument(
            createdAt = clock().toString(),
            appVersion = appVersion,
            categories = categoryDao.getAll().map { it.toBackupCategory() },
            transactions = transactionDao.getAll().map { it.toBackupTransaction() },
            schedules = recurringDao.getAllSchedules().map { it.toBackupSchedule() },
            occurrences = recurringDao.getAllOccurrences().map { it.toBackupOccurrence() },
            settings = appSettingDao.getAll()
                .filterNot { it.key in BackupDocument.DEVICE_ONLY_SETTING_KEYS }
                .map { BackupSetting(key = it.key, value = it.value) },
        )

        BackupDocument.json.encodeToString(BackupDocument.serializer(), document)
    }

    /**
     * Decodes and validates [json] without writing anything.
     *
     * A malformed document - truncated, not JSON, a file from another app - is [RestoreInspection.Invalid]
     * with one plain sentence, not a crash. A well-formed document that breaks a rule is invalid with
     * the rule stated.
     */
    fun inspect(json: String): RestoreInspection {
        val document = try {
            BackupDocument.json.decodeFromString(BackupDocument.serializer(), json)
        } catch (_: SerializationException) {
            return unreadable()
        } catch (_: IllegalArgumentException) {
            return unreadable()
        }

        val check = BackupValidator.validate(document)
        if (!check.isValid) return RestoreInspection.Invalid(check.errors)

        return RestoreInspection.Valid(document = document, summary = summaryOf(document))
    }

    /**
     * Replaces the database with a document that has already passed [inspect].
     *
     * The document is validated again here rather than trusted, because this is the method that writes
     * and a caller is free to hand it anything. Nothing is written when validation fails.
     */
    suspend fun restore(document: BackupDocument): RestoreOutcome {
        val check = BackupValidator.validate(document)
        if (!check.isValid) return RestoreOutcome.Rejected(check.errors)

        // The device's own backup history survives a restore: it describes this phone, not the file.
        val deviceMetadata = appSettingDao.getAll()
            .filter { it.key in BackupDocument.DEVICE_ONLY_SETTING_KEYS }

        return try {
            transactionRunner.inTransaction {
                // Dependency order: children first, parents last. The RESTRICT foreign keys would
                // refuse any other order, which is exactly the guard wanted here.
                recurringDao.deleteAllOccurrences()
                transactionDao.deleteAll()
                recurringDao.deleteAllSchedules()
                categoryDao.deleteAll()
                appSettingDao.deleteAll()

                categoryDao.insertAll(document.categories.map { it.toCategoryEntity() })
                recurringDao.insertSchedules(document.schedules.map { it.toScheduleEntity() })
                transactionDao.insertAll(document.transactions.map { it.toTransactionEntity() })
                recurringDao.insertOccurrences(document.occurrences.map { it.toOccurrenceEntity() })
                appSettingDao.putAll(
                    document.settings
                        .filterNot { it.key in BackupDocument.DEVICE_ONLY_SETTING_KEYS }
                        .map(BackupSetting::toAppSettingEntity),
                )
                appSettingDao.putAll(deviceMetadata)
            }

            RestoreOutcome.Restored(summaryOf(document))
        } catch (failure: Exception) {
            // Room has already rolled the transaction back, so the data is untouched. The screen says
            // so rather than reporting a restore that did not happen.
            RestoreOutcome.Rejected(
                listOf(
                    "The backup could not be restored, so nothing was changed. " +
                        "The file may be damaged.",
                ),
            )
        }
    }

    private fun summaryOf(document: BackupDocument): RestoreSummary = RestoreSummary(
        categories = document.categories.size,
        transactions = document.transactions.size,
        schedules = document.schedules.size,
        occurrences = document.occurrences.size,
        settings = document.settings.count { it.key !in BackupDocument.DEVICE_ONLY_SETTING_KEYS },
    )

    private fun unreadable(): RestoreInspection = RestoreInspection.Invalid(
        listOf("This file is not a readable Home Finance backup. Nothing was changed."),
    )
}
