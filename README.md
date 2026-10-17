# Home Finance

A simple, offline-first Android application for managing household income, expenses, recurring payments, and monthly finances.

Home Finance is being built to make everyday financial tracking easier, including daily household expenses such as milk, monthly bills such as rent, and income received from tenants or other sources.

> **Development status:** MVP in progress. The application shell, the local financial data layer,
> and the transaction list and add form are in place and verified. Editing, reporting, and export
> are planned.

## Features

### Implemented

- Native Android application built with Kotlin.
- Modern UI using Jetpack Compose and Material 3.
- Home, Transactions, Recurring, Reports, and Settings destinations.
- Emerald-green visual theme.
- Offline-first application architecture.
- Room database foundation with schema export.
- Application-scoped, lazy database initialization.
- Income/expense transaction and category tables, with tested version 1 to version 2 and version 2 to version 3 migrations.
- DAOs for categories and transactions, plus a repository layer that validates amounts, category references, and category types.
- A transaction list grouped under day headings, with a search box, all/income/expense filters and a month selector, showing each entry's category, description and amount in the colour of its direction.
- Add income and add expense forms with exact paise parsing, validation, a date picker, and optional description and notes.
- A separate category picker screen, listing the categories of the current type and offering to create one when there are none yet.
- Optional quantity-and-rate entry for things bought by weight or volume, such as milk, with the amount derived from the two figures and stored alongside them.
- Unit tests for currency formatting, navigation destinations, placeholder content, home screen state, type conversion, category naming, repository validation, money and quantity input parsing, date conversion, transaction list filtering and grouping, category mark selection, and the add-transaction form state.
- Instrumented tests against an in-memory Room database for DAO behaviour, migration safety, and the transaction list, add-transaction form and category picker screens.
- No backend or account registration required for the current architecture.

### Planned

- Edit and delete existing transactions.
- Manage categories: rename, retire, and restore them.
- Track daily milk purchases over time, now that a single purchase can be recorded by quantity and rate.
- Manage recurring household expenses.
- Track monthly rental income.
- Record expected and actual rent receipts, including partial payments and outstanding balances.
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
│   │   │   │   │   └── entity/
│   │   │   │   └── repository/
│   │   │   ├── feature/
│   │   │   │   ├── home/
│   │   │   │   ├── transactions/
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
- Preserve historical financial records when categories or recurring settings change.
- Do not delete existing user data through destructive database migrations.
- Use database migrations when the schema changes.
- Do not insert demonstration transactions into the user's real financial records.
- Test calculations, validation, and persistence independently of the UI.

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

Database migrations and backup/restore behavior will be implemented and tested as the application evolves.

**Privacy note:** Local-first storage does not automatically guarantee that every data path is private or excluded from device backup. Review Android backup rules and export behavior before using the application for sensitive financial records.

## Development Roadmap

| Task | Scope | Status |
|---|---|---|
| Task 1 | Project setup, app shell, navigation, and UI foundation | Completed |
| Task 2.1 | Room configuration and initial schema | Completed |
| Task 2.2 | Core financial entities, DAOs, and validation | Completed |
| Task 3.1 | Transaction list and UI state | Completed |
| Task 3.2 | Add income and expense forms | Completed |
| Task 3.3 | Edit and delete transactions | Planned |
| Task 4 | Recurring expense engine | Planned |
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
