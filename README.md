# Home Finance

A simple, offline-first Android application for managing household income, expenses, recurring payments, and monthly finances.

Home Finance is being built to make everyday financial tracking easier, including daily household expenses such as milk, monthly bills such as rent, and income received from tenants or other sources.

> **Development status:** MVP in progress. The application shell, the local financial data layer, the
> transaction list, add form, details, edit form and delete confirmation, and the recurring schedule
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
- Application-scoped database initialization, created on first use rather than while the application object is being constructed, with one deliberate exception: the single start-up run that writes the categories the app ships with.
- Income/expense transaction and category tables, with tested version 1 to version 2, version 2 to version 3, version 3 to version 4, version 4 to version 5 and version 5 to version 6 migrations.
- DAOs for categories, transactions, recurring schedules and application settings, plus a repository layer that validates amounts, category references, and category types.
- A transaction list grouped under day headings, with a search box, all/income/expense filters and a month selector, showing each entry's category, description and amount in the colour of its direction.
- Add income and add expense forms with exact paise parsing, validation, a date picker, and optional description and notes.
- A separate category picker screen, listing the categories of the current type and offering to create one when there are none yet.
- Twelve starter categories - milk, groceries, vegetables, home rent, shop rent, electricity, gas, internet, medical, transport, investments and salary - written once on the first start and then left alone: they can be renamed, retired or ignored like any other category, and no later start brings one back.
- A category mark drawn with Compose Material icons rather than hand-drawn paths: a glyph on its own fixed tint and background, chosen by the category's stored icon key where one has been recorded and derived from its name otherwise, so a category the household adds still gets a sensible picture without being asked for one.
- Optional quantity-and-rate entry for things bought by weight or volume, such as milk, with the amount derived from the two figures and stored alongside them.
- A transaction details screen, opened by tapping a ledger row, showing the type, category, date, amount, optional quantity-and-rate, notes and the audit timestamps, with clear Edit and Delete actions.
- Edit of an existing transaction through the same form the add screen uses, prefilled from the stored row and updating it in place: the row keeps its identifier and the day it was first recorded, and only the day it was last changed is re-stamped.
- Delete with a confirmation dialog that says the removal cannot be undone, reporting a failure without removing anything and letting the list update itself from the database.
- Recurring schedules for daily, weekly, monthly and yearly rules with a configurable interval, an optional end date, and a monthly/yearly date rule that clamps to the end of a short month without drifting. A schedule's direction is its **category's** own type, so the same feature covers a rent payment and a salary: there is no separate "recurring income" to model, and a schedule cannot disagree with the category it files its payments under.
- A recurring list filtered by All, Expense or Income, each row showing the category's mark, the schedule's name, its frequency and amount - or its unit price, when the household priced the thing by weight or volume - and the next due date, with an overdue marker that does not rely on colour alone, plus loading, empty, no-matches, error and retry states.
- A recurring form for creating and editing schedules, reusing the ledger's exact rupee-to-paise parsing and its category picker, offering the same Income/Expense choice as the add-transaction form, and validating the name, amount, unit price, interval, dates and category; the categories on offer are the active ones of the chosen direction, and changing the direction clears a category that no longer belongs to it.
- Generated recurring occurrences: the Recurring tab is opened, the dates the rule implies up to a bounded planning horizon are created, and a database unique index on (schedule, due date) makes a repeated, interrupted or concurrent run harmless. The engine runs when Home is opened as well, because Home is the screen that draws what is due; it creates expectations only, never a ledger entry, so no total moves - "recurring payments due today are not today's spending".
- A recurring details screen stating the schedule's name, frequency and direction, its expected figure, its status, the next payment with how far off it is, the rule behind it, a paid/upcoming/overdue summary and the newest few payments, with Edit beside the facts it changes and Pause, Resume and Stop in the top bar's overflow menu.
- Marking a recurring occurrence paid writes exactly one entry in the category's direction through the ordinary transaction path, links it to the occurrence and files it under the schedule's category, inside one database transaction and only if the occurrence is still pending - confirmed from a bottom sheet that states the date, the expected figure, the category and the description the entry will carry and reuses the ledger form's own amount field.
- Skipping an occurrence, pausing and resuming a schedule, and stopping a schedule for good: none of them write a transaction, none of them delete anything, and pausing then resuming does not back-fill or duplicate dates.
- The Home dashboard's recurring section, read from the household's own occurrences rather than from sample data: what is late, what is due today and what is coming, each group counted and each date showing the schedule's name, the category's mark, how the date comes round, the amount that was expected on *that* date and how far off it is in words ("3 days overdue", "Due in 5 days"). Overdue dates are drawn first and are never trimmed; upcoming dates are previewed five at a time with the heading still stating how many there are in all; a state with nothing to show says "Nothing due today" rather than drawing a blank, a household with no schedules at all is invited to add one, and the section carries its own loading, failure and retry states without taking the rest of the dashboard down with it.
- A Reports dashboard for a chosen monthly window - This Month, 3 Months, 6 Months or 1 Year - opening with the current month, stepping backwards and forwards by its own length and refusing to step past the month the device is in, with the window named in words and the current month marked as still running. Its figures are exact integer paise read from the household's own ledger: income, expenses, net cash flow, the count of entries behind each figure, a savings rate and a comparison against the previous window of the same length - each shown only when it says something true, so a window with no income states no savings rate and a window with nothing to compare against shows no badge.
- An income-against-expenses chart and an Expenses by Category ring, both drawn from the same reads as the summary above them, with the ring listing every category largest-first, each naming what it came to and its share, each one tappable. For a one-month window the chart is bucketed by week; for longer windows by month. Empty directions and empty windows are stated in words rather than drawn as zeroes, and the five largest entries of the window are listed and open their own details.
- A monthly trend covering the six months ending with the window's last month, so the month being read is always the right-hand bar, and a category drill-down opened by tapping a category that reports, for the same window, what that category came to, how many entries made it up, their average, its share of that direction's total, its own trend, its largest entries and every entry grouped under the day it was recorded - each openable.
- A Settings dashboard with four groups - App & Appearance, Data & Backup, Manage Data, and App Settings - every row a real control: theme, language, currency and date format are choice screens with the current value named and selected; backup, restore, export, import and category management open their own working screens; the three behaviour switches and "Clear app data" live on the dashboard and the App Settings screen respectively. There is no profile header, because the app has no accounts, and no notification switches, because there is no notification workflow.
- Theme selection between Light, Dark and System Default, applied app-wide including dialogs, cards and the status and navigation bar icons, persisted and restored on the next start.
- A date-format preference (`5 Nov 2026`, `05/11/2026`, `2026-11-05`) with a live preview of today's date, applied through the one formatter every screen already uses; stored dates and the CSV contract are unchanged by it.
- Preferences for showing or hiding the Home balance, confirming before a single transaction is deleted, and haptic feedback, all stored in the existing `app_setting` table and applied without a restart. Clear app data and restore always confirm regardless of the delete switch.
- A versioned JSON backup written to a destination chosen through the system file picker, carrying categories, transactions, schedules, occurrences and display preferences with their ids preserved, and a restore that validates the file, states what it would replace, and replaces everything in one transaction or nothing at all.
- CSV export and a two-step import: a header and RFC 4180 escaping, ISO dates and exact decimal amounts; a preview naming ready rows, duplicates and refused rows with their line numbers; a documented duplicate policy; and one transaction for the confirmed write.
- Category management for renaming, retiring and restoring existing categories, reusing the same repository and leaving creation where it already lives - the transaction form's picker.
- Unit tests for currency formatting, navigation destinations, placeholder content, home screen state, the dashboard's classification of a date as overdue, due today or upcoming and its preview limit, type conversion, category naming, category mark keys and the list of categories the app ships with, the one-off category seeder, the category mark palette, repository validation, money and quantity input parsing and their edit round trips, date and timestamp formatting, transaction list filtering and grouping, the add, edit and details screen states, the recurrence calculator, the recurring list, form, details and occurrence view models, the shared occurrence payment and skip state machine, and the report window arithmetic, daily-to-weekly and daily-to-monthly folding, category shares and percentages, axis scaling, and the Reports and category drill-down view models including their refresh after a write.
- Unit tests for the settings model and its defaults, each preference key and its defensive parsing, the three date layouts and the process-wide style, CSV encoding and parsing including escaping and currency refusal, the import's preview, duplicate and commit rules, backup validation and a full round trip over the fakes, clear-data ordering and its seeder flag, the Settings view model's writes, failures and clear progress, the Home balance flag, and the delete-confirmation preference.
- Instrumented tests against an in-memory Room database for DAO behaviour, migration safety through every step to the current schema, the unique category-name index as both an insert and an ignore, the recurring unique index and foreign keys, the outstanding-occurrence read behind the Home dashboard and its schedule count, category seeding including its rollback and its idempotence, the recurring repository and generator including concurrent and failed payments, and the transaction list, add-transaction form, edit-transaction form, category picker, transaction details, recurring list, recurring form, recurring details, occurrence history and Home dashboard sections, and the report aggregate and listing queries, the reports repository against a real database and both report screens.
- Instrumented tests for preference persistence across a reopened database, clear-data consistency, a backup and restore round trip with real foreign keys, the rollback of a restore that fails part-way, CSV export and import against real tables, and the Settings dashboard's sections, rows, switches and tap targets.
- No backend or account registration required for the current architecture.

### Planned

- Track daily milk purchases over time, now that a single purchase can be recorded by quantity and rate.
- Track monthly rental income.
- Record expected and actual rent receipts, including partial payments and outstanding balances.
- Schedule reminders for recurring payments. This needs a notification workflow first: there are no notification switches
  in Settings for exactly that reason.
- Automatic or scheduled backups, and a cloud destination. Backups are manual today, and go wherever the household sends
  them from the system file picker.
- Translations, which need a populated `values-*` directory before the Language screen can offer them.

Features will be marked as implemented as they are completed and verified.

## Technology Stack

| Technology | Purpose |
|---|---|
| Kotlin | Primary programming language |
| Jetpack Compose | Declarative Android UI |
| Material 3 | UI components and design system |
| Material Icons (extended) | The glyphs drawn as category marks |
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
│   │   │   │   │   ├── icon/
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
- Derive reporting figures from the ledger rather than from a second set of rules: income is the sum of the
  entries of type income in the window, expenses the sum of the entries of type expense, and net cash flow is
  income minus expenses. A report is a reading of what was recorded, not a separate account.
- Decide every calendar rule in Kotlin against `LocalDate`: which day a week starts on, which month a date is in,
  and how a longer window is folded into bars. SQLite sums money and groups rows; it is never asked what a month
  is, so a report boundary is the household's own local day rather than the database server's.
- Treat a recurring schedule as a plan rather than a payment: generating a due date must never insert a transaction or move a total.
- Take a recurring schedule's direction from its category's own type rather than from a property of the schedule, so a schedule cannot record income as an expense or the other way round.
- Never mark a scheduled occurrence paid merely because its due date has passed.
- Preserve historical financial records when categories or recurring settings change.
- Do not delete existing user data through destructive database migrations.
- Use database migrations when the schema changes.
- Do not insert demonstration transactions into the user's real financial records. The categories the app ships with are not records: a category carries no amount and no date, they are written once rather than on every start, and a household that does not want them can retire them and never see them again.
- Test calculations, validation, and persistence independently of the UI.

## Recurring Schedules

A recurring schedule is a **plan**, not a payment. Three separate things are stored, and keeping them apart
is what the feature is built on:

1. a **schedule** - "shop rent, ₹15,000, monthly on the 5th, from 5 October 2026";
2. a **scheduled occurrence** - one date the rule falls on, such as 5 November 2026. Generating it writes no
   ledger entry and moves no total;
3. an **actual transaction** - the money itself, written only when the household records the payment, and
   linked back to the occurrence it settles.

Because of that split, a bill falling due never changes a household's expenses, and a date that passes while
the phone is off is still sitting there unpaid rather than quietly paid.

### Direction comes from the category

There is no "recurring income" feature beside a "recurring expense" one. A schedule points at a category, and
the direction of every payment it produces is that category's own type. Rent comes round and so does a salary;
the recurring tab offers both, filtered by All, Expense or Income, and the schedule's details screen says which
way the money moves in words as well as in colour. The single consequence worth stating plainly is that a
schedule cannot be created under a category it disagrees with, which is what stops a schedule quietly recording
an expense against an income category.

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
- A schedule may record **how the thing is priced** as well as what it expects: an optional unit ("litre") and
  the price of one unit. The two are stored together or not at all, the unit price is descriptive, and the
  expected amount stays the authoritative per-occurrence figure - the household may buy one litre or twenty and
  only they know which, so a payment writes the amount and never the rate.

### Generating occurrences

- Occurrences are generated when the Recurring tab or the Home screen is opened. There is no background job and no reminder.
- Only the dates the rule implies, up to today plus a **90-day planning horizon**, are created, so the list can
  show what is coming without generating years of dates nobody asked for.
- A schedule records how far it has been generated, and that progress is written only after the rows are
  committed, so an interrupted or repeated run does no harm.
- The unique index on `(schedule_id, due_date)` is the final authority: a second run, a restart or a concurrent
  run can only fail to insert a row that is already there.
- A paid or skipped occurrence is never generated again.
- Catching up on a missed period creates expectations only. It never writes a transaction.

### Recording a payment

- "Mark … as Received" (for an income category) or "Mark … as Paid" (for an expense one) opens a bottom sheet
  that states the schedule, the direction, the due date and weekday, the expected amount and the category, and
  the description the entry will carry - so a payment is confirmed rather than assumed.
- The sheet draws the ledger form's own amount field: the expected amount is pre-filled and can be corrected to
  what was actually handed over, through the same exact rupee-to-paise parsing as the ledger. The expected figure
  stays on screen beside the field, so the two are never merged into one number.
- The entry is filed under the schedule's category and dated the occurrence's due date, carries the category's
  type, and is linked to the occurrence it settles.
- The whole thing runs in one database transaction and the occurrence is updated only if it is still pending, so
  a double tap, a retry or a replay records exactly one entry. The sheet stays open and says the occurrence was
  already settled.
- A failure is reported as a failure: the sheet does not close and the typed figure is kept.
- Skipping records no entry at all. The occurrence is kept, so it is never generated again, and no total moves.
- The category picker and date picker are deliberately *not* offered here. The entry is filed under the
  schedule's own category and dated the date the schedule committed to; a control the write would then ignore
  would be worse than no control.

### What the Home dashboard shows

The Home screen's **Recurring Payments** section reads the same occurrences the recurring tab lists, grouped by
how they stand against today:

- **Overdue** first, and never trimmed. These are the dates that want doing something about, so a preview never
  decides for the household which of their own bills matters. Being late is said in words - "3 days overdue" -
  so the reading never depends on telling two container colours apart.
- **Due Today** next: the dates that fall on today exactly, in the amber a household associates with a bill that
  has arrived but is not late.
- **Upcoming** last, soonest first, previewed five at a time. The heading states how many there are **in all**, so
  a preview of five cannot read as "there are only five" - and nothing is lost by the limit, because every date is
  on the recurring tab, one tap away.

Each row is one **date**, not one schedule, so a schedule with several dates waiting has a row for each. The
figure is the occurrence's own - what was expected on *that* day - and the second line says how the date comes
round ("Monthly (on 5th)", "Every 2 weeks (on Tuesday)"), using the date's own day so a clamped month-end date
describes itself rather than the anchor it was clamped from.

Only dates that can still be acted on are drawn. A paid or skipped occurrence is absent because it is finished
with rather than work; nothing else is filtered out. A paused or stopped schedule's waiting dates **are** drawn,
because pausing stops the engine making more dates rather than cancelling the ones already asked for, and the app
goes on offering to record them - a household that stopped a subscription may still owe the final bill. Those rows
say so in their own line ("Monthly (on 5th) · Paused"), so the date reads as the last of a schedule that was
switched off rather than as a fault. A group with nothing in it is *said* - "Nothing due today", "No upcoming
recurring items" - rather than left blank; a household that has never set a schedule up is invited to add one; and
nothing is ever invented to fill the space.

The section is a reading of **expectations**, and it draws its amounts in the ordinary text colour rather than in
the ledger's green and red, which mean money that has moved. Opening Home runs the same generation run the tab
runs, and that run writes expectations only: a bill due today is not today's spending, and no figure above the
section is affected by it.

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

- There are no reminders or notifications - the details screen deliberately does not pretend otherwise - and no
  background generation, so dates appear when Home or the Recurring tab is opened.
- A payment is posted on the occurrence's due date; there is no way to date it differently, because the entry
  is the payment of *that* date.
- Occurrences are never deleted, only resolved, and a stopped schedule cannot be restarted.
- Deleting a recorded payment from the ledger leaves the occurrence saying it was paid and marks the link as
  gone. It does not reopen the occurrence or make the date generatable again.

## Reports

The Reports tab answers three questions about one window and nothing else: what came in, what went out, and what
was left. Every figure on it is read from the household's own transactions - there is no sample data anywhere in
the feature - and a window with nothing in it says so rather than showing zeroes dressed as a chart.

### What the figures mean

- **Income** is the sum of the ledger entries of type income whose date falls inside the window; **expenses** is
  the same for entries of type expense. Both are exact `Long` paise, summed by SQLite, counted so a large figure
  from one entry is distinguishable from the same figure from thirty, and never re-derived by a second rule.
- **Net cash flow** is income minus expenses. It is negative when the window spent more than it took in, and it
  is drawn as a loss rather than left for the reader to subtract.
- **Savings rate** is net cash flow as a whole-number percentage of income, and it is shown only when income is
  greater than zero. A window with no income states no rate at all: "0%" would read as "we saved nothing" when
  the truth is that nothing came in. A negative net is stated in words - how much more was spent than came in -
  because a negative percentage of income is a double negative.
- A **comparison** against the previous window of the same length sits beside each amount, but only when the
  earlier figure was greater than zero. "Up 100%" against a month in which nothing happened is arithmetic
  dressed as news.
- A recurring occurrence that has been generated but not paid is **not** income and **not** an expense. A
  schedule is a plan and only a payment is a fact, so marking one paid moves the report by that one occurrence -
  because that is the moment an entry is written.

### Windows and buckets

- The window is This Month, 3 Months, 6 Months or 1 Year, always a whole number of calendar months ending on a
  calendar month, so "last month" means the same thing whenever it is read.
- Previous and Next step the window by its own length. Next is refused past the month the device is in: there is
  no report for a period that has not happened.
- Boundaries are **device-local calendar days**, inclusive at both ends. A one-month window is bucketed by week
  for the chart, longer windows by month, because fifty-two weekly bars on a phone would be a texture rather than
  a chart.
- The monthly trend covers the six months ending with the window's last month, so the month being read is always
  the right-hand bar, and each bar is labelled.
- A window that holds nothing, a direction that holds nothing, and a read that failed are three different
  states, and each says which one it is.

### Drilling into a category

Tapping a category opens its own report for the same window: what it came to, how many entries made it up and
their average, its share of that direction's total in the window, its own trend, its largest entries, and every
entry grouped under the day it was recorded. The period travels with the tap, so the drill-down opens on the
window the household was already reading rather than on a default one. A category that the database no longer
has is said to be gone rather than to be empty, and its entries are still counted under the name they were filed
under, because retiring a category does not rewrite history.

### Three deliberate departures from the design

The design image this feature was built from draws three summary figures across the screen, an "Others" bucket
and "View All" links. Three departures were made, and they are the only ones:

- The three summary figures are full-width label-and-amount rows inside one card rather than three side-by-side
  cards. An exact figure such as ₹1,24,500.00 does not fit a third of a phone's width, and shortening or
  truncating a household's own total is not acceptable; the rows also survive a large font size.
- There is no "Others" bucket. Every category with spending in the window is listed, largest first, and each one
  is tappable: folding a household's own categories into a bucket would hide exactly the entries they are
  looking for.
- There is no "View All". The five largest entries of the window are listed in full, and the way to see more of
  a category is to tap it, which opens the report that lists every one of its entries.

### Known limitations

- Categories are flat: there is no subcategory taxonomy, so the design's "Top Subcategories" panel became the
  category's own largest entries rather than an invented hierarchy.
- The ledger records the day a transaction belongs to and not the time of day, so an individual entry cannot be
  labelled with the hour it was made. It carries its day instead.
- Reports cover up to a year and end on a calendar month; there is no free-form date range picker, because a
  report whose first and last day had to be reasoned about separately would be a different feature.
- Nothing is exported and nothing is backed up yet - see the roadmap.

## Settings, Backup and Restore

Settings is a real dashboard rather than a placeholder. Each row is a working control, and every value is stored in the
existing `app_setting` key/value table - there is no second preference store, and clearing the app's data clears it with
everything else.

### App and appearance

- **Theme** - Light, Dark or System Default. The chosen mode is applied to every screen, dialog and surface, and the
  status and navigation bar icons follow it, including while System Default is following the device. The default remains
  Light, which is what the app looked like before the setting existed.
- **Language** - the list is built from the languages the app actually ships translations for. Only English has string
  resources today, so the only entry is English and the screen says that translations are pending; it does not offer a
  language that would leave the interface untranslated.
- **Currency** - the list is built from the currencies amounts can actually be recorded in. The ledger records integer
  paise, so INR is the only entry, and the screen states that changing it never converts money. Amounts already stored are
  untouched.
- **Date format** - `5 Nov 2026`, `05/11/2026` or `2026-11-05`, with a live preview of today's date. The choice is applied
  through one formatter used by every screen. Stored dates do not change: a date is still the same calendar day.
- **Show balance on Home** - when off, the Home card does not draw the amount at all; the card, the labels and every other
  figure stay where they were.
- **Confirm before delete** - when off, deleting one transaction acts on the first tap. Restoring a backup and clearing all
  data always ask, whatever this switch says, because neither can be undone.
- **Haptic feedback** - when off, selection controls and the confirmed delete stop ticking. The preference never causes a
  vibration on its own.
- **Clear app data** - reached from App Settings, explained in a dialog, and performed only by the dialog's confirm
  button. Every row is deleted through the database in one transaction - the database file is never deleted - and the
  category seeder's flag is written back so the starter categories do not quietly return. The app is then left with no
  categories, and the add form already offers to create the first one.
- **Notifications** - deliberately absent. The app has no notification workflow to drive a reminder or summary switch, so
  no such switch is shown. It remains future work rather than a control that stores a value nothing reads.

### Backup format

A backup is UTF-8 JSON, written to a destination the household chooses through the system file picker. The app never
chooses a path and never asks for a storage permission. The document opens with its contract:

```json
{
  "format": "home-finance-backup",
  "version": 1,
  "createdAt": "2026-11-05T10:15:30Z",
  "appVersion": "1.0 (1)",
  "categories": [ ... ],
  "transactions": [ ... ],
  "schedules": [ ... ],
  "occurrences": [ ... ],
  "settings": [ ... ]
}
```

Every date is an ISO string, every enum is its name, and every amount is the same integer count of paise the database
stores. Row ids are preserved, because they are the references between a transaction and its category and between an
occurrence and its schedule and payment. The file carries the household's data and display preferences; the two
"last backup" keys are deliberately excluded, because they describe this phone rather than the household's finances.
The last successful backup time and size are recorded only **after** the write returns, so a failed write cannot claim a
backup that does not exist.

### Restore behaviour

A chosen file is decoded and validated before anything is changed. The validation restates the database's own rules:
every reference points at something the file contains, every amount is positive, every date and enum is one this build
understands, and no two rows claim the same key. A file that fails is reported with plain sentences and nothing is
written. A file that passes is summarised and confirmed by the household, then installed inside one transaction: the old
rows are deleted dependents-first (occurrences, transactions, schedules, categories and settings) and the file's rows are
written parents-first (categories, schedules, transactions, occurrences and settings), so every foreign key holds at
every step, and a failure anywhere rolls the whole restore back and leaves the existing data exactly as it was. An
unknown format, a newer version, an unknown field or a damaged file is refused rather than partly read. A restore does
not use a destructive migration.

### CSV export and import

The export writes the documented header and the ledger's real rows:

```text
date,type,category,description,amount,currency,quantity,rate,notes
```

Dates are ISO in the file whatever the display layout is, amounts are plain two-decimal major units with no symbol or
grouping, and text is escaped the RFC 4180 way (commas, quotes and line breaks quoted; embedded quotes doubled). UTF-8
throughout. The success message counts the rows actually written.

The import matches columns by name, so their order does not matter and extra columns are ignored; `date`, `type`,
`category` and `amount` are mandatory. Every value is validated: dates may be ISO, `dd/MM/yyyy` or `d MMM yyyy`; amounts
must be positive exact decimals; the type must be `INCOME` or `EXPENSE`; the category must exist in that direction; and a
currency other than INR is refused rather than relabelled. A preview states how many rows are ready, how many are already
in the ledger, and why each remaining row was refused. Nothing is written until the household confirms, and the confirmed
rows are inserted in one transaction. A duplicate is the same payment recorded twice - same date, direction, category,
amount, description and notes - and duplicates are skipped and counted, never silently dropped.

### Known limitations

- There are no notifications or reminders, and therefore no switches for them.
- Backup is manual. There is no automatic or scheduled backup, and no cloud destination; the file goes wherever the
  household sends it from the system picker.
- Restore replaces the whole data set. It does not merge two households' records, because merging cannot be done without
  inventing rules the household never agreed to.
- Only English strings exist, so the Language screen offers only English until a translated `values-*` directory ships.
- The app records INR only, so the Currency screen offers only INR and never converts.

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

Schema versions 1 to 6 are exported, with a tested migration for every step: version 1 to 2 introduced the
financial tables, version 2 to 3 added quantity and rate, version 3 to 4 added the recurring schedule and
occurrence tables with their indices, version 4 to 5 added a category's stored icon key, and version 5 to 6
added a schedule's optional unit and unit price. Every migration is asserted to preserve the rows already on
the device.

The version 5 to 6 migration adds two nullable columns and nothing else, so no existing schedule is given a unit
price it never had, and every schedule keeps the expected amount it was created with.

The version 4 to 5 migration adds a nullable column and nothing else, so no existing category is given a mark
it never had. The twelve categories the app ships with are written by the app itself, once, on the next start,
and recorded in `app_setting`; a migration is not the place for that, because it would have to invent names a
household may already use. Categories the household writes are stored with no icon key at all and are drawn
from their name instead, which is exactly what every version before 5 did.

Settings preferences, and the two facts about the last successful backup, live in the existing `app_setting` table;
no schema change and no migration were needed to add Settings. A backup is a versioned JSON file written where the
household chooses, and a restore replaces the database's rows inside one transaction - see "Settings, Backup and
Restore" above for the format and its guarantees.

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
| Task 4 | Recurring schedule engine, list, form and details | Completed |
| Task 5 | Milk tracking and rental income management | Planned |
| Task 6 | Reminders and local notifications | Planned |
| Task 7 | Reports, export, and backup/restore | Completed - the Reports tab and its category drill-down, CSV export and import, and versioned backup and restore are implemented and tested |
| Task 7.1 | Settings: theme, language, currency, date format, preferences, category management and clear data | Completed |
| Task 8 | Comprehensive testing and release preparation | Planned |

Statuses should be updated when each task has been implemented and verified.

## Testing Strategy

The project uses unit tests for logic and Android instrumentation tests where appropriate.

Testing will cover:

- Currency formatting and monetary calculations.
- Financial transaction validation.
- Category and transaction persistence.
- Category seeding: that the shipped categories are written once, invent no records, and can be retried safely.
- Database migration safety.
- Recurring schedule generation.
- Date-range filtering.
- Monthly income, expenses, and balances.
- Report aggregation: that income and expenses are summed apart, that a window is inclusive at both ends and
  excludes the days either side of it, that category breakdowns and their percentages are sized against the
  period's own spending, that a quiet day or category is absent rather than a zero, that daily rows add up to the
  summary above them, and that a generated-but-unpaid recurring occurrence reaches no report figure.
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
