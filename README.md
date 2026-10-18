# Home Finance

A simple, offline-first Android application for managing household income, expenses, recurring payments, and monthly finances.

Home Finance is being built to make everyday financial tracking easier, including daily household expenses such as milk, monthly bills such as rent, and income received from tenants or other sources.

> **Development status:** MVP in progress. The application shell, the local financial data layer, the
> transaction list, add form, details, edit form and delete confirmation, and the recurring expense
> engine with its schedules, occurrences and payment recording are in place and verified. Reporting,
> reminders and export are planned.

## Features

### Implemented

- Native Android application built with Kotlin.
- Modern UI using Jetpack Compose and Material 3.
- Home, Transactions, Recurring, Reports, and Settings destinations.
- Emerald-green visual theme.
- Offline-first application architecture.
- Room database foundation with schema export.
- Application-scoped, lazy database initialization.
- Income/expense transaction and category tables, with tested version 1 to version 2, version 2 to version 3 and version 3 to version 4 migrations.
- DAOs for categories and transactions, plus a repository layer that validates amounts, category references, and category types.
- A transaction list grouped under day headings, with a search box, all/income/expense filters and a month selector, showing each entry's category, description and amount in the colour of its direction.
- Add income and add expense forms with exact paise parsing, validation, a date picker, and optional description and notes.
- A separate category picker screen, listing the categories of the current type and offering to create one when there are none yet.
- Optional quantity-and-rate entry for things bought by weight or volume, such as milk, with the amount derived from the two figures and stored alongside them.
- A transaction details screen, opened by tapping a ledger row, showing the type, category, date, amount, optional quantity-and-rate, notes and the audit timestamps, with clear Edit and Delete actions.
- Edit of an existing transaction through the same form the add screen uses, prefilled from the stored row and updating it in place: the row keeps its identifier and the day it was first recorded, and only the day it was last changed is re-stamped.
- Delete with a confirmation dialog that says the removal cannot be undone, reporting a failure without removing anything and letting the list update itself from the database.
- Recurring expense schedules for daily, weekly, monthly and yearly rules with a configurable interval, an optional end date, and a monthly/yearly date rule that clamps to the end of a short month without drifting.
- A recurring list showing each schedule's name and description, category, expected amount, frequency, next due date and active or paused state, with an overdue marker that does not rely on colour alone, and loading, empty, error and retry states.
- A recurring form for creating and editing schedules, reusing the ledger's exact rupee-to-paise parsing and its category picker, validating the name, amount, interval, dates and category, and selectable only from active expense categories.
- Generated recurring occurrences: the Recurring tab is opened, the dates the rule implies up to a bounded planning horizon are created, and a database unique index on (schedule, due date) makes a repeated, interrupted or concurrent run harmless.
- Marking a recurring occurrence paid writes exactly one expense through the ordinary transaction path, links it to the occurrence and files it under the schedule's category, inside one database transaction and only if the occurrence is still pending.
- Skipping an occurrence, pausing and resuming a schedule, and stopping a schedule for good: none of them write an expense, none of them delete anything, and pausing then resuming does not back-fill or duplicate dates.
- Unit tests for currency formatting, navigation destinations, placeholder content, home screen state, type conversion, category naming, repository validation, money and quantity input parsing and their edit round trips, date and timestamp formatting, transaction list filtering and grouping, category mark selection, the add, edit and details screen states, the recurrence calculator, the recurring list, form, details and occurrence view models, and the shared occurrence payment and skip state machine.
- Instrumented tests against an in-memory Room database for DAO behaviour, migration safety, the recurring unique index and foreign keys, the recurring repository and generator including concurrent and failed payments, and the transaction list, add-transaction form, edit-transaction form, category picker, transaction details, recurring list, recurring form, recurring details and occurrence history screens.
- No backend or account registration required for the current architecture.

### Planned

- Manage categories: rename, retire, and restore them.
- Track daily milk purchases over time, now that a single purchase can be recorded by quantity and rate.
- Track monthly rental income.
- Record expected and actual rent receipts, including partial payments and outstanding balances.
- Recurring income schedules, now that recurring expenses are in place.
- View monthly income, expenses, and net balance.
- Schedule reminders for recurring payments.
- Export financial records and support backup and restore.
- Add validation and tests for financial calculations and data integrity.

Features will be marked as implemented as they are completed and verified.

## Technology Stack

| Technology | Purpose |
|---|---|
| Kotlin | Primary programming language |
| Jetpack Compose | Declarative Android UI |
| Material 3 | UI components and design system |
| AndroidX Navigation Compose | App navigation |
| Room | Local SQLite database |
| KSP | Kotlin annotation processing for Room |
| Kotlin Coroutines and Flow | Asynchronous operations and observable data |
| AndroidX Lifecycle | ViewModel and lifecycle integration |
| Gradle | Build automation |

## Architecture

The application follows a layered, offline-first architecture.

```text
Android Application
        |
        v
Jetpack Compose UI
        |
        v
ViewModels / UI State
        |
        v
Repository Layer
        |
        v
Room Database
        |
        v
Local SQLite Storage
```

The intended structure separates UI, application state, data access, and persistence so that features can be developed and tested independently.

### Project structure

```text
app/
├── src/
│   ├── main/
│   │   ├── java/com/homefinance/app/
│   │   │   ├── core/
│   │   │   │   ├── common/
│   │   │   │   ├── ui/
│   │   │   │   │   ├── component/
│   │   │   │   │   └── theme/
│   │   │   │   └── util/
│   │   │   ├── data/
│   │   │   │   ├── local/
│   │   │   │   │   ├── dao/
│   │   │   │   │   ├── entity/
│   │   │   │   │   ├── migration/
│   │   │   │   │   └── relation/
│   │   │   │   ├── model/
│   │   │   │   └── repository/
│   │   │   ├── feature/
│   │   │   │   ├── home/
│   │   │   │   ├── transactions/
│   │   │   │   ├── transactiondetail/
│   │   │   │   ├── addtransaction/
│   │   │   │   ├── recurring/
│   │   │   │   ├── reports/
│   │   │   │   └── settings/
│   │   │   ├── navigation/
│   │   │   ├── HomeFinanceApp.kt
│   │   │   └── MainActivity.kt
│   │   └── res/
│   ├── test/
│   └── androidTest/
├── schemas/
└── build.gradle.kts
```

This illustrates the intended organization; the exact contents may evolve as implementation progresses.

## Requirements

- Android Studio with a compatible Android SDK.
- JDK compatible with the project's Gradle and Android Gradle Plugin configuration.
- Git.
- An Android device or emulator for runtime testing.

Use the versions configured in the repository rather than independently upgrading Gradle, Kotlin, the Android Gradle Plugin, or other dependencies.

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/vipinsuper19/Home-finance-app.git
cd Home-finance-app
```

### 2. Open the project

Open the project root in Android Studio and allow Gradle synchronization to complete.

If Gradle synchronization fails, check the distribution URL in:

```text
gradle/wrapper/gradle-wrapper.properties
```

The wrapper must use a distribution URL accessible from your machine. Do not rely on a local file path belonging to another developer's computer.

### 3. Build the debug APK

On Windows:

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

On macOS or Linux:

```bash
./gradlew :app:assembleDebug --console=plain
```

The expected debug APK output location is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 4. Run unit tests

Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

macOS or Linux:

```bash
./gradlew :app:testDebugUnitTest --console=plain
```

### 5. Build and validate the complete debug test setup

Windows:

```powershell
.\gradlew.bat clean :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --console=plain
```

This builds the debug application, compiles the Android instrumentation-test APK, and runs local unit tests. Instrumented tests require a compatible connected device or emulator to execute.

## Financial Data Rules

The following rules guide financial feature development:

- Store monetary amounts as integer paise using `Long`.
- Avoid floating-point values for financial calculations.
- Store a typed quantity as integer thousandths and a rate as integer paise per unit, so a purchase by weight or volume is recorded as the two figures the household actually saw rather than only as a total.
- Keep expected amounts distinct from actual payments.
- Treat a recurring schedule as a plan rather than a payment: generating a due date must never insert a transaction or move a total.
- Never mark a scheduled occurrence paid merely because its due date has passed.
- Preserve historical financial records when categories or recurring settings change.
- Do not delete existing user data through destructive database migrations.
- Use database migrations when the schema changes.
- Do not insert demonstration transactions into the user's real financial records.
- Test calculations, validation, and persistence independently of the UI.

## Recurring Expenses

A recurring expense is a **plan**, not a payment. Three separate things are stored, and keeping them apart
is what the feature is built on:

1. a **schedule** - "shop rent, ₹15,000, monthly on the 5th, from 5 October 2026";
2. a **scheduled occurrence** - one date the rule falls on, such as 5 November 2026. Generating it writes no
   ledger entry and moves no total;
3. an **actual transaction** - the money itself, written only when the household records the payment, and
   linked back to the occurrence it settles.

Because of that split, a bill falling due never changes a household's expenses, and a date that passes while
the phone is off is still sitting there unpaid rather than quietly paid.

### Frequencies and dates

- Daily, weekly, monthly and yearly, each with a whole-number interval of 1 to 1000 - "every 2 weeks", "every
  3 months".
- Daily and weekly periods add days. Monthly and yearly periods use **calendar** arithmetic anchored on the
  start date's day of month; no period is ever a fixed number of days.
- The anchor never drifts. A monthly schedule starting on the 5th falls on the 5th, and each period is counted
  from the original start date rather than from the previous, possibly clamped, date.
- Month-end clamping is applied to each date and never moves the anchor: a schedule starting on 31 January
  gives 31 January, then 28 February (29 in a leap year), then 31 March, then 30 April, then 31 May.
- A yearly schedule anchored on 29 February falls on 28 February in the non-leap years and returns to
  29 February in the next leap year.
- Dates are computed and compared as local calendar dates, never as strings, so a time-zone change cannot shift
  which day a bill falls on.
- The start date is the first date of the rule and is included. The optional end date is included too, and
  nothing is ever generated after it. With no end date the rule continues until it is paused or stopped.

### Generating occurrences

- Occurrences are generated when the Recurring tab is opened. There is no background job and no reminder.
- Only the dates the rule implies, up to today plus a **90-day planning horizon**, are created, so the list can
  show what is coming without generating years of dates nobody asked for.
- A schedule records how far it has been generated, and that progress is written only after the rows are
  committed, so an interrupted or repeated run does no harm.
- The unique index on `(schedule_id, due_date)` is the final authority: a second run, a restart or a concurrent
  run can only fail to insert a row that is already there.
- A paid or skipped occurrence is never generated again.
- Catching up on a missed period creates expectations only. It never writes a transaction.

### Recording a payment

- "Mark paid" states the schedule's name, its category, the due date and the expected amount before anything is
  written, so a payment is confirmed rather than assumed.
- The expected amount is pre-filled and can be corrected to what was actually paid. The corrected figure goes
  through the same exact rupee-to-paise parsing as the ledger's own form, and the occurrence keeps its expected
  amount either way.
- The expense is filed under the schedule's category and dated the occurrence's due date, and the occurrence is
  linked to it.
- The whole thing runs in one database transaction and the occurrence is updated only if it is still pending, so
  a double tap, a retry or a replay records exactly one expense. The dialog stays open and says the occurrence
  was already settled.
- A failure is reported as a failure: the dialog does not close and the typed figure is kept.
- Skipping records no expense at all. The occurrence is kept, so it is never generated again, and no total moves.

### Pausing, resuming and stopping

- **Pausing** stops the schedule generating anything new. Nothing is deleted: the dates already generated stay
  payable and skippable.
- **Resuming** continues from today. Dates that fell inside the pause are deliberately not back-filled, so
  pausing and resuming repeatedly cannot produce a pile of overdue bills or a duplicate row.
- **Stopping** retires a schedule for good. It is one-way by design, so it asks first, and the schedule and its
  whole history stay exactly where they are.
- **Editing** keeps the schedule's identity and the day it was first recorded, keeps every occurrence that
  already exists - paid, skipped or pending - and generates only the dates that are missing. Changing the
  expected amount never rewrites a recorded payment.
- A schedule under a category that has since been retired can still be edited and paid. The picker does not
  offer retired categories, and a category switched off between choosing it and saving is refused with an
  explanation rather than saved under a category the household cannot see.

### Known limitations

- Recurring schedules are **expenses only**; recurring income is not modelled yet.
- There are no reminders or notifications and no background generation, so dates appear when the tab is opened.
- A payment is posted on the occurrence's due date; there is no way to date it differently.
- Occurrences are never deleted, only resolved, and a stopped schedule cannot be restarted.
- Deleting a recorded payment from the ledger leaves the occurrence saying it was paid and marks the link as
  gone. It does not reopen the occurrence or make the date generatable again.

## Database and Privacy

The application is designed around local storage without requiring a backend for its core financial features.

The Room database filename is:

```text
home_finance.db
```

Room schema exports are maintained under:

```text
app/schemas/
```

Schema versions 1 to 4 are exported, with a tested migration for every step: version 1 to 2 introduced the
financial tables, version 2 to 3 added quantity and rate, and version 3 to 4 added the recurring schedule and
occurrence tables with their indices. Every migration is asserted to preserve the rows already on the device.
Backup and restore behavior will be implemented and tested as the application evolves.

**Privacy note:** Local-first storage does not automatically guarantee that every data path is private or excluded from device backup. Review Android backup rules and export behavior before using the application for sensitive financial records.

## Development Roadmap

| Task | Scope | Status |
|---|---|---|
| Task 1 | Project setup, app shell, navigation, and UI foundation | Completed |
| Task 2.1 | Room configuration and initial schema | Completed |
| Task 2.2 | Core financial entities, DAOs, and validation | Completed |
| Task 3.1 | Transaction list and UI state | Completed |
| Task 3.2 | Add income and expense forms | Completed |
| Task 3.3 | Edit and delete transactions | Completed |
| Task 4 | Recurring expense engine | Completed |
| Task 5 | Milk tracking and rental income management | Planned |
| Task 6 | Reminders and local notifications | Planned |
| Task 7 | Reports, export, and backup/restore | Planned |
| Task 8 | Comprehensive testing and release preparation | Planned |

Statuses should be updated when each task has been implemented and verified.

## Testing Strategy

The project uses unit tests for logic and Android instrumentation tests where appropriate.

Testing will cover:

- Currency formatting and monetary calculations.
- Financial transaction validation.
- Category and transaction persistence.
- Database migration safety.
- Recurring schedule generation.
- Date-range filtering.
- Monthly income, expenses, and balances.
- Backup and restore integrity.
- Navigation and UI regression behavior.

## Contributing

This project is under active development.

Before making changes:

1. Review the existing architecture and development conventions.
2. Keep changes limited to the assigned task.
3. Preserve existing tests and database migration history.
4. Run the relevant build and test commands.
5. Document actual results and unresolved issues.

## License

No license has been specified yet. All rights remain with the respective copyright holder unless a license is added to this repository.

## Repository

GitHub: https://github.com/vipinsuper19/Home-finance-app
