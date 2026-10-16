# Room schema exports

Room writes one JSON file per database version into this directory (configured by the
`room.schemaLocation` KSP argument in `app/build.gradle.kts`).

Version 1 is recorded before the first table exists, so every later schema change can ship as a
real, testable migration instead of a destructive wipe. Do not delete or hand-edit files here.
