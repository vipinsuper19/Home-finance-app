package com.homefinance.app.data.model

import com.homefinance.app.data.local.entity.AppSettingEntity
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The versioned, documented format of a Home Finance backup file.
 *
 * ### Why JSON, and why the entities are not written directly
 *
 * The file has to survive the app's own schema evolving, so it is a *contract* rather than a dump of
 * the tables: every field is named, every date is an ISO string, every enum is its name, and money is
 * the same integer count of paise the database stores. A column rename in a future migration cannot
 * silently change what an old file means, and a file can be read by a person.
 *
 * [format] and [version] are the first two fields on purpose. A reader must be able to reject a file
 * before it trusts a single row of it, and a future format change bumps [VERSION] rather than
 * reinterpreting this one. Unknown keys are *rejected* rather than ignored: a file that carries a
 * field this build does not understand may mean something this build would get wrong.
 *
 * The backup carries the household's whole data set - categories, the ledger, recurring schedules and
 * their occurrence history - plus the display preferences. It deliberately does **not** carry the
 * two "last backup" metadata keys, which describe this device's own backup history rather than the
 * household's finances; a restored file must not claim a backup happened on this phone.
 */
@Serializable
data class BackupDocument(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val createdAt: String = "",
    val appVersion: String = "",
    val categories: List<BackupCategory> = emptyList(),
    val transactions: List<BackupTransaction> = emptyList(),
    val schedules: List<BackupSchedule> = emptyList(),
    val occurrences: List<BackupOccurrence> = emptyList(),
    val settings: List<BackupSetting> = emptyList(),
) {

    companion object {
        const val FORMAT: String = "home-finance-backup"

        /** The only version this build writes, and the highest it can read. */
        const val VERSION: Int = 1

        /** Settings that describe this device's backup history and are never restored over. */
        val DEVICE_ONLY_SETTING_KEYS: Set<String> = setOf(
            SettingsKeys.LAST_BACKUP_AT,
            SettingsKeys.LAST_BACKUP_BYTES,
        )

        /**
         * The JSON dialect of the file: strict, pretty-printed, and with defaults written out so every
         * field a reader might look for is visibly present.
         */
        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}

@Serializable
data class BackupCategory(
    val id: Long,
    val name: String,
    val type: String,
    val iconKey: String? = null,
    val isActive: Boolean = true,
    val createdAt: String,
)

@Serializable
data class BackupTransaction(
    val id: Long,
    val type: String,
    val amountPaise: Long,
    val categoryId: Long,
    val description: String? = null,
    val notes: String? = null,
    val quantityThousandths: Long? = null,
    val ratePaise: Long? = null,
    val transactionDate: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BackupSchedule(
    val id: Long,
    val name: String,
    val description: String? = null,
    val categoryId: Long,
    val amountPaise: Long,
    val unit: String? = null,
    val ratePaise: Long? = null,
    val frequency: String,
    val intervalCount: Int,
    val startDate: String,
    val endDate: String? = null,
    val status: String,
    val generatedThrough: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BackupOccurrence(
    val id: Long,
    val scheduleId: Long,
    val dueDate: String,
    val amountPaise: Long,
    val status: String,
    val transactionId: Long? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BackupSetting(
    val key: String,
    val value: String,
)

// --- Entity to file -------------------------------------------------------------------------

fun CategoryEntity.toBackupCategory(): BackupCategory = BackupCategory(
    id = id,
    name = name,
    type = type.name,
    iconKey = iconKey,
    isActive = isActive,
    createdAt = createdAt.toString(),
)

fun TransactionEntity.toBackupTransaction(): BackupTransaction = BackupTransaction(
    id = id,
    type = type.name,
    amountPaise = amountPaise,
    categoryId = categoryId,
    description = description,
    notes = notes,
    quantityThousandths = quantityThousandths,
    ratePaise = ratePaise,
    transactionDate = transactionDate.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

fun RecurringScheduleEntity.toBackupSchedule(): BackupSchedule = BackupSchedule(
    id = id,
    name = name,
    description = description,
    categoryId = categoryId,
    amountPaise = amountPaise,
    unit = unit,
    ratePaise = ratePaise,
    frequency = frequency.name,
    intervalCount = intervalCount,
    startDate = startDate.toString(),
    endDate = endDate?.toString(),
    status = status.name,
    generatedThrough = generatedThrough?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

fun RecurringOccurrenceEntity.toBackupOccurrence(): BackupOccurrence = BackupOccurrence(
    id = id,
    scheduleId = scheduleId,
    dueDate = dueDate.toString(),
    amountPaise = amountPaise,
    status = status.name,
    transactionId = transactionId,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

// --- File to entity -------------------------------------------------------------------------
//
// These throw on a malformed value, which is what the validator is for: it calls every one of them
// once, collects the failures as sentences, and only a file that passes is ever mapped for real.

fun BackupCategory.toCategoryEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    type = TransactionType.valueOf(type),
    iconKey = iconKey,
    isActive = isActive,
    createdAt = Instant.parse(createdAt),
)

fun BackupTransaction.toTransactionEntity(): TransactionEntity = TransactionEntity(
    id = id,
    type = TransactionType.valueOf(type),
    amountPaise = amountPaise,
    categoryId = categoryId,
    description = description,
    notes = notes,
    quantityThousandths = quantityThousandths,
    ratePaise = ratePaise,
    transactionDate = LocalDate.parse(transactionDate),
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
)

fun BackupSchedule.toScheduleEntity(): RecurringScheduleEntity = RecurringScheduleEntity(
    id = id,
    name = name,
    description = description,
    categoryId = categoryId,
    amountPaise = amountPaise,
    unit = unit,
    ratePaise = ratePaise,
    frequency = RecurrenceFrequency.valueOf(frequency),
    intervalCount = intervalCount,
    startDate = LocalDate.parse(startDate),
    endDate = endDate?.let(LocalDate::parse),
    status = RecurringScheduleStatus.valueOf(status),
    generatedThrough = generatedThrough?.let(LocalDate::parse),
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
)

fun BackupOccurrence.toOccurrenceEntity(): RecurringOccurrenceEntity = RecurringOccurrenceEntity(
    id = id,
    scheduleId = scheduleId,
    dueDate = LocalDate.parse(dueDate),
    amountPaise = amountPaise,
    status = RecurringOccurrenceStatus.valueOf(status),
    transactionId = transactionId,
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
)

fun BackupSetting.toAppSettingEntity(): AppSettingEntity = AppSettingEntity(key = key, value = value)

/** The outcome of checking a decoded file before anything is written. */
data class BackupCheck(val errors: List<String>) {

    val isValid: Boolean get() = errors.isEmpty()

    companion object {
        val Valid = BackupCheck(emptyList())
    }
}

/**
 * Checks a decoded file for everything the database itself would refuse, **before** it is written.
 *
 * The rules are the schema's own rules restated in words a household can act on: every reference
 * points at something the file contains, every amount is positive, every date parses, every enum is
 * one this build knows, and no two rows claim the same key. A file that passes cannot make the
 * restore fail halfway, which is what makes the restore one transaction that either replaces the data
 * or leaves it exactly as it was.
 *
 * The messages are shown verbatim in the Restore screen, so each names the row and the problem
 * without naming a column.
 */
object BackupValidator {

    /** How many problems are reported before the list is cut off, so a corrupt file cannot flood the screen. */
    const val MAX_REPORTED_ERRORS: Int = 8

    fun validate(document: BackupDocument): BackupCheck {
        if (document.format != BackupDocument.FORMAT) {
            return BackupCheck(listOf("This file is not a Home Finance backup."))
        }
        if (document.version != BackupDocument.VERSION) {
            return BackupCheck(
                listOf(
                    "This backup was made by a different version of the app " +
                        "(format ${document.version}; this app reads ${BackupDocument.VERSION}).",
                ),
            )
        }

        val errors = mutableListOf<String>()

        fun check(label: String, block: () -> Unit) {
            if (errors.size >= MAX_REPORTED_ERRORS) return
            try {
                block()
            } catch (_: IllegalArgumentException) {
                errors += "$label is not valid."
            } catch (_: java.time.format.DateTimeParseException) {
                errors += "$label has a date or time this app cannot read."
            }
        }

        val categoryIds = mutableSetOf<Long>()
        val categoryNames = mutableSetOf<String>()
        val categoryTypes = mutableMapOf<Long, TransactionType>()

        document.categories.forEach { category ->
            check("Category ${category.id}") {
                require(category.id > 0L) { "id" }
                require(categoryIds.add(category.id)) { "duplicate id" }
                val type = TransactionType.valueOf(category.type)
                categoryTypes[category.id] = type
                val normalised = CategoryEntity.requireValidName(category.name)
                require(categoryNames.add("${type.name}|${normalised.lowercase()}")) { "duplicate name" }
                Instant.parse(category.createdAt)
            }
        }

        val transactionIds = mutableSetOf<Long>()

        document.transactions.forEach { transaction ->
            check("Transaction ${transaction.id}") {
                require(transaction.id > 0L) { "id" }
                require(transactionIds.add(transaction.id)) { "duplicate id" }
                require(transaction.amountPaise > 0L) { "amount" }
                val type = TransactionType.valueOf(transaction.type)
                require(transaction.categoryId in categoryIds) { "category" }
                require(categoryTypes[transaction.categoryId] == type) { "category type" }
                require((transaction.quantityThousandths == null) == (transaction.ratePaise == null)) {
                    "quantity and rate"
                }
                transaction.quantityThousandths?.let { require(it > 0L) { "quantity" } }
                transaction.ratePaise?.let { require(it > 0L) { "rate" } }
                LocalDate.parse(transaction.transactionDate)
                Instant.parse(transaction.createdAt)
                Instant.parse(transaction.updatedAt)
            }
        }

        val scheduleIds = mutableSetOf<Long>()

        document.schedules.forEach { schedule ->
            check("Recurring schedule ${schedule.id}") {
                require(schedule.id > 0L) { "id" }
                require(scheduleIds.add(schedule.id)) { "duplicate id" }
                require(schedule.name.isNotBlank()) { "name" }
                require(schedule.amountPaise > 0L) { "amount" }
                require(schedule.categoryId in categoryIds) { "category" }
                require(schedule.intervalCount > 0) { "repeat interval" }
                RecurrenceFrequency.valueOf(schedule.frequency)
                RecurringScheduleStatus.valueOf(schedule.status)
                require((schedule.unit == null) == (schedule.ratePaise == null)) { "unit and rate" }
                schedule.ratePaise?.let { require(it > 0L) { "rate" } }
                val start = LocalDate.parse(schedule.startDate)
                schedule.endDate?.let { require(!LocalDate.parse(it).isBefore(start)) { "end date" } }
                schedule.generatedThrough?.let(LocalDate::parse)
                Instant.parse(schedule.createdAt)
                Instant.parse(schedule.updatedAt)
            }
        }

        val occurrenceIds = mutableSetOf<Long>()
        val occurrenceDueDates = mutableSetOf<String>()

        document.occurrences.forEach { occurrence ->
            check("Recurring occurrence ${occurrence.id}") {
                require(occurrence.id > 0L) { "id" }
                require(occurrenceIds.add(occurrence.id)) { "duplicate id" }
                require(occurrence.scheduleId in scheduleIds) { "schedule" }
                require(occurrenceDueDates.add("${occurrence.scheduleId}|${occurrence.dueDate}")) {
                    "duplicate due date"
                }
                require(occurrence.amountPaise > 0L) { "amount" }
                RecurringOccurrenceStatus.valueOf(occurrence.status)
                occurrence.transactionId?.let {
                    require(it in transactionIds) { "linked transaction" }
                }
                LocalDate.parse(occurrence.dueDate)
                Instant.parse(occurrence.createdAt)
                Instant.parse(occurrence.updatedAt)
            }
        }

        val settingKeys = mutableSetOf<String>()

        document.settings.forEach { setting ->
            check("Setting") {
                require(setting.key.isNotBlank()) { "key" }
                require(settingKeys.add(setting.key)) { "duplicate key" }
            }
        }

        return BackupCheck(errors)
    }
}
