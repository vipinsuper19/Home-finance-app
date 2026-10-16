# Home Finance

A simple, offline-first Android application for managing household income, expenses, recurring payments, and monthly finances.

Home Finance is being built to make everyday financial tracking easier, including daily household expenses such as milk, monthly bills such as rent, and income received from tenants or other sources.

> **Development status:** MVP in progress. The application shell and initial local database foundation are being developed. Full financial transaction management and reporting are planned.

## Features

### Implemented

- Native Android application built with Kotlin.
- Modern UI using Jetpack Compose and Material 3.
- Home, Transactions, Recurring, Reports, and Settings destinations.
- Emerald-green visual theme.
- Offline-first application architecture.
- Room database foundation with schema export.
- Application-scoped, lazy database initialization.
- Unit tests for currency formatting, navigation destinations, placeholder content, and home screen state.
- No backend or account registration required for the current architecture.

### Planned

- Record income and expenses.
- Categorize financial transactions.
- Track daily milk purchases using quantity and rate.
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
│   │   │   │   ├── recurring/
│   │   │   │   └── reports/
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
| Task 2.2 | Core financial entities, DAOs, and validation | In progress |
| Task 3 | Transaction management UI | Planned |
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
