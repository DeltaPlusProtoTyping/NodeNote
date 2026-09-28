# Fork of NodeNote

Cloned from NodeNote (`Documents\nodeNote`) at commit 42b2845 (v1.0.2 + polish). New project
name/purpose not yet decided.

- Remote `upstream` = NodeNote on GitHub, fetch-only (push disabled). No `origin` yet — create a
  new GitHub repo before pushing.
- Windows `upgradeUuid` already changed so this app's MSI won't replace NodeNote's.
- Still shared with NodeNote until renamed: `packageName` / `bundleID` / linux `packageName` in
  `shared/build.gradle.kts`, prefs dir `~/.nodenote` (`ProjectStorage.desktop.kt`), window title
  (`main.kt`), `com.nodenote` package, README, `.github/workflows/release.yml`.

## Build / run
Kotlin 2.1.21 + Compose Multiplatform 1.8.2, Gradle 8.13, JDK 17 toolchain (PATH `java` is 25 —
Gradle uses JAVA_HOME/JDK 17). Run: `gradlew :shared:run`. MSI: `gradlew :shared:packageMsi`.

## Gotchas learned in NodeNote
- `pointerInput` must be keyed on the `AppState` (not `Unit`) — tabs swap AppState instances,
  and a `Unit` key freezes handlers onto the old one (pan/zoom went dead).
- Claude's sandbox virtualizes `%APPDATA%`; don't launch the app from Claude tools for real data.
