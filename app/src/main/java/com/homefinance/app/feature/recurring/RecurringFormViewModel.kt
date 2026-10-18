package com.homefinance.app.feature.recurring

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.core.util.MoneyInput
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.repository.CategoryRepository
import com.homefinance.app.data.repository.RecurringRepository
import com.homefinance.app.feature.addtransaction.CategoriesUiState
import com.homefinance.app.feature.addtransaction.CategoryDialogError
import com.homefinance.app.feature.addtransaction.CategoryDialogState
import com.homefinance.app.feature.addtransaction.CategoryOption
import com.homefinance.app.feature.addtransaction.EditLoadState
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs one form for both creating a recurring expense and changing an existing one.
 *
 * ### One form, two jobs, on purpose
 *
 * An edit and a creation ask for exactly the same facts in exactly the same way, so they are the same
 * screen with the same validation and the same wording. Two screens would drift, and the rules that
 * matter here - a positive amount, a category that is really an active expense category, an end date
 * that does not precede the start date - would end up enforced in one and not the other.
 * [editingId] is the whole difference: `null` writes a new schedule, non-null rewrites that one.
 *
 * ### Where the rules live
 *
 * This class validates only what the *screen* needs to complain about: a non-blank name, a parseable
 * positive amount, a parseable interval, a category having been chosen, and an end date not before the
 * start date. The authoritative rules - the amount really is positive, the interval really is within
 * range, the category really exists and really is an expense category, the dates really are ordered -
 * stay in [RecurringRepository], which refuses any write that breaks them. Duplicating those checks here
 * would create a second copy of the rules that could drift from the tested one.
 *
 * ### Where the category list comes from
 *
 * [CategoryRepository.observeByType] re-read every time the reload counter moves, filtered to the
 * **active** expense categories. Nothing here polls: Room pushes a new list whenever a category
 * changes, so a category created in the picker's own dialog appears without this class asking again. The
 * read deliberately does not depend on anything the household types, so no keystroke reaches SQLite.
 *
 * ### Saving
 *
 * [save] does not report success on its own authority. It awaits the repository and only then records
 * the id in [RecurringFormUiState.savedScheduleId]; until that id exists the save button is dead and
 * [save] refuses, so a double tap - including one across a configuration change, because the flag lives
 * in the view model - cannot write two schedules.
 *
 * @param editingId the schedule this form changes, or `null` to create one.
 * @param today the household's current local day. It is the default start date for a new schedule, and
 *   the day the repository generates initial occurrences up to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringFormViewModel(
    private val repository: RecurringRepository,
    private val categories: CategoryRepository,
    private val today: LocalDate = LocalDate.now(),
    private val editingId: Long? = null,
    private val instant: () -> Instant = Instant::now,
) : ViewModel() {

    private val state = MutableStateFlow(
        RecurringFormUiState(
            startDate = today,
            // An edit has to read its row before it can draw itself; a new schedule is ready at once.
            editLoad = if (editingId == null) EditLoadState.Ready else EditLoadState.Loading,
        ),
    )

    /** The form exactly as the screen should render it. */
    val uiState: StateFlow<RecurringFormUiState> = state.asStateFlow()

    private val categoryReloads = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            categoryReloads
                .flatMapLatest { observeActiveExpenseCategories() }
                .collect { options -> state.update { it.copy(categories = options) } }
        }

        if (editingId != null) loadForEdit()
    }

    /** Records the name as typed. The complaint about a missing name is cleared with it. */
    fun onNameChange(value: String) {
        state.update { it.copy(name = value, showNameError = false, saveError = null) }
    }

    fun onDescriptionChange(value: String) {
        state.update { it.copy(description = value) }
    }

    /** Records the expected amount as typed. Clears the previous complaint about it. */
    fun onAmountChange(value: String) {
        state.update { it.copy(amountInput = value, amountError = null, saveError = null) }
    }

    /**
     * Records the chosen category, named as the offered list names it.
     *
     * An id that is not among the offered categories is ignored rather than stored: a category can only
     * be chosen from the list the picker showed, and keeping an id this form cannot name would put
     * "nothing chosen" on screen while the save used something else.
     */
    fun onCategorySelected(categoryId: Long) {
        state.update { current ->
            val option = (current.categories as? CategoriesUiState.Ready)
                ?.options
                ?.firstOrNull { it.id == categoryId }
                ?: return@update current

            current.copy(category = option, showCategoryError = false, saveError = null)
        }
    }

    fun onFrequencyChange(frequency: RecurrenceFrequency) {
        state.update { it.copy(frequency = frequency) }
    }

    /** Records the repeat interval as typed. Clears the previous complaint about it. */
    fun onIntervalChange(value: String) {
        state.update { it.copy(intervalInput = value, intervalError = null, saveError = null) }
    }

    /**
     * Moves the first occurrence.
     *
     * The end date is deliberately *not* pushed along with it. A household correcting a start date has
     * one thing in mind, and silently rewriting the other date would change how many occurrences the
     * schedule generates without saying so; if the move makes the two inconsistent, the save says so.
     */
    fun onStartDateChange(date: LocalDate) {
        state.update { it.copy(startDate = date, showEndDateError = false, saveError = null) }
    }

    /** Sets or clears the last occurrence. `null` means "until paused". */
    fun onEndDateChange(date: LocalDate?) {
        state.update { it.copy(endDate = date, showEndDateError = false, saveError = null) }
    }

    /** Asks for the category list again after a failed read. */
    fun retryCategories() {
        categoryReloads.update { it + 1 }
    }

    /** Asks for the schedule being changed again after a failed read. Does nothing on a new schedule. */
    fun retryEditLoad() {
        if (editingId != null) loadForEdit()
    }

    /**
     * Validates the form and, if it passes, creates or rewrites the schedule.
     *
     * Every check is made before anything is written, and the first failure is reported on the field it
     * belongs to - a form that complained about all five at once would bury the one the household can
     * actually see. The order runs from the top of the form down, so the first thing said is the first
     * thing on screen.
     *
     * A successful return here is *not* the same as a successful save: only
     * [RecurringFormUiState.savedScheduleId] becoming non-null means the row exists.
     */
    fun save() {
        val current = state.value
        if (!current.canAttemptSave) return

        val name = current.name.trim()
        if (name.isEmpty()) {
            state.update { it.copy(showNameError = true, saveError = null) }
            return
        }

        val amount = MoneyInput.parseRupeesToPaise(current.amountInput)
        if (amount is MoneyParseResult.Problem) {
            state.update { it.copy(amountError = amount, saveError = null) }
            return
        }

        val interval = parseInterval(current.intervalInput)
        if (interval is IntervalParseResult.Problem) {
            state.update { it.copy(intervalError = interval.problem, saveError = null) }
            return
        }

        val category = current.category
        if (category == null) {
            state.update { it.copy(showCategoryError = true, saveError = null) }
            return
        }

        val endDate = current.endDate
        if (endDate != null && endDate.isBefore(current.startDate)) {
            state.update { it.copy(showEndDateError = true, saveError = null) }
            return
        }

        state.update {
            it.copy(
                isSaving = true,
                showNameError = false,
                amountError = null,
                intervalError = null,
                showCategoryError = false,
                showEndDateError = false,
                saveError = null,
            )
        }

        val amountPaise = (amount as MoneyParseResult.Success).paise
        val intervalCount = (interval as IntervalParseResult.Success).count
        val editing = editingId

        viewModelScope.launch {
            try {
                if (editing == null) {
                    val id = repository.createSchedule(
                        name = name,
                        categoryId = category.id,
                        amountPaise = amountPaise,
                        frequency = current.frequency,
                        intervalCount = intervalCount,
                        startDate = current.startDate,
                        endDate = endDate,
                        today = today,
                        description = current.description,
                        now = instant(),
                    )
                    state.update { it.copy(isSaving = false, savedScheduleId = id) }
                } else {
                    // Not `createSchedule`, ever: an edit that inserted would leave the household with
                    // two schedules and a "changed" one that was not the one they changed.
                    val rewritten = repository.updateSchedule(
                        id = editing,
                        name = name,
                        categoryId = category.id,
                        amountPaise = amountPaise,
                        frequency = current.frequency,
                        intervalCount = intervalCount,
                        startDate = current.startDate,
                        endDate = endDate,
                        today = today,
                        description = current.description,
                        now = instant(),
                    )
                    // The same id comes back as the "saved" one, so the screen leaves on success
                    // exactly as it does after a create.
                    state.update {
                        if (rewritten) {
                            it.copy(isSaving = false, savedScheduleId = editing)
                        } else {
                            it.copy(isSaving = false, saveError = RecurringSaveError.ScheduleMissing)
                        }
                    }
                }
            } catch (refused: IllegalArgumentException) {
                // The repository refused the write as impossible. From this screen that can only mean
                // the category - the picker offers only active expense categories, and categories are
                // never deleted - because everything else was validated above with the same rules.
                state.update { it.copy(isSaving = false, saveError = RecurringSaveError.CategoryUnavailable) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(isSaving = false, saveError = RecurringSaveError.Failed(failure)) }
            }
        }
    }

    /** Opens the "new category" dialog, which only the category picker offers. */
    fun openCategoryDialog() {
        state.update { it.copy(categoryDialog = CategoryDialogState()) }
    }

    fun dismissCategoryDialog() {
        state.update { it.copy(categoryDialog = null) }
    }

    fun onCategoryNameChange(value: String) {
        state.update { current ->
            val dialog = current.categoryDialog ?: return@update current
            current.copy(categoryDialog = dialog.copy(nameInput = value, error = null))
        }
    }

    /**
     * Creates the expense category named in the dialog and selects it for the schedule being written.
     *
     * The name stored on the selection is the *normalised* one, produced by the same
     * [CategoryEntity.normalizeName] the repository used to write the row, so the form shows exactly
     * what the database holds rather than what was typed.
     *
     * The type is always [TransactionType.EXPENSE]: a recurring schedule is an expense, so its picker
     * never offers - and this method can never create - an income category. That is what keeps an
     * income category from being reached through the recurring flow at all.
     */
    fun createCategory() {
        val dialog = state.value.categoryDialog ?: return
        if (dialog.isSaving) return

        if (dialog.nameInput.isBlank()) {
            state.update {
                it.copy(categoryDialog = dialog.copy(error = CategoryDialogError.BlankName))
            }
            return
        }

        state.update { it.copy(categoryDialog = dialog.copy(isSaving = true, error = null)) }

        val name = dialog.nameInput
        viewModelScope.launch {
            try {
                val id = categories.create(name = name, type = TransactionType.EXPENSE)
                state.update {
                    it.copy(
                        category = CategoryOption(id = id, name = CategoryEntity.normalizeName(name)),
                        showCategoryError = false,
                        categoryDialog = null,
                    )
                }
            } catch (duplicate: SQLiteConstraintException) {
                state.update { it.withDialogError(CategoryDialogError.Duplicate) }
            } catch (blank: IllegalArgumentException) {
                // Defence in depth: the blank check above should have caught this first.
                state.update { it.withDialogError(CategoryDialogError.BlankName) }
            } catch (failure: Exception) {
                state.update { it.withDialogError(CategoryDialogError.Failed(failure)) }
            }
        }
    }

    /**
     * Reads the schedule being changed, once, and fills the form in from it.
     *
     * Once, deliberately: the household is about to type into these fields, and a live read would let
     * the database overwrite the half-written edit under their hands.
     *
     * The read is the repository's own observable projection, taken once with `first()`, because that
     * is the query that resolves the category's *name* beside the schedule's figures. A row filed under
     * a category that has since been retired therefore still shows the name it was filed under, and the
     * name is not invented here - it is the one the join returned.
     */
    private fun loadForEdit() {
        val id = editingId ?: return

        viewModelScope.launch {
            state.update { it.copy(editLoad = EditLoadState.Loading) }

            val row = try {
                repository.observeSchedule(id).first()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(editLoad = EditLoadState.Failed(failure)) }
                return@launch
            }

            state.update { current ->
                if (row == null) {
                    current.copy(editLoad = EditLoadState.NotFound)
                } else {
                    current.copy(
                        editLoad = EditLoadState.Ready,
                        name = row.name,
                        description = row.description.orEmpty(),
                        // Written back the way the household would have *typed* it, so saving an
                        // unchanged schedule stores exactly the same paise again.
                        amountInput = MoneyInput.toEditableText(row.amountPaise),
                        category = CategoryOption(
                            id = row.categoryId,
                            name = row.categoryName,
                            iconKey = row.categoryIconKey,
                        ),
                        frequency = row.frequency,
                        intervalInput = row.intervalCount.toString(),
                        startDate = row.startDate,
                        endDate = row.endDate,
                    )
                }
            }
        }
    }

    /**
     * The active expense categories, newest read first and already retired-free.
     *
     * `catch` ends the flow on failure, so a broken read becomes a message with a retry rather than a
     * spinner that never stops; [retryCategories] restarts it.
     */
    private fun observeActiveExpenseCategories(): Flow<CategoriesUiState> =
        categories.observeByType(TransactionType.EXPENSE)
            .map<List<CategoryEntity>, CategoriesUiState> { rows ->
                CategoriesUiState.Ready(
                    rows.filter { it.isActive }
                        .map { CategoryOption(id = it.id, name = it.name, iconKey = it.iconKey) },
                )
            }
            .onStart { emit(CategoriesUiState.Loading) }
            .catch { failure -> emit(CategoriesUiState.Failed(failure)) }

    /** Records a dialog failure only while the dialog is still open. */
    private fun RecurringFormUiState.withDialogError(error: CategoryDialogError) =
        copy(categoryDialog = categoryDialog?.copy(isSaving = false, error = error))

    companion object {

        /**
         * Builds the form against the application's own repositories.
         *
         * @param editingId the schedule to change, or `null` to create one.
         */
        fun factory(editingId: Long? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "RecurringFormViewModel needs the Application to reach the repositories" }

                val app = application as HomeFinanceApp
                RecurringFormViewModel(
                    repository = app.recurringRepository,
                    categories = app.categoryRepository,
                    editingId = editingId,
                )
            }
        }

        /**
         * Builds the form over [repository] and [categories] with every external fact pinned.
         *
         * The seam a device test uses so that the real screens can be driven against an in-memory
         * database rather than the household's own file, and the one a JVM test uses to pin the day.
         */
        fun factory(
            repository: RecurringRepository,
            categories: CategoryRepository,
            today: LocalDate,
            editingId: Long? = null,
            instant: () -> Instant = Instant::now,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RecurringFormViewModel(
                    repository = repository,
                    categories = categories,
                    today = today,
                    editingId = editingId,
                    instant = instant,
                )
            }
        }
    }
}
